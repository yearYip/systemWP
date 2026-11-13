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
 * 纹理由一块 tile 平铺得到：56×56 个 chip，其中前 512 个 chip 承载 32 比特头部
 * （每比特 16 次重复），剩余 chip 全部平均分给正文比特做重复（载荷越短，重复次数越多）；
 * 整屏铺满后同一槽位会被观测数次到数十次，靠累加取信。
 *
 * 支持保留足够头部与正文的裁剪图。缩放、JPEG 二次压缩、
 * 翻拍可能削弱或破坏 4 像素级棋盘，不能保证恢复。
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

    /** 过滤无法可靠定位的过小输入。 */
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

    /** 只有通过解密认证的结果才返回身份；其余状态仅供诊断。 */
    enum class Status { SUCCESS, NOT_FOUND, INSUFFICIENT_DATA, VALIDATION_FAILED }

    data class Detection(
        val payload: WatermarkPayload?,
        val score: Double,
        val status: Status = if (payload != null) Status.SUCCESS else Status.NOT_FOUND,
        val headerBitsObserved: Int = 0,
        val bodyBitsObserved: Int = 0,
        val bodyBitsRequired: Int = 0
    )

    /** 由截图还原载荷；无有效水印时返回 null。 */
    fun extract(bitmap: Bitmap): WatermarkPayload? = detect(bitmap).payload

    /** 与 [extract] 相同，但额外返回相关强度，便于区分“没画上/被压缩抹掉”与“画上了但解不出”。 */
    fun detect(bitmap: Bitmap): Detection {
        if (bitmap.width < MIN_DETECT_SIDE || bitmap.height < MIN_DETECT_SIDE) {
            return Detection(null, 0.0)
        }

        // 常见的未裁剪原图先走快速路径。
        val original = accumulate(bitmap, 0, 0, bitmap.height, 1)
        val direct = inspect(original)
        if (direct.payload != null) return direct

        // 像素相位只需累加一次；整 chip 的平移通过重排槽位搜索，
        // 避免对 672×672 个偏移反复扫描整张图片。
        data class Candidate(val x: Int, val y: Int, val score: Double)
        val coarse = ArrayList<Candidate>()
        for (py in 0 until CHIP_SIZE) {
            for (px in 0 until CHIP_SIZE) {
                val acc = accumulate(bitmap, px, py, bitmap.height, 2)
                for (cy in 0 until TILE_CHIPS) {
                    for (cx in 0 until TILE_CHIPS) {
                        val score = syncScore(acc, cx, cy)
                        if (score > 0.0) coarse.add(
                            Candidate(px + cx * CHIP_SIZE, py + cy * CHIP_SIZE, score)
                        )
                    }
                }
                // 每个相位后裁掉弱候选，限制内存与后续精定位开销。
                coarse.sortByDescending { it.score }
                if (coarse.size > 24) coarse.subList(24, coarse.size).clear()
            }
        }

        val offsets = coarse.map { it.x to it.y }
        var best = direct
        // 按像素相位分组，精定位也只为每个相位扫描一次图片。
        for ((phase, positions) in offsets.groupBy { (x, y) ->
            (x % CHIP_SIZE) to (y % CHIP_SIZE)
        }) {
            val acc = if (phase.first == 0 && phase.second == 0) original
                else accumulate(bitmap, phase.first, phase.second, bitmap.height, 1)
            for ((x, y) in positions) {
                val shifted = translate(acc, x / CHIP_SIZE, y / CHIP_SIZE)
                val result = inspect(shifted)
                if (result.payload != null) return result
                // 完整、合法的头部比单纯相关分数更有诊断价值。
                if (result.status.ordinal > best.status.ordinal ||
                    (result.status == best.status && result.score > best.score)) best = result
            }
        }
        return best
    }

    private val magicBits = WatermarkBits.bytesToBits(byteArrayOf(MAGIC_0, MAGIC_1))
    private val pn = IntArray(TILE_SLOTS) { WatermarkBits.pnSign(it) }

    private fun sourceSlot(slot: Int, cx: Int, cy: Int): Int =
        Math.floorMod(slot / TILE_CHIPS - cy, TILE_CHIPS) * TILE_CHIPS +
            Math.floorMod(slot % TILE_CHIPS - cx, TILE_CHIPS)

    /** 已知 magic 的有符号相关；覆盖过少的候选不能参加定位。 */
    private fun syncScore(acc: Accumulator, cx: Int = 0, cy: Int = 0): Double {
        var sum = 0.0
        var count = 0
        var bits = 0
        for (g in magicBits.indices) {
            var observed = false
            val sign = if (magicBits[g] == 1) 1 else -1
            for (r in 0 until HEADER_REPEAT) {
                val slot = g * HEADER_REPEAT + r
                val source = sourceSlot(slot, cx, cy)
                if (acc.weight[source] > 0.0) {
                    sum += sign * pn[slot] * acc.sum[source] / acc.weight[source]
                    count++
                    observed = true
                }
            }
            if (observed) bits++
        }
        return if (bits < 12 || count < 64) 0.0
            else sum / count * kotlin.math.sqrt(count.toDouble() / (16 * HEADER_REPEAT))
    }

    private fun translate(acc: Accumulator, cx: Int, cy: Int): Accumulator {
        val result = Accumulator()
        for (slot in 0 until TILE_SLOTS) {
            val source = sourceSlot(slot, cx, cy)
            result.sum[slot] = acc.sum[source]
            result.weight[slot] = acc.weight[source]
        }
        return result
    }

    private fun observedBits(acc: Accumulator, first: Int, bits: Int, repeat: Int): Int =
        (0 until bits).count { bit ->
            (0 until repeat).any { acc.weight[first + bit * repeat + it] > 0.0 }
        }

    private fun inspect(acc: Accumulator): Detection {
        val score = syncScore(acc)
        val headerObserved = observedBits(acc, 0, HEADER_BITS, HEADER_REPEAT)
        val header = WatermarkBits.bitsToBytes(
            IntArray(HEADER_BITS) { readBit(acc, it * HEADER_REPEAT, HEADER_REPEAT) }
        )
        val magicObserved = observedBits(acc, 0, 16, HEADER_REPEAT)
        val magicMatches = magicObserved == 16 && header[0] == MAGIC_0 && header[1] == MAGIC_1
        if (!magicMatches) return Detection(null, score, headerBitsObserved = headerObserved)
        if (headerObserved < HEADER_BITS) return Detection(
            null, score, Status.INSUFFICIENT_DATA, headerObserved
        )
        val length = ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
        if (length !in 1..MAX_BODY_BYTES) return Detection(
            null, score, headerBitsObserved = headerObserved
        )
        val bits = length * 8
        val repeat = bodyRepeat(bits)
        val observed = observedBits(acc, HEADER_SLOTS, bits, repeat)
        if (observed < bits) return Detection(
            null, score, Status.INSUFFICIENT_DATA, headerObserved, observed, bits
        )
        val payload = decodeFrom(acc)
        return Detection(payload, score,
            if (payload != null) Status.SUCCESS else Status.VALIDATION_FAILED,
            headerObserved, observed, bits)
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

        // 完整 4×4 棋盘块作差；统一权重保证背景均值抵消，丢弃不完整边缘块。
        val period = SUB_BLOCK * 2
        val startX = Math.floorMod(-shiftX, period)
        val startY = Math.floorMod(-shiftY, period)
        val blockRows = IntArray(width * period)
        for (y in startY..(limit - period) step (period * step)) {
            bitmap.getPixels(blockRows, 0, width, 0, y, width, period)
            val chipRow = ((y + shiftY) / CHIP_SIZE % TILE_CHIPS) * TILE_CHIPS
            for (x in startX..(width - period) step (period * step)) {
                var contrast = 0.0
                var gradient = 0
                for (dy in 0 until period) for (dx in 0 until period) {
                    val index = dy * width + x + dx
                    val value = (blockRows[index] ushr 8) and 255
                    val sign = if (((dx / SUB_BLOCK + dy / SUB_BLOCK) and 1) == 0) 1 else -1
                    contrast += value * sign
                    if (dx + 1 < period) gradient += abs(value - ((blockRows[index + 1] ushr 8) and 255))
                    if (dy + 1 < period) gradient += abs(value - ((blockRows[index + width] ushr 8) and 255))
                }
                val normalized = gradient / (24.0 * GRADIENT_SCALE)
                val weight = 1.0 / (1.0 + normalized * normalized)
                val slot = chipRow + (x + shiftX) / CHIP_SIZE % TILE_CHIPS
                acc.sum[slot] += contrast / (period * period) * weight
                acc.weight[slot] += weight
            }
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
