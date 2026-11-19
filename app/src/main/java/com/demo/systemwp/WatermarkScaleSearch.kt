package com.demo.systemwp

import android.graphics.Bitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** W2 定位、头部校准及屏幕尺寸补偿入口；每个结果均须通过 GCM 认证。 */
class WatermarkScaleSearch {
    data class Result(
        val detection: LocatedWatermarkCodec.Detection,
        val scale: Double,
        val timings: WatermarkTimings? = null
    )

    suspend fun detect(
        bitmap: Bitmap,
        original: LocatedWatermarkCodec.Detection,
        originalScale: Double = 1.0,
        onStage: suspend (WatermarkParseProgress) -> Unit = {},
        knownScaleFallback: (suspend () -> Result)? = null
    ): Result {
        var best = Result(original, originalScale)
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        if (original.payload != null) return best
        val timings = WatermarkTimings()
        val located = LocatedWatermarkCodec.detectLocated(bitmap, { coroutine.ensureActive() }, onStage, timings)
        if (located != null) {
            if (located.detection.payload != null) return located.copy(timings = timings)
            if (LocatedWatermarkCodec.better(located.detection, best.detection)) best = located
        }
        if (knownScaleFallback != null) {
            onStage(WatermarkParseProgress(WatermarkParseProgress.Stage.SCREEN_COMPENSATION,
                "正在尝试屏幕尺寸补偿…"))
            val compensated = knownScaleFallback()
            if (compensated.detection.payload != null) return compensated.copy(timings = timings)
            if (LocatedWatermarkCodec.better(compensated.detection, best.detection)) best = compensated
        }
        return best.copy(timings = timings)
    }
}
