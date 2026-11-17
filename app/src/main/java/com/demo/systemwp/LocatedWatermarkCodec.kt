package com.demo.systemwp

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** 定位版屏幕水印。R/B 通道承载横/纵向双频定位信号，G 通道承载正文。
 * 分离通道避免定位标识挤占正文槽位；所有通道仍使用同一个低透明度悬浮层。
 * 正文使用 6 像素周期、空间交织和扩展 Hamming 码，认证规则及密钥不变。
 */
object LocatedWatermarkCodec {
    const val OVERLAY_ALPHA = 6
    const val DEBUG_OVERLAY_ALPHA = 40
    const val MAX_BODY_BYTES = 128

    enum class Status { SUCCESS, NOT_FOUND, INSUFFICIENT_DATA, VALIDATION_FAILED }

    /** 只有通过本机 GCM 认证才返回载荷，其余字段用于诊断。 */
    data class Detection(
        val payload: WatermarkPayload?,
        val score: Double,
        val status: Status = if (payload != null) Status.SUCCESS else Status.NOT_FOUND,
        val headerBitsObserved: Int = 0,
        val bodyBitsObserved: Int = 0,
        val bodyBitsRequired: Int = 0,
        val format: String = "定位版 W2"
    )

    fun createShader(tile: Bitmap): BitmapShader =
        BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)

    private const val SIDE = 56
    private const val CHIP = 12
    private const val SLOTS = SIDE * SIDE
    private const val HEADER_BITS = 32
    private const val HEADER_REPEAT = 16
    private const val HEADER_SLOTS = HEADER_BITS * HEADER_REPEAT
    private const val MAGIC = 0x5732 // W2
    private const val FORMAT = "定位版 W2"
    private val nextRunId = AtomicLong()

    // 997 与 3136 互质：一一映射，连续位和重复副本分散到不同位置。
    private fun physical(logical: Int): Int = (logical * 997 + 313) % SLOTS

    fun buildTile(sealed: ByteArray): Bitmap? {
        if (sealed.isEmpty() || sealed.size > MAX_BODY_BYTES) return null
        val body = WatermarkErrorCorrection.encode(sealed)
        val repeat = (SLOTS - HEADER_SLOTS) / body.size
        if (repeat < 1) return null
        val header = WatermarkBits.bytesToBits(byteArrayOf(
            (MAGIC ushr 8).toByte(), MAGIC.toByte(),
            (sealed.size ushr 8).toByte(), sealed.size.toByte()
        ))
        val signs = IntArray(SLOTS) { WatermarkBits.pnSign(it) }
        fun put(logical: Int, bit: Int) {
            val slot = physical(logical)
            signs[slot] = WatermarkBits.pnSign(slot) * if (bit == 1) 1 else -1
        }
        for (r in 0 until HEADER_REPEAT) for (bit in header.indices) put(r * HEADER_BITS + bit, header[bit])
        for (r in 0 until repeat) for (bit in body.indices) put(HEADER_SLOTS + r * body.size + bit, body[bit])
        val pilots = IntArray(WatermarkLocator.TILE) {
            (128.0 + WatermarkLocator.signal(it + 0.5)).roundToInt().coerceIn(0, 255)
        }
        val pixels = IntArray(WatermarkLocator.TILE * WatermarkLocator.TILE)
        for (y in 0 until WatermarkLocator.TILE) for (x in 0 until WatermarkLocator.TILE) {
            val slot = y / CHIP * SIDE + x / CHIP
            val carrier = if ((x / 3 + y / 3) % 2 == 0) 1 else -1
            val green = if (carrier * signs[slot] > 0) 255 else 0
            pixels[y * WatermarkLocator.TILE + x] =
                (255 shl 24) or (pilots[x] shl 16) or (green shl 8) or pilots[y]
        }
        return Bitmap.createBitmap(pixels, WatermarkLocator.TILE, WatermarkLocator.TILE, Bitmap.Config.ARGB_8888)
    }

    /** 只读默认位置，不进行位置、尺度搜索。 */
    fun detectDirect(bitmap: Bitmap, checkCancelled: () -> Unit): Detection =
        Image(bitmap).inspect(1.0, 1.0, 0.0, 0.0, checkCancelled)

    suspend fun detectLocated(
        bitmap: Bitmap,
        checkCancelled: () -> Unit,
        onProgress: suspend (String) -> Unit
    ): WatermarkScaleSearch.Result? {
        val runId = nextRunId.incrementAndGet()
        fun diagnostic(message: String) {
            Log.i("SystemWP", "locator run=$runId $message")
        }
        diagnostic("start size=${bitmap.width}x${bitmap.height}")
        if (bitmap.width < 96 || bitmap.height < 96) {
            diagnostic("complete reason=image_too_small minimumSide=96")
            return null
        }
        val image = Image(bitmap)
        onProgress("正在快速检测原尺寸水印头部…")
        val probe = image.probeUnscaled(checkCancelled, ::diagnostic)
        var best: WatermarkScaleSearch.Result? = null
        var unscaledTried = false

        suspend fun tryUnscaled(): WatermarkScaleSearch.Result? {
            checkCancelled()
            unscaledTried = true
            onProgress("正在验证原尺寸水印…")
            // 优先完成预检找到的位置，认证失败才进入完整的原尺度位置搜索。
            val seeded = image.completeUnscaledProbe(probe, checkCancelled, ::diagnostic)
            if (seeded != null) {
                val result = WatermarkScaleSearch.Result(seeded, 1.0)
                val previous = best
                if (previous == null || better(seeded, previous.detection)) best = result
                if (seeded.payload != null) return result
            }
            val detection = image.detectUnscaled(checkCancelled, onProgress, ::diagnostic)
            if (detection != null) {
                val result = WatermarkScaleSearch.Result(detection, 1.0)
                val previous = best
                if (previous == null || better(detection, previous.detection)) best = result
                if (detection.payload != null) return result
            }
            return null
        }

        if (probe.strong) {
            diagnostic("route first=unscaled reason=strong_unscaled_header")
            tryUnscaled()?.let { return it }
            diagnostic("route fallback=scaled reason=unscaled_not_authenticated")
        }
        onProgress("正在搜索水印定位信号…")
        val projections = image.projections(checkCancelled)
        val candidates = WatermarkLocator.locateJoint(projections.first, projections.second, checkCancelled, ::diagnostic)
        if (!unscaledTried) {
            val clearScaledSignal = candidates.any {
                abs(it.x.scale - 1.0) >= 0.01 && it.score >= 0.3 && minOf(it.x.score, it.y.score) >= 0.15
            }
            // 仅用相关运算数估计相对成本，不把它当作设备上的实际耗时。
            val unscaledCost = 144L * SLOTS * magicSlots.size
            val scaledCost = if (candidates.isEmpty()) Long.MAX_VALUE
                else candidates.size * 9L * 36 * SLOTS * coarseMagicIndices.size
            val scaledFirst = clearScaledSignal || scaledCost < unscaledCost
            val reason = when {
                clearScaledSignal -> "strong_scaled_pilot"
                scaledFirst -> "ambiguous_lower_scaled_cost"
                else -> "ambiguous_lower_unscaled_cost"
            }
            diagnostic("route first=${if (scaledFirst) "scaled" else "unscaled"} reason=$reason " +
                "unscaledCorrelationOps=$unscaledCost scaledCorrelationOps=$scaledCost")
            if (!scaledFirst) {
                tryUnscaled()?.let { return it }
                diagnostic("route fallback=scaled reason=unscaled_not_authenticated")
            }
        }
        for ((index, candidate) in candidates.withIndex()) {
            checkCancelled()
            val x = candidate.x
            val y = candidate.y
            val pairDetails = "joint candidate=${index + 1} scale=${x.scale} " +
                "scoreX=${x.score} scoreY=${y.score} jointScore=${candidate.score}"
            onProgress("正在处理候选 ${index + 1} / ${candidates.size}：对齐并验证…")
            val scale = (x.scale + y.scale) / 2.0
            Log.i("SystemWP", "locator sx=${x.scale} sy=${y.scale} " +
                "x=${x.offset} y=${y.offset} scoreX=${x.score} scoreY=${y.score} run=$runId")
            val calibrated = image.calibrateHeader(scale, checkCancelled,
                onProgress = { progress ->
                    onProgress("正在处理候选 ${index + 1} / ${candidates.size}\n$progress")
                }) {
                diagnostic("candidate=${index + 1} $it")
            }
            if (calibrated != null) {
                val previous = best
                if (previous == null || better(calibrated.detection, previous.detection)) best = calibrated
                if (calibrated.detection.payload != null) {
                    diagnostic("complete reason=success stage=header_calibration $pairDetails " +
                        "calibratedScale=${calibrated.scale}")
                    return calibrated
                }
            }
        }
        if (!unscaledTried) {
            diagnostic("route fallback=unscaled reason=scaled_not_authenticated")
            tryUnscaled()?.let { return it }
        }
        val reason = if (candidates.isEmpty()) "no_joint_scale_candidates" else "all_decode_attempts_failed"
        diagnostic("complete reason=$reason jointCandidates=${candidates.size} " +
            "bestStatus=${best?.detection?.status}")
        return best
    }

    internal fun better(a: Detection, b: Detection): Boolean =
        a.payload != null || a.status.ordinal > b.status.ordinal ||
            (a.status == b.status && a.score > b.score)

    private class Samples(val sums: DoubleArray, val weights: DoubleArray)

    private val magicBits = WatermarkBits.bytesToBits(byteArrayOf((MAGIC ushr 8).toByte(), MAGIC.toByte()))
    private val magicSlots = IntArray(16 * HEADER_REPEAT) { physical(it / 16 * HEADER_BITS + it % 16) }
    private val magicSigns = IntArray(magicSlots.size) {
        WatermarkBits.pnSign(magicSlots[it]) * if (magicBits[it % 16] == 1) 1 else -1
    }
    private val shiftedCoordinates = Array(SIDE) { shift -> IntArray(SIDE) { Math.floorMod(it - shift, SIDE) } }
    // 粗定位使用每个 magic bit 的四份空间分散副本；精校准再读取全部 16 份。
    private val coarseMagicIndices = IntArray(64) { it / 16 * 64 + it % 16 }
    private val isMagicSlot = BooleanArray(SLOTS).also { mask -> magicSlots.forEach { mask[it] = true } }

    private class Image(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }

        private data class CropCandidate(val phaseX: Int, val phaseY: Int, val chipX: Int, val chipY: Int,
                                         val score: Double, val samples: Samples)

        data class Geometry(val scale: Double, val x: Double, val y: Double, val score: Double)
        private data class HeaderEvidence(val score: Double, val matchingBits: Int, val observedSlots: Int,
                                          val coherence: Double)
        data class UnscaledProbe(val positions: List<Geometry>, val strong: Boolean)
        // 预检与完整原尺度搜索共用采样，避免再次读取同一像素相位。
        private val unscaledPhaseSamples = arrayOfNulls<Samples>(CHIP * CHIP)

        fun probeUnscaled(checkCancelled: () -> Unit, diagnostic: (String) -> Unit): UnscaledProbe {
            val positions = searchHeaderPositions(1.0, checkCancelled)
            val measured = positions.map { it to headerEvidence(it, checkCancelled) }
                .sortedByDescending { it.second.score }
            val strongest = measured.firstOrNull()?.second
            val strong = strongest != null && strongest.score >= 0.3 && strongest.matchingBits >= 14 &&
                strongest.observedSlots >= 128 && strongest.coherence >= 0.25
            diagnostic("probe scale=1.0 phases=36 coarseMagicSlots=64 positions=${positions.size} " +
                "score=${strongest?.score} magicMatches=${strongest?.matchingBits}/16 " +
                "magicSlots=${strongest?.observedSlots}/256 coherence=${strongest?.coherence} strong=$strong")
            // 只检查已知 magic，不读取长度、正文，也不在预检阶段执行认证。
            return UnscaledProbe(measured.map { (geometry, evidence) -> geometry.copy(score = evidence.score) }, strong)
        }

        fun completeUnscaledProbe(probe: UnscaledProbe, checkCancelled: () -> Unit,
                                  diagnostic: (String) -> Unit): Detection? {
            var best: Detection? = null
            for ((index, position) in probe.positions.withIndex()) {
                checkCancelled()
                val geometry = refineHeader(position, checkCancelled, adjustScale = false)
                val detection = inspect(1.0, 1.0, geometry.x, geometry.y, checkCancelled)
                diagnostic("unscaled probeCandidate=${index + 1} scale=1.0 offsetX=${geometry.x} " +
                    "offsetY=${geometry.y} status=${detection.status} score=${detection.score}")
                val previous = best
                if (previous == null || better(detection, previous)) best = detection
                if (detection.payload != null) {
                    diagnostic("complete reason=success stage=unscaled source=probe scale=1.0")
                    return detection
                }
            }
            return best
        }

        /** 频谱只提供尺度初值；位置及最终尺度由已知 magic 的真实 G 通道信号校准。
         * 整数恢复宽度保留相邻值，连续采样避免再次缩放 bitmap 引入插值。
         */
        suspend fun calibrateHeader(
            estimatedScale: Double,
            checkCancelled: () -> Unit,
            onProgress: suspend (String) -> Unit,
            diagnostic: (String) -> Unit
        ): WatermarkScaleSearch.Result? {
            val centerWidth = (width * estimatedScale).roundToInt()
            val widths = (listOf(0) + (1..4).flatMap { listOf(-it, it) })
                .map { centerWidth + it }
                .filter { it >= 64 && it.toDouble() / width in 0.45..1.10 }
            diagnostic("calibration start estimatedScale=$estimatedScale centerWidth=$centerWidth " +
                "widths=${widths.joinToString()} phaseStep=2 coarseMagicSlots=64")
            var best: WatermarkScaleSearch.Result? = null
            var attempts = 0
            for ((index, restoredWidth) in widths.withIndex()) {
                checkCancelled()
                val scale = restoredWidth.toDouble() / width
                onProgress("正在用水印头部校准比例和位置：${index + 1} / ${widths.size}")
                val positions = searchHeaderPositions(scale, checkCancelled)
                diagnostic("calibration width=$restoredWidth scale=$scale positions=${positions.size} " +
                    "coarseScore=${positions.firstOrNull()?.score}")
                // 每个恢复宽度独立保留位置，不让错误宽度的高分提前淘汰相邻宽度。
                for (position in positions) {
                    val geometry = refineHeader(position, checkCancelled)
                    val evidence = headerEvidence(geometry, checkCancelled)
                    val detection = inspect(geometry.scale, geometry.scale, geometry.x, geometry.y, checkCancelled)
                    val result = WatermarkScaleSearch.Result(detection, geometry.scale)
                    attempts++
                    diagnostic("calibration attempt=$attempts restoredWidth=$restoredWidth " +
                        "scale=${geometry.scale} offsetX=${geometry.x} offsetY=${geometry.y} " +
                        "coarseScore=${position.score} headerScore=${evidence.score} " +
                        "magicMatches=${evidence.matchingBits}/16 magicSlots=${evidence.observedSlots}/256 " +
                        "status=${detection.status} header=${detection.headerBitsObserved}/32 " +
                        "body=${detection.bodyBitsObserved}/${detection.bodyBitsRequired}")
                    val previous = best
                    if (previous == null || better(detection, previous.detection)) best = result
                    if (detection.payload != null) {
                        diagnostic("calibration complete reason=success attempts=$attempts scale=${geometry.scale}")
                        return result
                    }
                }
            }
            diagnostic("calibration complete reason=no_authenticated_candidate attempts=$attempts " +
                "bestStatus=${best?.detection?.status}")
            return best
        }

        private fun searchHeaderPositions(scale: Double, checkCancelled: () -> Unit): List<Geometry> {
            val strongest = ArrayList<Geometry>()
            for (py in 0 until CHIP step 2) for (px in 0 until CHIP step 2) {
                checkCancelled()
                val samples = if (scale == 1.0) {
                    val key = py * CHIP + px
                    unscaledPhaseSamples[key] ?: sample(scale, scale, px.toDouble(), py.toDouble(), checkCancelled)
                        .also { unscaledPhaseSamples[key] = it }
                } else sample(scale, scale, px.toDouble(), py.toDouble(), checkCancelled)
                val averages = DoubleArray(SLOTS) {
                    if (samples.weights[it] > 0) samples.sums[it] / samples.weights[it] else 0.0
                }
                for (cy in 0 until SIDE) {
                    checkCancelled()
                    val rows = shiftedCoordinates[cy]
                    for (cx in 0 until SIDE) {
                        val columns = shiftedCoordinates[cx]
                        var sum = 0.0
                        var count = 0
                        var observed = 0
                        for (i in coarseMagicIndices) {
                            val slot = magicSlots[i]
                            val source = rows[slot / SIDE] * SIDE + columns[slot % SIDE]
                            if (samples.weights[source] > 0) {
                                sum += magicSigns[i] * averages[source]
                                count++
                                observed = observed or (1 shl (i % 16))
                            }
                        }
                        if (count < 32 || Integer.bitCount(observed) < 12) continue
                        val score = sum / count * kotlin.math.sqrt(count / 64.0)
                        if (score <= 0 || (strongest.size == 32 && score <= strongest.last().score)) continue
                        strongest.add(Geometry(scale, (px + cx * CHIP).toDouble(),
                            (py + cy * CHIP).toDouble(), score))
                        strongest.sortByDescending { it.score }
                        if (strongest.size > 32) strongest.removeAt(strongest.lastIndex)
                    }
                }
            }
            // 相同位置附近的像素相位不应占满四个精校准名额。
            val selected = ArrayList<Geometry>()
            for (candidate in strongest) {
                if (selected.all { periodicDistance(it.x, candidate.x) > 4.0 || periodicDistance(it.y, candidate.y) > 4.0 }) {
                    selected.add(candidate)
                    if (selected.size == 4) break
                }
            }
            return selected
        }

        private fun periodicDistance(a: Double, b: Double): Double {
            val distance = abs(a - b) % WatermarkLocator.TILE
            return minOf(distance, WatermarkLocator.TILE - distance)
        }

        private fun headerEvidence(geometry: Geometry, checkCancelled: () -> Unit): HeaderEvidence {
            val samples = sample(geometry.scale, geometry.scale, geometry.x, geometry.y, checkCancelled, magicOnly = true)
            val bitSums = DoubleArray(16)
            var sum = 0.0
            var magnitude = 0.0
            var count = 0
            for (i in magicSlots.indices) {
                val slot = magicSlots[i]
                if (samples.weights[slot] > 0) {
                    val value = magicSigns[i] * samples.sums[slot] / samples.weights[slot]
                    sum += value
                    magnitude += abs(value)
                    bitSums[i % 16] += value
                    count++
                }
            }
            val score = if (count < 64) 0.0 else sum / count * kotlin.math.sqrt(count / 256.0)
            return HeaderEvidence(score, bitSums.count { it > 0 }, count,
                if (magnitude > 0) sum / magnitude else 0.0)
        }

        private fun refineHeader(initial: Geometry, checkCancelled: () -> Unit,
                                 adjustScale: Boolean = true): Geometry {
            var best = initial.copy(score = headerEvidence(initial, checkCancelled).score)
            // 位置与比例交替优化。改变比例时保持图像中心的水印坐标，减少两者的耦合。
            for (step in doubleArrayOf(1.0, 0.5, 0.25)) {
                repeat(2) {
                    val center = best
                    for (dy in -1..1) for (dx in -1..1) {
                        checkCancelled()
                        val candidate = center.copy(x = center.x + dx * step, y = center.y + dy * step)
                        val score = headerEvidence(candidate, checkCancelled).score
                        if (score > best.score) best = candidate.copy(score = score)
                    }
                    val scaleCenter = best
                    for (direction in if (adjustScale) intArrayOf(-1, 1) else intArrayOf()) {
                        val delta = direction * step / (2.0 * width)
                        val scale = scaleCenter.scale + delta
                        if (scale !in 0.45..1.10) continue
                        val candidate = Geometry(scale, scaleCenter.x - delta * width / 2,
                            scaleCenter.y - delta * height / 2, 0.0)
                        val score = headerEvidence(candidate, checkCancelled).score
                        if (score > best.score) best = candidate.copy(score = score)
                    }
                }
            }
            return best
        }

        suspend fun detectUnscaled(
            checkCancelled: () -> Unit,
            onProgress: suspend (String) -> Unit,
            diagnostic: (String) -> Unit
        ): Detection? {
            val candidates = ArrayList<CropCandidate>()
            diagnostic("unscaled start scale=1.0 phases=144 chipOffsets=3136 limit=24")
            for (py in 0 until CHIP) {
                onProgress("正在按原尺寸搜索裁剪位置：${py + 1} / $CHIP")
                for (px in 0 until CHIP) {
                    checkCancelled()
                    val samples = unscaledPhaseSamples[py * CHIP + px] ?:
                        sample(1.0, 1.0, px.toDouble(), py.toDouble(), checkCancelled)
                    val averages = DoubleArray(SLOTS) {
                        if (samples.weights[it] > 0) samples.sums[it] / samples.weights[it] else 0.0
                    }
                    for (cy in 0 until SIDE) {
                        checkCancelled()
                        val rows = shiftedCoordinates[cy]
                        for (cx in 0 until SIDE) {
                            val columns = shiftedCoordinates[cx]
                            var sum = 0.0
                            var count = 0
                            var observed = 0
                            for (i in magicSlots.indices) {
                                val slot = magicSlots[i]
                                val source = rows[slot / SIDE] * SIDE + columns[slot % SIDE]
                                if (samples.weights[source] > 0) {
                                    sum += magicSigns[i] * averages[source]
                                    count++
                                    observed = observed or (1 shl (i % 16))
                                }
                            }
                            if (count < 64 || Integer.bitCount(observed) < 12) continue
                            val score = sum / count * kotlin.math.sqrt(count.toDouble() / magicSlots.size)
                            if (score <= 0 || (candidates.size == 24 && score <= candidates.last().score)) continue
                            candidates.add(CropCandidate(px, py, cx, cy, score, samples))
                            candidates.sortByDescending { it.score }
                            if (candidates.size > 24) candidates.removeAt(candidates.lastIndex)
                        }
                    }
                }
                diagnostic("unscaled phaseRow=${py + 1}/$CHIP retained=${candidates.size} bestScore=${candidates.firstOrNull()?.score}")
            }
            var best: Detection? = null
            for ((index, candidate) in candidates.withIndex()) {
                checkCancelled()
                val sums = DoubleArray(SLOTS)
                val weights = DoubleArray(SLOTS)
                for (slot in 0 until SLOTS) {
                    val source = shiftedCoordinates[candidate.chipY][slot / SIDE] * SIDE +
                        shiftedCoordinates[candidate.chipX][slot % SIDE]
                    sums[slot] = candidate.samples.sums[source]
                    weights[slot] = candidate.samples.weights[source]
                }
                val detection = inspect(Samples(sums, weights))
                diagnostic("unscaled candidate=${index + 1} scale=1.0 " +
                    "offsetX=${candidate.phaseX + candidate.chipX * CHIP} " +
                    "offsetY=${candidate.phaseY + candidate.chipY * CHIP} score=${candidate.score} " +
                    "status=${detection.status} header=${detection.headerBitsObserved}/32 " +
                    "body=${detection.bodyBitsObserved}/${detection.bodyBitsRequired}")
                val previous = best
                if (previous == null || better(detection, previous)) best = detection
                if (detection.payload != null) {
                    diagnostic("complete reason=success stage=unscaled scale=1.0")
                    return detection
                }
            }
            diagnostic("unscaled complete reason=no_authenticated_candidate candidates=${candidates.size} bestStatus=${best?.status}")
            return best
        }

        fun projections(checkCancelled: () -> Unit): Pair<DoubleArray, DoubleArray> {
            val x = DoubleArray(width)
            val y = DoubleArray(height)
            // 色差抵消灰度背景和文字；正文的零均值棋盘在投影时大体抵消。
            for (row in 0 until height) {
                if (row % 32 == 0) checkCancelled()
                for (column in 0 until width) {
                    val pixel = pixels[row * width + column]
                    val g = (pixel ushr 8) and 255
                    x[column] += ((pixel ushr 16) and 255) - g
                    y[row] += (pixel and 255) - g
                }
            }
            for (i in x.indices) x[i] /= height.toDouble()
            for (i in y.indices) y[i] /= width.toDouble()
            return x to y
        }

        private fun green(x: Double, y: Double): Double {
            val ix = floor(x).toInt()
            val iy = floor(y).toInt()
            val fx = x - ix
            val fy = y - iy
            fun at(dx: Int, dy: Int): Double = ((pixels[(iy + dy) * width + ix + dx] ushr 8) and 255).toDouble()
            return (at(0, 0) * (1 - fx) + at(1, 0) * fx) * (1 - fy) +
                (at(0, 1) * (1 - fx) + at(1, 1) * fx) * fy
        }

        fun inspect(sx: Double, sy: Double, ox: Double, oy: Double,
                    checkCancelled: () -> Unit): Detection =
            inspect(sample(sx, sy, ox, oy, checkCancelled))

        private fun sample(sx: Double, sy: Double, ox: Double, oy: Double,
                           checkCancelled: () -> Unit, magicOnly: Boolean = false): Samples {
            val sums = DoubleArray(SLOTS)
            val weights = DoubleArray(SLOTS)
            // 每个 6×6 棋盘的四个子块中心配对作差，不需要把整图缩放取整。
            // sx/sy 和 ox/oy 直接用于采样，因此保留亚像素尺度和偏移。
            val minX = floor(ox / CHIP).toInt()
            val maxX = ceil((ox + width * sx) / CHIP).toInt()
            val minY = floor(oy / CHIP).toInt()
            val maxY = ceil((oy + height * sy) / CHIP).toInt()
            for (cy in minY until maxY) {
                checkCancelled()
                for (cx in minX until maxX) {
                    val slot = Math.floorMod(cy, SIDE) * SIDE + Math.floorMod(cx, SIDE)
                    if (magicOnly && !isMagicSlot[slot]) continue
                    for (by in 0..1) for (bx in 0..1) {
                        val x0 = (cx * CHIP + bx * 6 + 1.5 - ox) / sx - 0.5
                        val x1 = (cx * CHIP + bx * 6 + 4.5 - ox) / sx - 0.5
                        val y0 = (cy * CHIP + by * 6 + 1.5 - oy) / sy - 0.5
                        val y1 = (cy * CHIP + by * 6 + 4.5 - oy) / sy - 0.5
                        if (x0 < 0 || y0 < 0 || x1 >= width - 1 || y1 >= height - 1) continue
                        val a = green(x0, y0)
                        val b = green(x1, y0)
                        val c = green(x0, y1)
                        val d = green(x1, y1)
                        val gradient = (abs(a - b) + abs(a - c) + abs(b - d) + abs(c - d)) / 4.0
                        val weight = 1.0 / (1.0 + gradient * gradient / (24.0 * 24.0))
                        sums[slot] += (a - b - c + d) / 4.0 * weight
                        weights[slot] += weight
                    }
                }
            }
            return Samples(sums, weights)
        }

        private fun inspect(samples: Samples): Detection {
            val sums = samples.sums
            val weights = samples.weights
            fun read(first: Int, bit: Int, bits: Int, repeats: Int): Double? {
                var sum = 0.0
                var weight = 0.0
                for (r in 0 until repeats) {
                    val slot = physical(first + r * bits + bit)
                    if (weights[slot] > 0) {
                        sum += WatermarkBits.pnSign(slot) * sums[slot]
                        weight += weights[slot]
                    }
                }
                return if (weight > 0) sum / weight else null
            }
            val headerValues = Array(HEADER_BITS) { read(0, it, HEADER_BITS, HEADER_REPEAT) }
            val observedHeader = headerValues.count { it != null }
            val expected = WatermarkBits.bytesToBits(byteArrayOf((MAGIC ushr 8).toByte(), MAGIC.toByte()))
            val score = (0 until 16).sumOf {
                (headerValues[it] ?: 0.0) * if (expected[it] == 1) 1 else -1
            } / 16.0
            fun result(status: Status, observed: Int = 0, required: Int = 0,
                       payload: WatermarkPayload? = null) = Detection(
                payload, max(0.0, score), status, observedHeader, observed, required, FORMAT)
            val header = WatermarkBits.bitsToBytes(IntArray(HEADER_BITS) { if ((headerValues[it] ?: 0.0) > 0) 1 else 0 })
            if ((header[0].toInt() and 255) != (MAGIC ushr 8) ||
                (header[1].toInt() and 255) != (MAGIC and 255)) return result(Status.NOT_FOUND)
            if (observedHeader < HEADER_BITS) return result(Status.INSUFFICIENT_DATA)
            val length = ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
            if (length !in 1..MAX_BODY_BYTES) return result(Status.NOT_FOUND)
            val bits = length * 16
            val repeats = (SLOTS - HEADER_SLOTS) / bits
            if (repeats < 1) return result(Status.NOT_FOUND)
            val values = Array(bits) { read(HEADER_SLOTS, it, bits, repeats) }
            val observed = values.count { it != null }
            val encoded = Array<Int?>(bits) { values[it]?.let { value -> if (value > 0) 1 else 0 } }
            val sealed = WatermarkErrorCorrection.decode(encoded) ?: return result(
                if (observed < bits) Status.INSUFFICIENT_DATA else Status.VALIDATION_FAILED,
                observed, bits)
            val payload = WatermarkCrypto.open(sealed)?.let { WatermarkPayload.decode(it) }
            return result(if (payload == null) Status.VALIDATION_FAILED else Status.SUCCESS,
                observed, bits, payload)
        }
    }
}
