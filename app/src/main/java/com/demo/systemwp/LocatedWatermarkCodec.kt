package com.demo.systemwp

import android.graphics.Bitmap
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
 * 旧版棋盘水印继续由 ScreenWatermarkCodec 解码。
 */
object LocatedWatermarkCodec {
    private const val SIDE = 56
    private const val CHIP = 12
    private const val SLOTS = SIDE * SIDE
    private const val HEADER_BITS = 32
    private const val HEADER_REPEAT = 16
    private const val HEADER_SLOTS = HEADER_BITS * HEADER_REPEAT
    private const val MAGIC = 0x5732 // W2，与旧格式 WD 区分
    private const val FORMAT = "定位版 W2"
    private const val MAX_SCALE_DIFFERENCE = 0.008
    private val nextRunId = AtomicLong()

    // 997 与 3136 互质：一一映射，连续位和重复副本分散到不同位置。
    private fun physical(logical: Int): Int = (logical * 997 + 313) % SLOTS

    fun buildTile(sealed: ByteArray): Bitmap? {
        if (sealed.isEmpty() || sealed.size > ScreenWatermarkCodec.MAX_BODY_BYTES) return null
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
    fun detectDirect(bitmap: Bitmap, checkCancelled: () -> Unit): ScreenWatermarkCodec.Detection =
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
        onProgress("正在搜索水印定位信号…")
        val projections = image.projections(checkCancelled)
        val horizontal = WatermarkLocator.locate(projections.first, checkCancelled) {
            diagnostic("axis=X $it")
        }
        val vertical = WatermarkLocator.locate(projections.second, checkCancelled) {
            diagnostic("axis=Y $it")
        }
        diagnostic("pairing candidatesX=${horizontal.size} candidatesY=${vertical.size} " +
            "maxRelativeDifference=$MAX_SCALE_DIFFERENCE")
        var best: WatermarkScaleSearch.Result? = null
        var acceptedPairs = 0
        var rejectedPairs = 0
        for ((xi, x) in horizontal.withIndex()) for ((yi, y) in vertical.withIndex()) {
            checkCancelled()
            // 允许导出尺寸取整带来的轻微横纵差异，拒绝明显不等比的伪候选。
            val difference = abs(x.scale - y.scale) / max(x.scale, y.scale)
            val pairDetails = "pairX=${xi + 1} pairY=${yi + 1} sx=${x.scale} sy=${y.scale} " +
                "scoreX=${x.score} scoreY=${y.score} relativeDifference=$difference"
            if (difference > MAX_SCALE_DIFFERENCE) {
                rejectedPairs++
                diagnostic("$pairDetails rejected reason=scale_mismatch threshold=$MAX_SCALE_DIFFERENCE")
                continue
            }
            acceptedPairs++
            diagnostic("$pairDetails accepted")
            onProgress("已找到候选定位信号，正在对齐并验证…")
            val scale = (x.scale + y.scale) / 2.0
            Log.i("SystemWP", "locator sx=${x.scale} sy=${y.scale} " +
                "x=${x.offset} y=${y.offset} scoreX=${x.score} scoreY=${y.score} run=$runId")
            val statuses = IntArray(ScreenWatermarkCodec.Status.entries.size)
            var pairBest: ScreenWatermarkCodec.Detection? = null
            // 双频给出亚像素坐标；邻域细化弥补背景、插值造成的相位偏差。
            for (delta in doubleArrayOf(0.0, -0.0002, 0.0002)) {
                for (dy in doubleArrayOf(0.0, -0.5, 0.5, -1.0, 1.0)) {
                    for (dx in doubleArrayOf(0.0, -0.5, 0.5, -1.0, 1.0)) {
                        checkCancelled()
                        val detection = image.inspect(x.scale + delta, y.scale + delta,
                            x.offset + dx, y.offset + dy, checkCancelled)
                        val result = WatermarkScaleSearch.Result(detection, scale + delta)
                        statuses[detection.status.ordinal]++
                        val previousPair = pairBest
                        if (previousPair == null || better(detection, previousPair)) pairBest = detection
                        val previous = best
                        if (previous == null || better(detection, previous.detection)) best = result
                        if (detection.payload != null) {
                            diagnostic("complete reason=success $pairDetails scaleDelta=$delta " +
                                "offsetX=${x.offset + dx} offsetY=${y.offset + dy} " +
                                "header=${detection.headerBitsObserved}/32 " +
                                "body=${detection.bodyBitsObserved}/${detection.bodyBitsRequired}")
                            return result
                        }
                    }
                }
            }
            diagnostic("$pairDetails decode_failed attempts=${statuses.sum()} " +
                "statuses=${ScreenWatermarkCodec.Status.entries.joinToString { "${it.name}:${statuses[it.ordinal]}" }} " +
                "bestStatus=${pairBest?.status} bestScore=${pairBest?.score} " +
                "header=${pairBest?.headerBitsObserved}/32 " +
                "body=${pairBest?.bodyBitsObserved}/${pairBest?.bodyBitsRequired}")
        }
        val reason = when {
            horizontal.isEmpty() || vertical.isEmpty() -> "missing_axis_candidates"
            acceptedPairs == 0 -> "no_compatible_scale_pair"
            else -> "all_decode_attempts_failed"
        }
        diagnostic("complete reason=$reason acceptedPairs=$acceptedPairs rejectedPairs=$rejectedPairs " +
            "bestStatus=${best?.detection?.status}")
        return best
    }

    internal fun better(a: ScreenWatermarkCodec.Detection, b: ScreenWatermarkCodec.Detection): Boolean =
        a.payload != null || a.status.ordinal > b.status.ordinal ||
            (a.status == b.status && a.score > b.score)

    private class Image(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }

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
                    checkCancelled: () -> Unit): ScreenWatermarkCodec.Detection {
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
            fun result(status: ScreenWatermarkCodec.Status, observed: Int = 0, required: Int = 0,
                       payload: WatermarkPayload? = null) = ScreenWatermarkCodec.Detection(
                payload, max(0.0, score), status, observedHeader, observed, required, FORMAT)
            val header = WatermarkBits.bitsToBytes(IntArray(HEADER_BITS) { if ((headerValues[it] ?: 0.0) > 0) 1 else 0 })
            if ((header[0].toInt() and 255) != (MAGIC ushr 8) ||
                (header[1].toInt() and 255) != (MAGIC and 255)) return result(ScreenWatermarkCodec.Status.NOT_FOUND)
            if (observedHeader < HEADER_BITS) return result(ScreenWatermarkCodec.Status.INSUFFICIENT_DATA)
            val length = ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
            if (length !in 1..ScreenWatermarkCodec.MAX_BODY_BYTES) return result(ScreenWatermarkCodec.Status.NOT_FOUND)
            val bits = length * 16
            val repeats = (SLOTS - HEADER_SLOTS) / bits
            if (repeats < 1) return result(ScreenWatermarkCodec.Status.NOT_FOUND)
            val values = Array(bits) { read(HEADER_SLOTS, it, bits, repeats) }
            val observed = values.count { it != null }
            val encoded = Array<Int?>(bits) { values[it]?.let { value -> if (value > 0) 1 else 0 } }
            val sealed = WatermarkErrorCorrection.decode(encoded) ?: return result(
                if (observed < bits) ScreenWatermarkCodec.Status.INSUFFICIENT_DATA else ScreenWatermarkCodec.Status.VALIDATION_FAILED,
                observed, bits)
            val payload = WatermarkCrypto.open(sealed)?.let { WatermarkPayload.decode(it) }
            return result(if (payload == null) ScreenWatermarkCodec.Status.VALIDATION_FAILED else ScreenWatermarkCodec.Status.SUCCESS,
                observed, bits, payload)
        }
    }
}
