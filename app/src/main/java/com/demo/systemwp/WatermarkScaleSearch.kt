package com.demo.systemwp

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 先定位新格式的同步信号，再回退旧格式的位置及尺度搜索。所有候选仍须通过 GCM 认证。
 * 每张图片或每个视频使用独立实例；未认证的比例只是提示，不能锁定为后续帧的唯一比例。
 */
class WatermarkScaleSearch {
    data class Result(
        val detection: ScreenWatermarkCodec.Detection,
        val scale: Double
    )

    private var hints = emptyList<Double>()
    private var searches = 0

    suspend fun detect(
        bitmap: Bitmap,
        original: ScreenWatermarkCodec.Detection,
        originalScale: Double = 1.0,
        onStage: suspend (String) -> Unit = {},
        onProgress: suspend (Double) -> Unit
    ): Result {
        var best = Result(original, originalScale)
        val candidates = mutableListOf(best)
        val tried = mutableSetOf(bitmap.width to bitmap.height)
        val coroutine = currentCoroutineContext()

        if (original.payload != null) return best
        val located = LocatedWatermarkCodec.detectLocated(bitmap,
            { coroutine.ensureActive() }, onStage)
        if (located != null) {
            if (located.detection.payload != null) return located
            if (LocatedWatermarkCodec.better(located.detection, best.detection)) best = located
        }
        onStage("定位版未通过认证，正在尝试兼容旧版水印…")
        val legacy = ScreenWatermarkCodec.detect(bitmap) { coroutine.ensureActive() }
        if (legacy.payload != null) return Result(legacy, 1.0)
        if (LocatedWatermarkCodec.better(legacy, best.detection)) best = Result(legacy, 1.0)
        // 两种格式的相关分数不在同一标度，旧版尺度候选只用旧版分数排名。
        candidates.clear()
        candidates.add(Result(legacy, 1.0))
        if (original.format == legacy.format && originalScale != 1.0) {
            candidates.add(Result(original, originalScale))
        }

        suspend fun attempt(scale: Double): Result? {
            coroutine.ensureActive()
            if (scale < MIN_SCALE || scale > 1.0) return null
            val width = (bitmap.width * scale).roundToInt()
            val height = (bitmap.height * scale).roundToInt()
            if (width < 64 || height < 64 || !tried.add(width to height)) return null
            onProgress(scale)
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            val detection = try {
                ScreenWatermarkCodec.detect(scaled) { coroutine.ensureActive() }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
            val result = Result(detection, scale)
            candidates.add(result)
            Log.i("SystemWP", "scale=$scale size=${width}x$height " +
                "status=${detection.status} score=${detection.score} " +
                "header=${detection.headerBitsObserved} " +
                "body=${detection.bodyBitsObserved}/${detection.bodyBitsRequired}")
            if (detection.payload != null ||
                detection.status.ordinal > best.detection.status.ordinal ||
                (detection.status == best.detection.status && detection.score > best.detection.score)
            ) best = result
            return result
        }

        // 后续帧优先重试之前的候选，仍逐帧认证，不把高分当成成功。
        for (scale in hints) {
            val result = attempt(scale)
            if (result?.detection?.payload != null) return result
        }

        // 完整搜索覆盖最先读到的三帧；后续帧复用候选，控制长视频的耗时。
        if (searches >= MAX_SEARCH_FRAMES) return best
        searches++
        // 常见编辑比例先试，再以 0.025 的补偿比例步长粗搜 1～2 倍放大。
        val coarse = listOf(0.8, 2.0 / 3.0, 0.75, 0.5) +
            (1..20).map { 1.0 - it * 0.025 }
        for (scale in coarse) {
            val result = attempt(scale)
            if (result?.detection?.payload != null) return result
        }

        fun strongest(count: Int): List<Result> {
            // 用同步相关强度排序几何候选，避免偶然合法的头部支配比例搜索。
            val selected = mutableListOf<Result>()
            for (candidate in candidates.sortedByDescending { it.detection.score }) {
                if (selected.all { abs(it.scale - candidate.scale) > 0.002 }) {
                    selected.add(candidate)
                    if (selected.size == count) break
                }
            }
            return selected
        }

        // 逐层细化到 0.0002；每层固定候选中心，避免边遍历边扩张。
        for ((radius, step) in listOf(0.025 to 0.005, 0.005 to 0.001, 0.001 to 0.0002)) {
            val centers = strongest(3)
            val steps = (radius / step).roundToInt()
            for (center in centers) {
                for (offset in -steps..steps) {
                    val result = attempt(center.scale + offset * step)
                    if (result?.detection?.payload != null) return result
                }
            }
        }
        hints = strongest(8).map { it.scale }
        return best
    }

    companion object {
        const val MIN_SCALE = 0.5
        const val MAX_SEARCH_FRAMES = 3
    }
}
