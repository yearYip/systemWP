package com.demo.systemwp

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** W2 正文不变；定位模板使用四个方向、八个频率分量，所有波矢均整周期平铺。 */
internal object WatermarkSpectralTemplate {
    data class Tone(val x: Int, val y: Int, val phase: Double, val amplitude: Double) {
        val frequency: Double = hypot(x.toDouble(), y.toDouble()) / WatermarkLocator.TILE
        val angle: Double = atan2(y.toDouble(), x.toDouble())
    }

    // 横纵分量使用零相位以供投影/相位定位，斜向分量用于多方向频谱匹配。
    // 每通道四个分量各 30，RMS 为 sqrt(1800) ≈ 42.43；峰值绝对值不超过 120。
    val red = listOf(Tone(16, 0, 0.0, 30.0), Tone(21, 0, 0.0, 30.0),
        Tone(9, 12, 0.85, 30.0), Tone(12, 16, -1.30, 30.0))
    val blue = listOf(Tone(0, 16, 0.0, 30.0), Tone(0, 21, 0.0, 30.0),
        Tone(-12, 9, 1.90, 30.0), Tone(-16, 12, -0.40, 30.0))

    private class Wave(val tone: Tone) {
        val samples = DoubleArray(WatermarkLocator.TILE) {
            tone.amplitude * sin(2 * PI * (it + (tone.x + tone.y) / 2.0) / WatermarkLocator.TILE + tone.phase)
        }
        fun at(x: Int, y: Int): Double = samples[Math.floorMod(tone.x * x + tone.y * y, WatermarkLocator.TILE)]
    }

    private val redWaves = red.map { Wave(it) }
    private val blueWaves = blue.map { Wave(it) }

    fun redPixel(x: Int, y: Int): Int = (128 + redWaves.sumOf { it.at(x, y) }).roundToInt()
    fun bluePixel(x: Int, y: Int): Int = (128 + blueWaves.sumOf { it.at(x, y) }).roundToInt()

    /** 连续坐标版本用于合成检查；生成 Bitmap 时使用上面的周期查表。 */
    fun signal(x: Double, y: Double, redChannel: Boolean): Double =
        (if (redChannel) red else blue).sumOf {
            it.amplitude * sin(2 * PI * (it.x * x + it.y * y) / WatermarkLocator.TILE + it.phase)
        }
}
