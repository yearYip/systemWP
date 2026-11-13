package com.demo.systemwp

import java.nio.charset.StandardCharsets

/**
 * 屏幕暗水印的明文载荷。
 *
 * 记录「谁 + 哪台设备 + 何时」；字段按 UTF-8 字节长度自适应裁剪，
 * 因此用户名/设备 ID 改动后水印内容随之变化，也不会因输入过长而失败。
 *
 * 线格式：[版本 1B][用户名长度 1B][用户名][设备 ID 长度 1B][设备 ID][时间戳 8B 大端]
 */
data class WatermarkPayload(
    val userId: String,
    val deviceId: String,
    val timestamp: Long
) {

    fun encode(): ByteArray {
        val user = truncate(userId)
        val device = truncate(deviceId)
        val out = ByteArray(2 + user.size + 1 + device.size + 8)
        var p = 0
        out[p++] = VERSION
        out[p++] = user.size.toByte()
        System.arraycopy(user, 0, out, p, user.size)
        p += user.size
        out[p++] = device.size.toByte()
        System.arraycopy(device, 0, out, p, device.size)
        p += device.size
        for (i in 7 downTo 0) {
            out[p++] = ((timestamp ushr (i * 8)) and 0xFF).toByte()
        }
        return out
    }

    companion object {
        const val VERSION: Byte = 2
        const val MAX_FIELD_BYTES = 32

        fun decode(bytes: ByteArray): WatermarkPayload? {
            if (bytes.size < 2) return null
            if (bytes[0] != VERSION) return null

            var p = 1
            val userLen = bytes[p++].toInt() and 0xFF
            if (userLen > MAX_FIELD_BYTES) return null
            if (p + userLen + 1 + 8 > bytes.size) return null
            val user = String(bytes, p, userLen, StandardCharsets.UTF_8)
            p += userLen

            val deviceLen = bytes[p++].toInt() and 0xFF
            if (deviceLen > MAX_FIELD_BYTES) return null
            if (p + deviceLen + 8 != bytes.size) return null
            val device = String(bytes, p, deviceLen, StandardCharsets.UTF_8)
            p += deviceLen

            var timestamp = 0L
            for (i in 0 until 8) {
                timestamp = (timestamp shl 8) or (bytes[p + i].toLong() and 0xFF)
            }
            if (timestamp <= 0L) return null

            return WatermarkPayload(user, device, timestamp)
        }

        /** 截断到 [MAX_FIELD_BYTES] 以内，且不切开多字节字符。 */
        private fun truncate(value: String): ByteArray {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size <= MAX_FIELD_BYTES) return bytes
            var end = MAX_FIELD_BYTES
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) {
                end--
            }
            return bytes.copyOf(end)
        }
    }
}
