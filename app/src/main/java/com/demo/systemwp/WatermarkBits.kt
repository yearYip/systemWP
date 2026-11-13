package com.demo.systemwp

/**
 * 比特打包与伪随机符号工具，屏幕暗水印的编解码共用。
 *
 * 编码端与解码端必须得到完全一致的 ±1 序列，因此这里只用
 * 「位序固定为 MSB 优先」与「按槽位下标确定性的 SplitMix64」两条约定。
 */
internal object WatermarkBits {

    fun bytesToBits(bytes: ByteArray): IntArray {
        val bits = IntArray(bytes.size * 8)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 0xFF
            for (b in 0 until 8) {
                bits[i * 8 + b] = (value ushr (7 - b)) and 1
            }
        }
        return bits
    }

    fun bitsToBytes(bits: IntArray): ByteArray {
        val out = ByteArray((bits.size + 7) / 8)
        for (i in bits.indices) {
            if (bits[i] != 0) {
                out[i ushr 3] = (out[i ushr 3].toInt() or (1 shl (7 - (i and 7)))).toByte()
            }
        }
        return out
    }

    /** 由槽位下标确定性地取 ±1，用于打散比特极性。 */
    fun pnSign(slot: Int): Int {
        var z = slot.toLong() * GOLDEN + GOLDEN
        z = (z xor (z ushr 30)) * MIX_A
        z = (z xor (z ushr 27)) * MIX_B
        return if (((z xor (z ushr 31)) and 1L) == 0L) -1 else 1
    }

    private const val GOLDEN = -7046029254386353131L
    private const val MIX_A = -4658895280553007687L
    private const val MIX_B = -7723592293110705685L
}
