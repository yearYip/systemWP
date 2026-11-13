package com.demo.systemwp

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import kotlin.math.abs

/**
 * 屏幕暗水印编解码器。
 *
 * 为什么不是 LSB：悬浮窗只能做 alpha 混合，无法改写底层像素，
 * 所以 [WatermarkCodec] 那套 LSB 手段在屏幕上无从下手。
 * 这里改用「棋盘调制」：每个 4×4 小块内亮/暗各半，局部均值为零，
 * 人眼看到的是不可见的均匀噪声，而截图像素里留下了可用相关法检测的痕迹。
 *
 * 纹理由一块 tile 平铺得到：64×64 个 chip，其中前 512 个 chip 承载 32 比特头部
 * （每比特 16 次重复），剩余 chip 全部平均分给正文比特做重复（载荷越短，重复次数越多）；
 * 整屏铺满后同一槽位会被观测数次到数十次，靠累加取信。
 *
 * 限制：只对「原生分辨率、无损格式」的截屏有效。缩放、JPEG 二次压缩、
 * 翻拍都会破坏 4 像素级的棋盘，属物理上限而非实现问题。
 */
object ScreenWatermarkCodec {

    /**
     * 数据单元边长（逻辑像素）。
     *
     * 越大越抗重采样（改过显示尺寸或模拟器按比例缩放时，截图会被整体放大/缩小），
     * 但单元越大越容易看出块状纹理；12 在 0.8× 缩放后仍剩 9.6 像素，足够稳定取样。
     */
    const val CHIP_SIZE = 12

    /**
     * 单元内部棋盘粒度：2 表示按 2×2 像素交替，空间周期 4 像素。
     * 原来的逐像素交替是 2 像素周期（Nyquist），任何重采样都会把它平均成灰。
     */
    private const val SUB_BLOCK = 2

    /** 相位搜索用的行数与采样步长（只用于粗定位，精度足够且快）。 */
    private const val SEARCH_ROWS = 192
    private const val SEARCH_STEP = 2

    /** tile 内的 chip 数（每边）。tile 必须小于屏幕，否则边缘槽位永远采不到样本。 */
    private const val TILE_CHIPS = 56

    private const val TILE_PIXELS = CHIP_SIZE * TILE_CHIPS
    private const val TILE_SLOTS = TILE_CHIPS * TILE_CHIPS

    /**
     * 悬浮层的 paint alpha（0..255），直接决定调制幅度：
     * 黑/白各半，逐像素差值约为 [OVERLAY_ALPHA] 个灰阶。
     *
     * 2 ≈ 0.8%：极限隐形，但复杂界面上信号很容易被画面细节淹没；
     * 6 ≈ 2.4%：4 像素棋盘的高频噪声，正常观看距离下仍不可见，抗噪裕度大得多；
     * 10 以上开始能在纯色背景上看出极淡纹理。
     */
    const val OVERLAY_ALPHA = 6

    const val DEBUG_OVERLAY_ALPHA = 40

    /** 梯度权重曲线：边缘/文字越强，权重越低，避免把纹理当信号。 */
    private const val GRADIENT_SCALE = 24.0

    private const val HEADER_BYTES = 4
    private const val HEADER_BITS = HEADER_BYTES * 8
    private const val HEADER_REPEAT = 16
    private const val HEADER_SLOTS = HEADER_BITS * HEADER_REPEAT

    /** 正文比特的最小重复次数；实际次数按 tile 剩余容量自动放大。 */
    private const val MIN_BODY_REPEAT = 2

    /** 密文包长度上限，保证 [HEADER_SLOTS] + 正文槽位不超过 tile 容量。 */
    const val MAX_BODY_BYTES = 128

    private const val MAGIC_0: Byte = 0x57 // 'W'
    private const val MAGIC_1: Byte = 0x44 // 'D'

    private const val BAND_ROWS = 128

    /** 低于该尺寸的图不可能包含完整相位信息。 */
    private const val MIN_DETECT_SIDE = 64

    /** 把密文包装进 tile；容量不足或载荷非法时返回 null。 */
    fun buildTile(sealed: ByteArray): Bitmap? {
        if (sealed.isEmpty() || sealed.size > MAX_BODY_BYTES) return null

        val header = ByteArray(HEADER_BYTES)
        header[0] = MAGIC_0
        header[1] = MAGIC_1
        header[2] = ((sealed.size ushr 8) and 0xFF).toByte()
        header[3] = (sealed.size and 0xFF).toByte()

        val headerBits = WatermarkBits.bytesToBits(header)
        val bodyBits = WatermarkBits.bytesToBits(sealed)
        val repeat = bodyRepeat(bodyBits.size)
        if (requiredSlots(bodyBits.size, repeat) > TILE_SLOTS) return null

        // 未被数据占用的槽位也填随机符号，避免纹理出现可辨认的整块异常
        val signs = IntArray(TILE_SLOTS) { WatermarkBits.pnSign(it) }
        for (g in headerBits.indices) {
            val symbol = if (headerBits[g] == 1) 1 else -1
            for (r in 0 until HEADER_REPEAT) {
                val slot = g * HEADER_REPEAT + r
                signs[slot] = WatermarkBits.pnSign(slot) * symbol
            }
        }
        for (j in bodyBits.indices) {
            val symbol = if (bodyBits[j] == 1) 1 else -1
            for (r in 0 until repeat) {
                val slot = HEADER_SLOTS + j * repeat + r
                signs[slot] = WatermarkBits.pnSign(slot) * symbol
            }
        }

        val pixels = IntArray(TILE_PIXELS * TILE_PIXELS)
        var p = 0
        for (y in 0 until TILE_PIXELS) {
            val chipRow = (y / CHIP_SIZE) * TILE_CHIPS
            for (x in 0 until TILE_PIXELS) {
                val chip = chipRow + x / CHIP_SIZE
                val check = if ((((x / SUB_BLOCK) + (y / SUB_BLOCK)) and 1) == 0) 1 else -1
                pixels[p++] = if (check * signs[chip] > 0) WHITE else BLACK
            }
        }
        return Bitmap.createBitmap(pixels, TILE_PIXELS, TILE_PIXELS, Bitmap.Config.ARGB_8888)
    }

    /** 生成铺满整屏的平铺 shader；失败返回 null（此时悬浮层什么都不画）。 */
    fun createShader(tile: Bitmap): BitmapShader =
        BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)

    /** 检测结果：命中时 [payload] 非空；[score] 恒为头部相关强度，可用于判断“有没有信号”。 */
    data class Detection(val payload: WatermarkPayload?, val score: Double)

    /** 由截图还原载荷；无有效水印时返回 null。 */
    fun extract(bitmap: Bitmap): WatermarkPayload? = detect(bitmap).payload

    /** 与 [extract] 相同，但额外返回相关强度，便于区分“没画上/被压缩抹掉”与“画上了但解不出”。 */
    fun detect(bitmap: Bitmap): Detection {
        if (bitmap.width < MIN_DETECT_SIDE || bitmap.height < MIN_DETECT_SIDE) {
            return Detection(null, 0.0)
        }

        // 先枚举棋盘相位（chip 网格 + 子块网格），取头部相关最强的那一个
        var bestShiftX = 0
        var bestShiftY = 0
        var bestScore = Double.NEGATIVE_INFINITY
        val bandRows = minOf(bitmap.height, SEARCH_ROWS)
        for (shiftY in 0 until CHIP_SIZE) {
            for (shiftX in 0 until CHIP_SIZE) {
                val score = headerScore(
                    accumulate(bitmap, shiftX, shiftY, bandRows, SEARCH_STEP)
                )
                if (score > bestScore) {
                    bestScore = score
                    bestShiftX = shiftX
                    bestShiftY = shiftY
                }
            }
        }

        // 再整屏累加一次，利用 tile 的重复获得几十次观测
        val acc = accumulate(bitmap, bestShiftX, bestShiftY, bitmap.height, 1)
        val score = headerScore(acc)
        val payload = decodeFrom(acc)
        return Detection(payload, score)
    }

    private fun decodeFrom(acc: Accumulator): WatermarkPayload? {
        val header = WatermarkBits.bitsToBytes(
            IntArray(HEADER_BITS) { readBit(acc, it * HEADER_REPEAT, HEADER_REPEAT) }
        )
        if (header[0] != MAGIC_0 || header[1] != MAGIC_1) return null

        val bodyLen = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        if (bodyLen <= 0 || bodyLen > MAX_BODY_BYTES) return null

        val bodyBitCount = bodyLen * 8
        val repeat = bodyRepeat(bodyBitCount)
        if (requiredSlots(bodyBitCount, repeat) > TILE_SLOTS) return null

        val bodyBits = IntArray(bodyBitCount) {
            readBit(acc, HEADER_SLOTS + it * repeat, repeat)
        }
        val sealed = WatermarkBits.bitsToBytes(bodyBits)
        val plain = WatermarkCrypto.open(sealed) ?: return null
        return WatermarkPayload.decode(plain)
    }

    // ---------------------------------------------------------------- 内部实现

    private class Accumulator {
        val sum = DoubleArray(TILE_SLOTS)
        val weight = DoubleArray(TILE_SLOTS)
    }

    /**
     * 按 chip 累加「亮度 × 棋盘符号」，并按局部梯度加权。
     *
     * 棋盘符号在偶数边长小块内和为零，平滑背景与线性渐变会被抵消，
     * 留下的是我们画上去的 ±幅度；而文字边缘、照片细节同样是高频，
     * 会用「相邻像素差越大权重越低」的方式压掉，避免噪声淹没 ±几个灰阶的信号。
     */
    private fun accumulate(
        bitmap: Bitmap,
        shiftX: Int,
        shiftY: Int,
        rows: Int,
        step: Int
    ): Accumulator {
        val acc = Accumulator()
        val width = bitmap.width
        val limit = minOf(rows, bitmap.height)
        if (limit <= 0 || width < 2) return acc

        val bandRows = minOf(limit, BAND_ROWS)
        val band = IntArray(width * bandRows)
        var y0 = 0
        while (y0 < limit) {
            val height = minOf(bandRows, limit - y0)
            bitmap.getPixels(band, 0, width, 0, y0, width, height)
            for (row in 0 until height step step) {
                val y = y0 + row
                val chipRow = (((y + shiftY) / CHIP_SIZE) % TILE_CHIPS) * TILE_CHIPS
                val subRow = (y + shiftY) / SUB_BLOCK
                val base = row * width
                val nextBase = if (row + 1 < height) base + width else -1
                for (x in 0 until width step step) {
                    val slot = chipRow + ((x + shiftX) / CHIP_SIZE) % TILE_CHIPS
                    val luminance = (band[base + x] ushr 8) and 0xFF
                    val sub = (x + shiftX) / SUB_BLOCK + subRow
                    val check = if ((sub and 1) == 0) 1 else -1

                    var gradient = 0
                    if (x + 1 < width) {
                        gradient += abs(luminance - ((band[base + x + 1] ushr 8) and 0xFF))
                    }
                    if (nextBase >= 0) {
                        gradient += abs(luminance - ((band[nextBase + x] ushr 8) and 0xFF))
                    }
                    val normalized = gradient / GRADIENT_SCALE
                    val weight = 1.0 / (1.0 + normalized * normalized)

                    acc.sum[slot] += luminance.toDouble() * check * weight
                    acc.weight[slot] += weight
                }
            }
            y0 += height
        }
        return acc
    }

    private fun readBit(acc: Accumulator, firstSlot: Int, repeats: Int): Int {
        var sum = 0.0
        for (r in 0 until repeats) {
            val slot = firstSlot + r
            val weight = acc.weight[slot]
            if (weight > 0.0) {
                sum += WatermarkBits.pnSign(slot) * (acc.sum[slot] / weight)
            }
        }
        return if (sum > 0) 1 else 0
    }

    /**
     * 头部相关强度，用来挑选棋盘相位、也作为失败时的诊断值。
     * 有信号时约等于调制幅度（灰度级数），无信号时在 0 附近。
     */
    private fun headerScore(acc: Accumulator): Double {
        var score = 0.0
        for (g in 0 until HEADER_BITS) {
            var sum = 0.0
            for (r in 0 until HEADER_REPEAT) {
                val slot = g * HEADER_REPEAT + r
                val weight = acc.weight[slot]
                if (weight > 0.0) {
                    sum += WatermarkBits.pnSign(slot) * (acc.sum[slot] / weight)
                }
            }
            score += abs(sum)
        }
        return score / (HEADER_BITS * HEADER_REPEAT)
    }

    /**
     * 正文比特重复次数：把 tile 剩余槽位尽量摊给每个比特。
     * 载荷越短、重复越多，单比特观测样本越多，抗噪裕度越高。
     */
    private fun bodyRepeat(bodyBits: Int): Int {
        if (bodyBits <= 0) return MIN_BODY_REPEAT
        return maxOf(MIN_BODY_REPEAT, (TILE_SLOTS - HEADER_SLOTS) / bodyBits)
    }

    private fun requiredSlots(bodyBits: Int, repeat: Int): Int =
        HEADER_SLOTS + bodyBits * repeat

    /** 0xFFFFFFFF：不透明白。 */
    private const val WHITE = -1

    /** 0xFF000000：不透明黑。 */
    private const val BLACK = -16777216
}
