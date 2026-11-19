package com.demo.systemwp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 多方向模板的横纵分量定位。两个方向在同一补偿比例下联合评分。
 * 横纵分量在 672 像素内重复 16、21 次；联合相位用于恢复块内位置。
 */
internal object WatermarkLocator {
    const val TILE = 672
    private const val MIN_SCORE = 0.08
    private val omega = doubleArrayOf(2.0 * PI * 16 / TILE, 2.0 * PI * 21 / TILE)

    data class Axis(val scale: Double, val offset: Double, val score: Double)
    data class Candidate(val x: Axis, val y: Axis, val score: Double)

    private data class Fit(val scale: Double, val phase: DoubleArray, val amplitude: DoubleArray) {
        val score: Double get() = sqrt(amplitude[0] * amplitude[1])
    }

    private data class JointFit(val x: Fit, val y: Fit) {
        val scale: Double get() = x.scale
        val score: Double get() = sqrt(x.score * y.score)
    }

    private class Projection(values: DoubleArray) {
        private val input: DoubleArray
        private val totalWeight: Double

        init {
            val mean = values.average()
            val midpoint = (values.size - 1) / 2.0
            val slope = values.indices.sumOf { (it - midpoint) * (values[it] - mean) } /
                values.indices.sumOf { val x = it - midpoint; x * x }
            val weights = DoubleArray(values.size) { 0.5 - 0.5 * cos(2.0 * PI * it / (values.size - 1)) }
            input = DoubleArray(values.size) { (values[it] - mean - slope * (it - midpoint)) * weights[it] }
            totalWeight = weights.sum()
        }

        fun fit(scale: Double): Fit {
            val phase = DoubleArray(2)
            val amplitude = DoubleArray(2)
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
                phase[f] = atan2(imaginary, real)
                amplitude[f] = 2.0 * sqrt(real * real + imaginary * imaginary) / totalWeight
            }
            return Fit(scale, phase, amplitude)
        }
    }

    fun locateJoint(
        horizontal: DoubleArray,
        vertical: DoubleArray,
        checkCancelled: () -> Unit,
        onDiagnostic: (String) -> Unit = {}
    ): List<Candidate> {
        if (horizontal.size < 96 || vertical.size < 96) {
            onDiagnostic("joint rejected reason=too_short samplesX=${horizontal.size} samplesY=${vertical.size}")
            return emptyList()
        }
        val x = Projection(horizontal)
        val y = Projection(vertical)
        fun fit(scale: Double) = JointFit(x.fit(scale), y.fit(scale))
        onDiagnostic("joint search samplesX=${horizontal.size} samplesY=${vertical.size} " +
            "scaleRange=0.45..1.10 step=0.001 minAxisScore=$MIN_SCORE")
        val coarse = (450..1100).map { step ->
            if (step % 16 == 0) checkCancelled()
            fit(step / 1000.0)
        }
        // 只选局部峰值，避免同一个宽峰的相邻肩部占满候选。
        val peaks = coarse.indices.filter { index ->
            val score = coarse[index].score
            val before = coarse.getOrNull(index - 1)?.score ?: Double.NEGATIVE_INFINITY
            val after = coarse.getOrNull(index + 1)?.score ?: Double.NEGATIVE_INFINITY
            score >= before && score >= after && (score > before || score > after)
        }.map { coarse[it] }.sortedByDescending { it.score }
        val centers = mutableListOf<JointFit>()
        for (candidate in peaks) {
            if (centers.all { abs(it.scale - candidate.scale) >= 0.015 }) centers.add(candidate)
            if (centers.size == 6) break
        }
        onDiagnostic("joint coarse scanned=${coarse.size} localPeaks=${peaks.size} " +
            "retained=${centers.size} limit=6 minScaleSeparation=0.015")
        val result = ArrayList<Candidate>()
        for ((index, center) in centers.withIndex()) {
            checkCancelled()
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
            val detail = "joint candidate=${index + 1} coarseScale=${center.scale} " +
                "scale=${best.scale} score=${best.score} scoreX=${best.x.score} scoreY=${best.y.score}"
            for ((axis, axisFit) in listOf("X" to best.x, "Y" to best.y)) {
                onDiagnostic("axis=$axis candidate=${index + 1} scale=${axisFit.scale} score=${axisFit.score} " +
                    "amplitude16=${axisFit.amplitude[0]} amplitude21=${axisFit.amplitude[1]}")
            }
            if (best.x.score < MIN_SCORE || best.y.score < MIN_SCORE) {
                onDiagnostic("$detail rejected reason=low_axis_score threshold=$MIN_SCORE")
                continue
            }
            val ax = axis(best.x, checkCancelled)
            val ay = axis(best.y, checkCancelled)
            onDiagnostic("$detail accepted offsetX=${ax.offset} offsetY=${ay.offset}")
            result.add(Candidate(ax, ay, best.score))
        }
        onDiagnostic("joint complete accepted=${result.size} rejected=${centers.size - result.size}")
        return result.sortedByDescending { it.score }
    }

    private fun axis(fit: Fit, checkCancelled: () -> Unit): Axis {
        var offset = 0.0
        var error = Double.POSITIVE_INFINITY
        for (quarter in 0 until TILE * 4) {
            if (quarter % 128 == 0) checkCancelled()
            val position = quarter / 4.0
            val e = omega.indices.sumOf {
                fit.amplitude[it] * (1.0 - cos(omega[it] * position - fit.phase[it]))
            }
            if (e < error) {
                error = e
                offset = position
            }
        }
        return Axis(fit.scale, offset, fit.score)
    }
}
