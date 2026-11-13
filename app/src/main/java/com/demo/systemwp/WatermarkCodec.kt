package com.demo.systemwp

import android.graphics.Bitmap
import java.nio.charset.StandardCharsets

/**
 * 盲水印编解码器：把载荷写入像素的最低有效位（LSB）。
 *
 * 设计要点：
 * 1. 每个比特写入 [REPEAT] 个不同位置，提取时多数表决 —— 抗轻微噪点与重编码。
 * 2. 位置由「与通道总数互质的步长」遍历全部通道生成，等价于一次全图洗牌，
 *    不需要额外的置换表，内存占用为 O(1)，且水印均匀铺满整幅图而不是挤在一条横带里。
 * 3. 包 = 魔数(4) + 长度(2) + CRC16(2) + 正文，CRC 用于拒绝误判。
 * 4. LSB 修改幅度为 1/255，肉眼绝对不可见；但对 JPEG 有损压缩 / 缩放不鲁棒，
 *    因此落盘必须使用 PNG，且提取要求图片尺寸未被改变。
 */
object WatermarkCodec {

    private val MAGIC = "SWP1".toByteArray(StandardCharsets.US_ASCII)
    private const val LENGTH_BYTES = 2
    private const val CRC_BYTES = 2
    private const val HEADER_BYTES = 4 + LENGTH_BYTES + CRC_BYTES

    /** 每个比特重复写入的次数。 */
    private const val REPEAT = 3

    /** 隐蔽步长 / 起始偏移的种子，编解码必须一致。 */
    private const val SEED = 0x5A17C0DEL

    /** 单次可嵌入的最大正文字节数。 */
    const val MAX_PAYLOAD = 4096

    /**
     * 在 [source] 的副本上写入水印，返回新图（原图不被修改，alpha 通道保持不变）。
     *
     * @throws IllegalArgumentException 图片容量不足或载荷过大。
     */
    fun embed(source: Bitmap, payload: String): Bitmap {
        val body = payload.toByteArray(StandardCharsets.UTF_8)
        require(body.size <= MAX_PAYLOAD) { "载荷过长（上限 $MAX_PAYLOAD 字节）" }

        val packet = buildPacket(body)
        val bits = packet.size * 8
        val width = source.width
        val height = source.height
        val channels = width * height * 3
        require(bits.toLong() * REPEAT <= channels) { "图片太小，无法容纳水印" }

        val marked = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalArgumentException("图片无法复制为可写位图")

        val pixels = IntArray(width * height)
        marked.getPixels(pixels, 0, width, 0, 0, width, height)

        val stride = coprimeStride(channels)
        val offset = startOffset(channels)
        for (i in 0 until bits) {
            val bit = (packet[i ushr 3].toInt() ushr (7 - (i and 7))) and 1
            for (r in 0 until REPEAT) {
                writeBit(pixels, position(offset, stride, i * REPEAT + r, channels), bit)
            }
        }

        marked.setPixels(pixels, 0, width, 0, 0, width, height)
        return marked
    }

    /**
     * 从图片中提取水印正文；校验失败（无魔数或 CRC 不符）返回 null。
     */
    fun extract(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        val channels = width * height * 3
        if (channels < HEADER_BYTES * 8 * REPEAT) return null

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val header = readBits(pixels, channels, HEADER_BYTES * 8)
        for (i in MAGIC.indices) {
            if (header[i] != MAGIC[i]) return null
        }
        val length = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
        if (length <= 0 || length > MAX_PAYLOAD) return null
        val expectedCrc =
            ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)

        val totalBytes = HEADER_BYTES + length
        if (totalBytes.toLong() * 8 * REPEAT > channels) return null

        val packet = readBits(pixels, channels, totalBytes * 8)
        if (crc16(packet, HEADER_BYTES, length) != expectedCrc) return null

        return String(packet, HEADER_BYTES, length, StandardCharsets.UTF_8)
    }

    // ---------------------------------------------------------------- 内部实现

    private fun buildPacket(body: ByteArray): ByteArray {
        val packet = ByteArray(HEADER_BYTES + body.size)
        System.arraycopy(MAGIC, 0, packet, 0, MAGIC.size)
        packet[4] = ((body.size ushr 8) and 0xFF).toByte()
        packet[5] = (body.size and 0xFF).toByte()
        val crc = crc16(body, 0, body.size)
        packet[6] = ((crc ushr 8) and 0xFF).toByte()
        packet[7] = (crc and 0xFF).toByte()
        System.arraycopy(body, 0, packet, HEADER_BYTES, body.size)
        return packet
    }

    /** 按“多数表决”读出 [bitCount] 个比特。 */
    private fun readBits(pixels: IntArray, channels: Int, bitCount: Int): ByteArray {
        val out = ByteArray((bitCount + 7) / 8)
        val stride = coprimeStride(channels)
        val offset = startOffset(channels)
        for (i in 0 until bitCount) {
            var ones = 0
            for (r in 0 until REPEAT) {
                ones += readBit(pixels, position(offset, stride, i * REPEAT + r, channels))
            }
            if (ones * 2 > REPEAT) {
                out[i ushr 3] = (out[i ushr 3].toInt() or (1 shl (7 - (i and 7)))).toByte()
            }
        }
        return out
    }

    private fun position(offset: Int, stride: Int, slot: Int, channels: Int): Int =
        ((offset.toLong() + slot.toLong() * stride) % channels).toInt()

    private fun startOffset(channels: Int): Int = ((SEED * 31L) % channels).toInt()

    /** 取一个与 [total] 互质的步长，保证 0..total-1 被遍历一遍。 */
    private fun coprimeStride(total: Int): Int {
        var stride = (SEED % total).toInt().coerceAtLeast(1)
        while (gcd(stride, total) != 1) {
            stride++
            if (stride >= total) stride = 1
        }
        return stride
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val t = x % y
            x = y
            y = t
        }
        return x
    }

    private fun writeBit(pixels: IntArray, index: Int, bit: Int) {
        val pixel = index / 3
        val shift = 16 - (index % 3) * 8
        val value = (pixels[pixel] ushr shift) and 0xFF
        val next = (value and 0xFE) or bit
        pixels[pixel] = (pixels[pixel] and (0xFF shl shift).inv()) or (next shl shift)
    }

    private fun readBit(pixels: IntArray, index: Int): Int {
        val pixel = index / 3
        val shift = 16 - (index % 3) * 8
        return (pixels[pixel] ushr shift) and 1
    }

    private fun crc16(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }
}
