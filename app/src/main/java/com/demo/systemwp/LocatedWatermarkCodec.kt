package com.demo.systemwp

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/** 定位版屏幕水印。R/B 通道承载多方向频谱定位模板，G 通道承载正文。
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
        val format: String = "定位版 W2",
        val horizontalMirrored: Boolean = false,
        val rotationDegrees: Double = 0.0
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

    private class SearchLimitReached : RuntimeException()

    /** 每条路径独立限时；预算耗尽仅结束当前路径，用户取消仍向外传播。 */
    private class SearchBudget(milliseconds: Long, private val checkCancelled: () -> Unit) {
        val deadline = SystemClock.elapsedRealtimeNanos() + milliseconds * 1_000_000
        fun check() {
            checkCancelled()
            if (SystemClock.elapsedRealtimeNanos() >= deadline) throw SearchLimitReached()
        }
        fun remainingMs(): Long = maxOf(0, (deadline - SystemClock.elapsedRealtimeNanos()) / 1_000_000)
    }

    private enum class Route(val stage: WatermarkParseProgress.Stage, val budgetMs: Long) {
        ORIGINAL(WatermarkParseProgress.Stage.ORIGINAL_SEARCH, 10000),
        SCALED(WatermarkParseProgress.Stage.SCALED_SEARCH, 50000),
        ROTATION(WatermarkParseProgress.Stage.ROTATION_SEARCH, 200000)
    }

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
        val pixels = IntArray(WatermarkLocator.TILE * WatermarkLocator.TILE)
        for (y in 0 until WatermarkLocator.TILE) for (x in 0 until WatermarkLocator.TILE) {
            val slot = y / CHIP * SIDE + x / CHIP
            val carrier = if ((x / 3 + y / 3) % 2 == 0) 1 else -1
            val green = if (carrier * signs[slot] > 0) 255 else 0
            pixels[y * WatermarkLocator.TILE + x] =
                (255 shl 24) or (WatermarkSpectralTemplate.redPixel(x, y) shl 16) or
                    (green shl 8) or WatermarkSpectralTemplate.bluePixel(x, y)
        }
        return Bitmap.createBitmap(pixels, WatermarkLocator.TILE, WatermarkLocator.TILE, Bitmap.Config.ARGB_8888)
    }

    /** 只读默认位置，不进行位置、尺度搜索。 */
    fun detectDirect(bitmap: Bitmap, checkCancelled: () -> Unit): Detection =
        Image(bitmap).inspect(1.0, 1.0, 0.0, 0.0, checkCancelled)

    /** 屏幕尺寸补偿的直接读取：共用像素缓存，尝试四个旋转方向及其镜像。 */
    fun detectDirectWithMirror(bitmap: Bitmap, checkCancelled: () -> Unit): Detection {
        val image = Image(bitmap)
        var best: Detection? = null
        for (rotation in 0..3) {
            val rotated = image.rotated(rotation)
            for (direction in listOf(rotated, rotated.mirrored())) {
                checkCancelled()
                val result = direction.inspect(1.0, 1.0, 0.0, 0.0, checkCancelled)
                if (result.payload != null) return result
                if (best == null || better(result, best)) best = result
            }
        }
        return requireNotNull(best)
    }

    suspend fun detectLocated(
        bitmap: Bitmap,
        checkCancelled: () -> Unit,
        onProgress: suspend (WatermarkParseProgress) -> Unit,
        timings: WatermarkTimings? = null
    ): WatermarkScaleSearch.Result? {
        val started = SystemClock.elapsedRealtimeNanos()
        try {
            return detectLocatedInternal(bitmap, checkCancelled, onProgress, timings)
        } finally {
            timings?.record("locator_total", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
        }
    }

    private suspend fun detectLocatedInternal(
        bitmap: Bitmap,
        checkCancelled: () -> Unit,
        onProgress: suspend (WatermarkParseProgress) -> Unit,
        timings: WatermarkTimings?
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
        val normalImage = Image(bitmap)
        val startedAt = SystemClock.elapsedRealtimeNanos()
        fun elapsedMs() = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000.0
        var best: WatermarkScaleSearch.Result? = null
        fun remember(result: WatermarkScaleSearch.Result?) {
            if (result != null && (best == null || better(result.detection, requireNotNull(best).detection))) best = result
        }
        onProgress(WatermarkParseProgress(WatermarkParseProgress.Stage.HEADER_PROBE,
            "正在预检原尺度固定头部，判断是否优先搜索裁剪位置…"))
        val probeBudget = SearchBudget(1500, checkCancelled)
        val probe = measured("header_probe", ::diagnostic, timings) {
            normalImage.probeHeader(probeBudget, ::diagnostic)
        }
        val attempted = mutableSetOf<Route>()
        var angles = emptyList<WatermarkAngleEstimator.Candidate>()
        suspend fun runRoute(route: Route) {
            if (!attempted.add(route)) return
            val budget = SearchBudget(route.budgetMs, checkCancelled)
            diagnostic("route start=$route budgetMs=${route.budgetMs}")
            suspend fun report(detail: String) {
                onProgress(WatermarkParseProgress(route.stage, detail))
            }
            measured("route_${route.name.lowercase()}", ::diagnostic, timings) {
                try {
                    when (route) {
                        Route.ORIGINAL -> {
                            report("原尺度路径：按固定头部搜索裁剪偏移…")
                            remember(normalImage.decodePositions(probe.positions, false, budget, timings, ::diagnostic))
                            if (best?.detection?.payload == null) {
                                val positions = normalImage.searchHeaderPositions(1.0, 1, budget, 1400, ::diagnostic)
                                remember(normalImage.decodePositions(positions, false, budget, timings, ::diagnostic))
                            }
                        }
                        Route.SCALED -> {
                            report("无旋转缩放路径：估计比例并搜索裁剪偏移…")
                            val axis = normalImage.locateAxes(budget::check, ::diagnostic)
                            val scales = (angles.filter { nearZero(it.degrees) && !it.mirrored }.map { it.scale } +
                                axis.map { it.x.scale }).filter { abs(it - 1.0) > 0.001 }
                                .fold(mutableListOf<Double>()) { result, scale ->
                                    if (result.none { abs(it - scale) < 0.002 }) result.add(scale)
                                    result
                                }.take(3)
                            for ((index, scale) in scales.withIndex()) {
                                report("无旋转缩放路径：候选 ${index + 1}/${scales.size}，校准比例和位置…")
                                val seeds = axis.filter { abs(it.x.scale - scale) < 0.025 }.take(2).map {
                                    Image.Geometry(scale, it.x.offset, it.y.offset, 0.0)
                                }
                                remember(normalImage.decodePositions(seeds, true, budget, timings, ::diagnostic))
                                if (best?.detection?.payload != null) break
                                for (delta in doubleArrayOf(0.0, -1.0 / bitmap.width, 1.0 / bitmap.width)) {
                                    val adjusted = scale + delta
                                    if (adjusted !in 0.45..1.10) continue
                                    val positions = normalImage.searchHeaderPositions(adjusted, 2, budget, 700, ::diagnostic)
                                    remember(normalImage.decodePositions(positions, true, budget, timings, ::diagnostic))
                                    if (best?.detection?.payload != null) break
                                }
                                if (best?.detection?.payload != null) break
                            }
                        }
                        Route.ROTATION -> {
                            fixedLoop@ for (turns in 1..3) {
                                for (mirror in listOf(false, true)) {
                                    budget.check()
                                    val rotated = normalImage.rotated(turns)
                                    val image = if (mirror) rotated.mirrored() else rotated
                                    val label = "${turns * 90}° 镜像=$mirror"
                                    report("旋转路径：优先尝试固定角度 $label…")
                                    remember(measured("fixed_angle_alignment direction=$label", ::diagnostic, timings) {
                                        image.detectFixedDirection(angles.map { it.scale }, budget, timings, ::diagnostic)
                                    })
                                    if (best?.detection?.payload != null) break@fixedLoop
                                }
                            }
                            if (best?.detection?.payload != null) return@measured
                            report("固定角度未认证，继续按频谱角度候选联合校准…")
                            rotationLoop@ for ((index, angle) in angles.take(4).withIndex()) {
                                for (halfTurn in 0..1) for (mirror in listOf(angle.mirrored, !angle.mirrored)) {
                                    budget.check()
                                    val degrees = (angle.degrees + halfTurn * 180 + 360) % 360
                                    val rotated = normalImage.atAngle(degrees)
                                    val image = if (mirror) rotated.mirrored() else rotated
                                    val label = "${"%.2f".format(java.util.Locale.US, degrees)}° 镜像=$mirror"
                                    report("旋转路径：候选 ${index + 1}/${angles.size}，$label，联合校准并认证…")
                                    val result = measured("angle_alignment direction=$label", ::diagnostic) {
                                        image.detectEstimated(angle.scale, budget, timings) { diagnostic("angle=$label $it") }
                                    }
                                    remember(result)
                                    if (best?.detection?.payload != null) break@rotationLoop
                                }
                            }
                        }
                    }
                } catch (_: SearchLimitReached) {
                    diagnostic("route complete=$route reason=time_budget")
                    onProgress(WatermarkParseProgress(route.stage,
                        "达到路径预算（${route.budgetMs / 1000} 秒），已退出当前路径，准备回退…",
                        budgetExceeded = true))
                }
            }
            diagnostic("route complete=$route authenticated=${best?.detection?.payload != null}")
        }
        // 头部证据强时直接验证原尺度位置，成功即可省去整个 FFT。
        if (probe.strong) {
            runRoute(Route.ORIGINAL)
            if (best?.detection?.payload != null) {
                diagnostic("orientation complete reason=original_success totalMs=${elapsedMs()}")
                return best
            }
        }
        onProgress(WatermarkParseProgress(WatermarkParseProgress.Stage.SPECTRAL_LOCATION,
            "正在估计角度和比例，选择原尺度、缩放或旋转路径…"))
        angles = measured("angle_estimation", ::diagnostic, timings) {
            WatermarkAngleEstimator.estimate(normalImage.pixels, bitmap.width, bitmap.height,
                checkCancelled, ::diagnostic)
        }
        onProgress(WatermarkParseProgress(WatermarkParseProgress.Stage.SPECTRAL_LOCATION,
            "频谱预检已结束，正在选择解析路径…",
            estimatedAngles = angles.map { (it.degrees % 360 + 360) % 360 }, spectrumChecked = true))
        val strongest = angles.firstOrNull()?.takeIf { it.score >= 0.04 }
        val order = when {
            strongest == null -> listOf(Route.ORIGINAL, Route.SCALED, Route.ROTATION)
            !nearZero(strongest.degrees) || strongest.mirrored -> listOf(Route.ROTATION, Route.SCALED, Route.ORIGINAL)
            abs(strongest.scale - 1.0) >= 0.01 -> listOf(Route.SCALED, Route.ORIGINAL, Route.ROTATION)
            else -> listOf(Route.ORIGINAL, Route.SCALED, Route.ROTATION)
        }
        diagnostic("routing headerStrong=${probe.strong} order=${order.joinToString()} " +
            "angle=${strongest?.degrees} scale=${strongest?.scale} mirror=${strongest?.mirrored}")
        for (route in order) {
            runRoute(route)
            if (best?.detection?.payload != null) {
                diagnostic("orientation complete reason=${route.name.lowercase()}_success totalMs=${elapsedMs()}")
                return best
            }
        }
        diagnostic("orientation complete reason=no_authenticated_route totalMs=${elapsedMs()}")
        return best
    }

    private fun nearZero(degrees: Double): Boolean = abs((degrees + 540) % 360 - 180) <= 0.35

    internal fun better(a: Detection, b: Detection): Boolean =
        a.payload != null || a.status.ordinal > b.status.ordinal ||
            (a.status == b.status && a.score > b.score)

    private inline fun <T> measured(
        stage: String,
        diagnostic: (String) -> Unit,
        timings: WatermarkTimings? = null,
        block: () -> T
    ): T {
        val started = SystemClock.elapsedRealtimeNanos()
        try {
            return block()
        } finally {
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            timings?.record(stage, elapsedMs)
            diagnostic("timing stage=$stage elapsedMs=$elapsedMs")
        }
    }

    private class Samples(val sums: DoubleArray, val weights: DoubleArray)

    private val magicBits = WatermarkBits.bytesToBits(byteArrayOf((MAGIC ushr 8).toByte(), MAGIC.toByte()))
    private val magicSlots = IntArray(16 * HEADER_REPEAT) { physical(it / 16 * HEADER_BITS + it % 16) }
    private val magicSigns = IntArray(magicSlots.size) {
        WatermarkBits.pnSign(magicSlots[it]) * if (magicBits[it % 16] == 1) 1 else -1
    }
    private val isMagicSlot = BooleanArray(SLOTS).also { mask -> magicSlots.forEach { mask[it] = true } }
    private val shiftedCoordinates = Array(SIDE) { shift -> IntArray(SIDE) { Math.floorMod(it - shift, SIDE) } }
    private val coarseMagicIndices = IntArray(64) { it / 16 * 64 + it % 16 }

    private class Image private constructor(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val pixels: IntArray,
        val horizontalMirrored: Boolean,
        val quarterTurns: Int,
        val arbitraryDegrees: Double? = null
    ) {
        constructor(bitmap: Bitmap) : this(bitmap.width, bitmap.height,
            IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }, false, 0)

        private val radians = (arbitraryDegrees ?: 0.0) * PI / 180
        private val rotationCos = cos(radians)
        private val rotationSin = sin(radians)
        val width: Int = if (arbitraryDegrees != null)
            ceil(abs(rotationCos) * sourceWidth + abs(rotationSin) * sourceHeight).toInt()
            else if (quarterTurns % 2 == 0) sourceWidth else sourceHeight
        val height: Int = if (arbitraryDegrees != null)
            ceil(abs(rotationSin) * sourceWidth + abs(rotationCos) * sourceHeight).toInt()
            else if (quarterTurns % 2 == 0) sourceHeight else sourceWidth

        // 各方向共享不可变像素，位置和投影缓存各自独立。
        fun mirrored(): Image = Image(sourceWidth, sourceHeight, pixels, !horizontalMirrored, quarterTurns, arbitraryDegrees)

        fun rotated(turns: Int): Image = Image(sourceWidth, sourceHeight, pixels, false, turns)

        fun atAngle(degrees: Double): Image = Image(sourceWidth, sourceHeight, pixels, false, 0, degrees)

        private fun sourceX(x: Double, y: Double): Double {
            val vx = (if (horizontalMirrored) width - 1 - x else x) - (width - 1) / 2.0
            return rotationCos * vx + rotationSin * (y - (height - 1) / 2.0) + (sourceWidth - 1) / 2.0
        }

        private fun sourceY(x: Double, y: Double): Double {
            val vx = (if (horizontalMirrored) width - 1 - x else x) - (width - 1) / 2.0
            return -rotationSin * vx + rotationCos * (y - (height - 1) / 2.0) + (sourceHeight - 1) / 2.0
        }

        // 范围外返回 NaN，缺失区域不作为黑色像素参与定位或比特统计。
        private fun channel(x: Double, y: Double, shift: Int): Double {
            val sx = sourceX(x, y)
            val sy = sourceY(x, y)
            if (sx < 0 || sy < 0 || sx >= sourceWidth - 1 || sy >= sourceHeight - 1) return Double.NaN
            val ix = floor(sx).toInt(); val iy = floor(sy).toInt()
            val fx = sx - ix; val fy = sy - iy
            fun at(dx: Int, dy: Int) = ((pixels[(iy + dy) * sourceWidth + ix + dx] ushr shift) and 255).toDouble()
            return (at(0, 0) * (1 - fx) + at(1, 0) * fx) * (1 - fy) +
                (at(0, 1) * (1 - fx) + at(1, 1) * fx) * fy
        }

        // 先顺时针旋转，再在旋转后的坐标中应用水平镜像；整数坐标映射不插值。
        private fun pixelAt(column: Int, row: Int): Int {
            val x = if (horizontalMirrored) width - 1 - column else column
            val index = when (quarterTurns) {
                0 -> row * sourceWidth + x
                1 -> (sourceHeight - 1 - x) * sourceWidth + row
                2 -> (sourceHeight - 1 - row) * sourceWidth + sourceWidth - 1 - x
                3 -> x * sourceWidth + sourceWidth - 1 - row
                else -> error("Unsupported rotation: $quarterTurns")
            }
            return pixels[index]
        }

        data class Geometry(val scale: Double, val x: Double, val y: Double, val score: Double)
        private data class HeaderEvidence(val score: Double, val matchingBits: Int, val observedSlots: Int,
                                          val coherence: Double)
        data class HeaderProbe(val positions: List<Geometry>, val strong: Boolean)
        private val originalPhaseCandidates = arrayOfNulls<List<Geometry>>(CHIP * CHIP)
        private var axisCandidates: List<WatermarkLocator.Candidate>? = null

        fun probeHeader(budget: SearchBudget, diagnostic: (String) -> Unit): HeaderProbe {
            var positions = emptyList<Geometry>()
            var strong = false
            try {
                positions = searchHeaderPositions(1.0, 2, budget, 900, diagnostic)
                for (position in positions) {
                    budget.check()
                    val evidence = headerEvidence(position, budget::check)
                    if (evidence.score >= 0.3 && evidence.matchingBits >= 14 &&
                        evidence.observedSlots >= 128 && evidence.coherence >= 0.25) strong = true
                    diagnostic("header_probe score=${evidence.score} matches=${evidence.matchingBits}/16 " +
                        "slots=${evidence.observedSlots}/256 coherence=${evidence.coherence}")
                }
            } catch (_: SearchLimitReached) {
                diagnostic("header_probe reason=time_budget")
            }
            diagnostic("header_probe positions=${positions.size} strong=$strong")
            return HeaderProbe(positions, strong)
        }

        /** 缓存每个原尺度相位的位置候选，后续搜索复用预检已完成的相关计算。 */
        fun searchHeaderPositions(scale: Double, phaseStep: Int, budget: SearchBudget,
                                  searchMs: Long, diagnostic: (String) -> Unit): List<Geometry> {
            val deadline = minOf(budget.deadline - 200_000_000,
                SystemClock.elapsedRealtimeNanos() + searchMs * 1_000_000)
            val strongest = ArrayList<Geometry>()
            fun keep(target: MutableList<Geometry>, candidate: Geometry, limit: Int) {
                if (candidate.score <= 0 || (target.size == limit && candidate.score <= target.last().score)) return
                target.add(candidate)
                target.sortByDescending { it.score }
                if (target.size > limit) target.removeAt(target.lastIndex)
            }
            var phases = 0
            var reused = 0
            phaseLoop@ for (py in 0 until CHIP step phaseStep) for (px in 0 until CHIP step phaseStep) {
                if (SystemClock.elapsedRealtimeNanos() >= deadline) break@phaseLoop
                budget.check()
                val key = py * CHIP + px
                val cached = if (scale == 1.0) originalPhaseCandidates[key] else null
                if (cached != null) {
                    reused++
                    cached.forEach { keep(strongest, it, 32) }
                    continue
                }
                val samples = sample(scale, scale, px.toDouble(), py.toDouble(), budget::check)
                val averages = DoubleArray(SLOTS) {
                    if (samples.weights[it] > 0) samples.sums[it] / samples.weights[it] else 0.0
                }
                val phaseBest = ArrayList<Geometry>()
                for (cy in 0 until SIDE) {
                    if (SystemClock.elapsedRealtimeNanos() >= deadline) {
                        phaseBest.forEach { keep(strongest, it, 32) }
                        break@phaseLoop
                    }
                    budget.check()
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
                        keep(phaseBest, Geometry(scale, (px + cx * CHIP).toDouble(),
                            (py + cy * CHIP).toDouble(), score), 8)
                    }
                }
                phases++
                if (scale == 1.0) originalPhaseCandidates[key] = phaseBest.toList()
                phaseBest.forEach { keep(strongest, it, 32) }
            }
            val selected = ArrayList<Geometry>()
            fun distance(a: Double, b: Double): Double {
                val d = abs(a - b) % WatermarkLocator.TILE
                return minOf(d, WatermarkLocator.TILE - d)
            }
            for (candidate in strongest) {
                if (selected.all { distance(it.x, candidate.x) > 4 || distance(it.y, candidate.y) > 4 }) {
                    selected.add(candidate)
                    if (selected.size == 4) break
                }
            }
            diagnostic("header_positions scale=$scale phaseStep=$phaseStep phases=$phases reused=$reused " +
                "positions=${selected.size} remainingMs=${budget.remainingMs()}")
            return selected
        }

        fun decodePositions(positions: List<Geometry>, adjustScale: Boolean, budget: SearchBudget,
                            timings: WatermarkTimings?, diagnostic: (String) -> Unit): WatermarkScaleSearch.Result? {
            var best: WatermarkScaleSearch.Result? = null
            for (position in positions.take(4)) {
                budget.check()
                val geometry = measured("header_position_refinement", diagnostic, timings) {
                    refineHeader(position, budget::check, adjustScale)
                }
                val detection = measured("body_decode", diagnostic, timings) {
                    inspect(geometry.scale, geometry.scale, geometry.x, geometry.y, budget::check)
                }
                diagnostic("position_result scale=${geometry.scale} x=${geometry.x} y=${geometry.y} " +
                    "score=${geometry.score} status=${detection.status}")
                val result = WatermarkScaleSearch.Result(detection, geometry.scale)
                if (detection.payload != null) return result
                if (best == null || better(detection, best.detection)) best = result
            }
            return best
        }

        fun locateAxes(checkCancelled: () -> Unit, diagnostic: (String) -> Unit): List<WatermarkLocator.Candidate> {
            axisCandidates?.let {
                diagnostic("axis_candidates source=cache count=${it.size}")
                return it
            }
            val projections = measured("angle_projection", diagnostic) { projections(checkCancelled) }
            return measured("angle_scale_position", diagnostic) {
                WatermarkLocator.locateJoint(projections.first, projections.second, checkCancelled, diagnostic)
            }.also { axisCandidates = it }
        }

        /** 固定方向优先直接读取，再在该方向下进行有界的尺度和裁剪位置搜索。 */
        fun detectFixedDirection(estimatedScales: List<Double>, budget: SearchBudget,
                                 timings: WatermarkTimings?, diagnostic: (String) -> Unit): WatermarkScaleSearch.Result? {
            budget.check()
            var best = WatermarkScaleSearch.Result(measured("body_decode", diagnostic, timings) {
                inspect(1.0, 1.0, 0.0, 0.0, budget::check)
            }, 1.0)
            if (best.detection.payload != null) return best
            val axis = locateAxes(budget::check, diagnostic)
            val scales = (listOf(1.0) + axis.map { it.x.scale } + estimatedScales)
                .filter { it in 0.45..1.10 }.fold(mutableListOf<Double>()) { result, scale ->
                    if (result.none { abs(it - scale) < 0.002 }) result.add(scale)
                    result
                }.take(3)
            for (scale in scales) {
                val seeds = axis.filter { abs(it.x.scale - scale) < 0.025 }.take(2).map {
                    Geometry(scale, it.x.offset, it.y.offset, 0.0)
                }
                val aligned = decodePositions(seeds, scale != 1.0, budget, timings, diagnostic)
                if (aligned != null && better(aligned.detection, best.detection)) best = aligned
                if (best.detection.payload != null) return best
                val positions = measured("fixed_angle_position_search", diagnostic, timings) {
                    searchHeaderPositions(scale, 2, budget, 500, diagnostic)
                }
                val searched = decodePositions(positions, scale != 1.0, budget, timings, diagnostic)
                if (searched != null && better(searched.detection, best.detection)) best = searched
                if (best.detection.payload != null) return best
            }
            return best
        }

        /** 频谱角度仅为初值：使用模板横纵分量相位确定位置，再以固定头部精化几何。 */
        fun detectEstimated(estimatedScale: Double, budget: SearchBudget, timings: WatermarkTimings?,
                            diagnostic: (String) -> Unit): WatermarkScaleSearch.Result? {
            val checkCancelled = budget::check
            val candidates = locateAxes(checkCancelled, diagnostic)
                .filter { abs(it.x.scale - estimatedScale) < 0.025 }.take(2)
            var best: WatermarkScaleSearch.Result? = null
            for (candidate in candidates) {
                var image = this
                var geometry = measured("angle_header_position", diagnostic) {
                    refineHeader(Geometry(candidate.x.scale, candidate.x.offset, candidate.y.offset, 0.0), checkCancelled)
                }
                measured("angle_header_refinement", diagnostic, timings) {
                    for (angleStep in doubleArrayOf(0.1, 0.025, 0.005)) {
                        val centerImage = image
                        val center = geometry
                        for (direction in intArrayOf(-1, 1)) {
                            checkCancelled()
                            val degrees = requireNotNull(centerImage.arbitraryDegrees) + direction * angleStep
                            val rotated = atAngle(degrees)
                            val alternative = if (horizontalMirrored) rotated.mirrored() else rotated
                            val seed = center.copy(
                                x = center.x + (centerImage.width - alternative.width) * center.scale / 2,
                                y = center.y + (centerImage.height - alternative.height) * center.scale / 2)
                            val refined = alternative.refineHeader(seed, checkCancelled)
                            if (refined.score > geometry.score) { image = alternative; geometry = refined }
                        }
                    }
                }
                val detection = measured("angle_body_decode", diagnostic, timings) {
                    image.inspect(geometry.scale, geometry.scale, geometry.x, geometry.y, checkCancelled)
                }
                diagnostic("angle_result degrees=${detection.rotationDegrees} scale=${geometry.scale} " +
                    "offsetX=${geometry.x} offsetY=${geometry.y} headerScore=${geometry.score} " +
                    "status=${detection.status} body=${detection.bodyBitsObserved}/${detection.bodyBitsRequired}")
                val result = WatermarkScaleSearch.Result(detection, geometry.scale)
                if (detection.payload != null) return result
                if (best == null || better(detection, best.detection)) best = result
            }
            // 相位只给初值；未认证时在当前角度下限时搜索头部位置，补救裁剪造成的相位误差。
            if (budget.remainingMs() >= 500) {
                val scale = candidates.firstOrNull()?.x?.scale ?: estimatedScale
                val positions = measured("angle_position_search", diagnostic, timings) {
                    searchHeaderPositions(scale, 2, budget, 650, diagnostic)
                }
                val fallback = decodePositions(positions, true, budget, timings, diagnostic)
                if (fallback != null && (best == null || better(fallback.detection, best.detection))) best = fallback
            }
            return best
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

        fun projections(checkCancelled: () -> Unit): Pair<DoubleArray, DoubleArray> {
            val x = DoubleArray(width)
            val y = DoubleArray(height)
            // 色差抵消灰度背景和文字；正文的零均值棋盘在投影时大体抵消。
            for (row in 0 until height) {
                if (row % 32 == 0) checkCancelled()
                for (column in 0 until width) {
                    if (arbitraryDegrees != null) {
                        val g = channel(column.toDouble(), row.toDouble(), 8)
                        if (!g.isFinite()) continue
                        x[column] += channel(column.toDouble(), row.toDouble(), 16) - g
                        y[row] += channel(column.toDouble(), row.toDouble(), 0) - g
                        continue
                    }
                    val pixel = pixelAt(column, row)
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
            if (arbitraryDegrees != null) return channel(x, y, 8)
            val ix = floor(x).toInt()
            val iy = floor(y).toInt()
            val fx = x - ix
            val fy = y - iy
            fun at(dx: Int, dy: Int): Double {
                return ((pixelAt(ix + dx, iy + dy) ushr 8) and 255).toDouble()
            }
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
                        if (!a.isFinite() || !b.isFinite() || !c.isFinite() || !d.isFinite()) continue
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
                payload, max(0.0, score), status, observedHeader, observed, required, FORMAT,
                horizontalMirrored, ((arbitraryDegrees ?: (quarterTurns * 90.0)) % 360 + 360) % 360)
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
