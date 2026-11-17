package com.demo.systemwp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 分散在整个 tile 的双频同步信号，不依赖同一方块的四角同时出现。
 * 两频在 672 像素内分别重复 16、21 次，互质，因此联合相位可区分块内位置。
 * 输入坐标采用像素中心；scale 是输入坐标到原水印坐标的补偿比例。
 */
internal object WatermarkLocator {
    const val TILE = 672
    private const val MIN_SCORE = 0.08
    private val frequencies = intArrayOf(16, 21)
    private val omega = DoubleArray(2) { 2.0 * PI * frequencies[it] / TILE }

    fun signal(position: Double): Double = omega.sumOf { sin(it * position) } * 60.0

    data class Axis(val scale: Double, val offset: Double, val score: Double)
    private data class Fit(val scale: Double, val phase: DoubleArray, val amplitude: DoubleArray) {
        // 两个频率都要存在，单一界面条纹不应成为高分候选。
        val score: Double get() = sqrt(amplitude[0] * amplitude[1])
    }

    fun locate(
        values: DoubleArray,
        checkCancelled: () -> Unit,
        onDiagnostic: (String) -> Unit = {}
    ): List<Axis> {
        if (values.size < 96) {
            onDiagnostic("rejected reason=too_short samples=${values.size} minimum=96")
            return emptyList()
        }
        onDiagnostic("search samples=${values.size} scaleRange=0.45..1.10 step=0.001 minScore=$MIN_SCORE")
        val mean = values.average()
        val slope = values.indices.sumOf {
            (it - (values.size - 1) / 2.0) * (values[it] - mean)
        } / values.indices.sumOf { val x = it - (values.size - 1) / 2.0; x * x }
        val weights = DoubleArray(values.size) { 0.5 - 0.5 * cos(2.0 * PI * it / (values.size - 1)) }
        val input = DoubleArray(values.size) {
            (values[it] - mean - slope * (it - (values.size - 1) / 2.0)) * weights[it]
        }
        val totalWeight = weights.sum()

        fun fit(scale: Double): Fit {
            val phases = DoubleArray(2)
            val amplitudes = DoubleArray(2)
            for (f in omega.indices) {
                val angle = omega[f] * scale
                val dc = cos(angle)
                val ds = sin(angle)
                var c = cos(angle * 0.5)
                var s = sin(angle * 0.5)
                var real = 0.0
                var imaginary = 0.0
                for (value in input) {
                    real += value * s
                    imaginary += value * c
                    val nextC = c * dc - s * ds
                    s = s * dc + c * ds
                    c = nextC
                }
                phases[f] = atan2(imaginary, real)
                amplitudes[f] = 2.0 * sqrt(real * real + imaginary * imaginary) / totalWeight
            }
            return Fit(scale, phases, amplitudes)
        }

        val coarse = ArrayList<Fit>()
        // 比旧正文的粗搜更密，且每个比例只处理两条投影，不扫描整张图片。
        for (step in 450..1100) {
            if (step % 16 == 0) checkCancelled()
            coarse.add(fit(step / 1000.0))
        }
        val centers = mutableListOf<Fit>()
        for (candidate in coarse.sortedByDescending { it.score }) {
            if (centers.all { abs(it.scale - candidate.scale) > 0.005 }) centers.add(candidate)
            if (centers.size == 4) break
        }
        onDiagnostic("coarse scanned=${coarse.size} retained=${centers.size} " +
            "limit=4 minScaleSeparation=0.005")
        val result = ArrayList<Axis>()
        for ((index, center) in centers.withIndex()) {
            checkCancelled()
            onDiagnostic("candidate=${index + 1} coarseScale=${center.scale} coarseScore=${center.score}")
            var best = center
            for (step in -10..10) {
                val candidate = fit(center.scale + step * 0.0001)
                if (candidate.score > best.score) best = candidate
            }
            val fineCenter = best.scale
            for (step in -5..5) {
                val candidate = fit(fineCenter + step * 0.00002)
                if (candidate.score > best.score) best = candidate
            }
            val details = "candidate=${index + 1} scale=${best.scale} score=${best.score} " +
                "amplitude16=${best.amplitude[0]} amplitude21=${best.amplitude[1]}"
            if (best.score < MIN_SCORE) {
                onDiagnostic("$details rejected reason=low_score threshold=$MIN_SCORE")
                continue
            }
            // 联合两个相位恢复完整块内坐标；不把单个条纹的周期误作 tile 周期。
            var offset = 0.0
            var error = Double.POSITIVE_INFINITY
            for (quarter in 0 until TILE * 4) {
                val position = quarter / 4.0
                val e = omega.indices.sumOf {
                    best.amplitude[it] * (1.0 - cos(omega[it] * position - best.phase[it]))
                }
                if (e < error) {
                    error = e
                    offset = position
                }
            }
            result.add(Axis(best.scale, offset, best.score))
            onDiagnostic("$details accepted offset=$offset phaseError=$error")
        }
        onDiagnostic("complete accepted=${result.size} rejectedLowScore=${centers.size - result.size}")
        return result.sortedByDescending { it.score }
    }
}
