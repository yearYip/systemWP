package com.demo.systemwp

import kotlin.math.*

/** 独立 JVM 合成信号检查：不依赖 Android 设备或原始截图文件。 */
fun main() {
    val width = 640
    val height = 896
    val cases = listOf(7.0 to 1.0, 29.3 to 0.8, 45.0 to 1.0,
        89.0 to 0.6, 137.4 to 1.05, -23.7 to 0.9)
    for ((degrees, scale) in cases) for (mirror in listOf(false, true)) {
        val angle = degrees * PI / 180
        val pixels = IntArray(width * height) { i ->
            val x = (if (mirror) width - 1 - i % width else i % width) - width / 2.0
            val y = i / width - height / 2.0
            // 直接渲染旋转坐标中的有限窗口，相当于只收到中间裁剪区域。
            val u = (cos(angle) * x + sin(angle) * y) * scale + 173.2
            val v = (-sin(angle) * x + cos(angle) * y) * scale + 91.7
            val background = 145 + (15 * sin(x / 93) + 10 * cos(y / 117)).roundToInt()
            val g = (background + if ((floor(u / 3).toInt() + floor(v / 3).toInt()) % 2 == 0) 2 else -2)
            val redPilot = WatermarkSpectralTemplate.signal(u, v, true) * 6 / 255
            val bluePilot = WatermarkSpectralTemplate.signal(u, v, false) * 6 / 255
            val r = (background + redPilot).roundToInt().coerceIn(0, 255)
            val b = (background + bluePilot).roundToInt().coerceIn(0, 255)
            (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        val start = System.nanoTime()
        val candidates = WatermarkAngleEstimator.estimate(pixels, width, height, {}, {})
        val expected = if (mirror) degrees else -degrees
        val match = candidates.minByOrNull {
            val d = abs(it.degrees - expected) % 180
            min(d, 180 - d)
        }
        check(match != null) { "No angle: degrees=$degrees scale=$scale mirror=$mirror" }
        val d = abs(match.degrees - expected) % 180
        check(min(d, 180 - d) < 0.12 && abs(match.scale - scale) < 0.003) {
            "Wrong estimate: degrees=$degrees scale=$scale mirror=$mirror result=$match"
        }
        println("PASS rotation=$degrees scale=$scale mirror=$mirror result=$match ms=${(System.nanoTime() - start) / 1_000_000}")
    }
    for (y in 0 until WatermarkLocator.TILE step 7) for (x in 0 until WatermarkLocator.TILE step 7) {
        check(WatermarkSpectralTemplate.redPixel(x, y) in 8..248)
        check(WatermarkSpectralTemplate.bluePixel(x, y) in 8..248)
        check(WatermarkSpectralTemplate.redPixel(x, y) == WatermarkSpectralTemplate.redPixel(x + WatermarkLocator.TILE, y))
        check(WatermarkSpectralTemplate.bluePixel(x, y) == WatermarkSpectralTemplate.bluePixel(x, y + WatermarkLocator.TILE))
        check(WatermarkSpectralTemplate.redPixel(x, y) ==
            (128 + WatermarkSpectralTemplate.signal(x + 0.5, y + 0.5, true)).roundToInt())
        check(WatermarkSpectralTemplate.bluePixel(x, y) ==
            (128 + WatermarkSpectralTemplate.signal(x + 0.5, y + 0.5, false)).roundToInt())
    }
    check(WatermarkAngleEstimator.estimate(IntArray(width * height) { -1 }, width, height, {}, {}).isEmpty())
    var cancelled = false
    try {
        WatermarkAngleEstimator.estimate(IntArray(width * height), width, height,
            { throw InterruptedException("cancel test") }, {})
    } catch (_: InterruptedException) { cancelled = true }
    check(cancelled)
    println("PASS flat image and cancellation")
}
