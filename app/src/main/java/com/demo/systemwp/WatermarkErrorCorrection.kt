package com.demo.systemwp

/** 扩展 Hamming(8,4)：每组可纠正 1 位错误，或恢复最多 3 位擦除。
 * 超出唯一解码范围时拒绝猜测；最后仍必须通过 AES-GCM 认证。
 */
internal object WatermarkErrorCorrection {
    private val words = Array(16) { value ->
        val b = IntArray(8)
        b[2] = (value ushr 3) and 1
        b[4] = (value ushr 2) and 1
        b[5] = (value ushr 1) and 1
        b[6] = value and 1
        b[0] = b[2] xor b[4] xor b[6]
        b[1] = b[2] xor b[5] xor b[6]
        b[3] = b[4] xor b[5] xor b[6]
        b[7] = b.take(7).fold(0) { a, bit -> a xor bit }
        b
    }

    fun encode(bytes: ByteArray): IntArray = IntArray(bytes.size * 16) { index ->
        val value = bytes[index / 16].toInt() and 255
        words[if (index % 16 < 8) value ushr 4 else value and 15][index % 8]
    }

    /** null 表示该编码位在裁剪图中没有观测。 */
    fun decode(bits: Array<Int?>): ByteArray? {
        if (bits.size % 16 != 0) return null
        val nibbles = IntArray(bits.size / 8)
        for (group in nibbles.indices) {
            val erased = (0..7).count { bits[group * 8 + it] == null }
            var best = -1
            var distance = 9
            var tied = false
            for (value in words.indices) {
                val errors = (0..7).count {
                    val bit = bits[group * 8 + it]
                    bit != null && bit != words[value][it]
                }
                if (errors < distance) {
                    best = value
                    distance = errors
                    tied = false
                } else if (errors == distance) tied = true
            }
            if (tied || 2 * distance + erased >= 4) return null
            nibbles[group] = best
        }
        return ByteArray(nibbles.size / 2) {
            ((nibbles[it * 2] shl 4) or nibbles[it * 2 + 1]).toByte()
        }
    }
}
