package com.demo.systemwp

import kotlin.math.*

/** 匹配多方向频谱模板，只使用收到的截图。 */
internal object WatermarkAngleEstimator {
    data class Candidate(val degrees: Double, val scale: Double, val score: Double,
                         val mirrored: Boolean = false)
    private data class Family(val low: Double, val high: Double, val redAngle: Double)
    private data class Peak(val x: Double, val y: Double, val power: Double)
    private data class Spectrum(val width: Int, val height: Int, val values: DoubleArray,
                                val power: DoubleArray)
    private val families = listOf(
        Family(16.0 / WatermarkLocator.TILE, 21.0 / WatermarkLocator.TILE, 0.0),
        Family(15.0 / WatermarkLocator.TILE, 20.0 / WatermarkLocator.TILE, atan2(12.0, 9.0))
    )

    fun estimate(pixels: IntArray, width: Int, height: Int, check: () -> Unit,
                 diagnostic: (String) -> Unit): List<Candidate> {
        var stageStart = System.nanoTime()
        fun timing(stage: String) {
            val now = System.nanoTime()
            diagnostic("timing stage=$stage elapsedMs=${(now - stageStart) / 1_000_000.0}")
            stageStart = now
        }
        // 块平均抑制正文高频；限制步长以避免定位信号混叠，长图取中央窗口。
        val step = ceil(max(width, height) / 768.0).toInt().coerceIn(1, 8)
        val w = min(width / step, 768)
        val h = min(height / step, 768)
        if (min(w, h) < 32) {
            diagnostic("angle_spectrum rejected reason=too_short sampled=${w}x$h")
            return emptyList()
        }
        val originX = (width - w * step) / 2
        val originY = (height - h * step) / 2
        val red = DoubleArray(w * h)
        val blue = DoubleArray(w * h)
        for (y in 0 until h) {
            check()
            for (x in 0 until w) {
                var r = 0.0
                var b = 0.0
                for (dy in 0 until step) for (dx in 0 until step) {
                    val p = pixels[(originY + y * step + dy) * width + originX + x * step + dx]
                    val g = (p ushr 8) and 255
                    r += ((p ushr 16) and 255) - g
                    b += (p and 255) - g
                }
                red[y * w + x] = r / (step * step)
                blue[y * w + x] = b / (step * step)
            }
        }
        timing("angle_prepare")
        val r = spectrum(red, w, h, check)
        val b = spectrum(blue, w, h, check)
        timing("angle_fft")
        val redPeaks = peaks(r, step, check)
        val bluePeaks = peaks(b, step, check)
        fun pairs(s: Spectrum, peaks: List<Peak>, family: Family): List<Peak> {
            val result = ArrayList<Peak>()
            val tolerance = 2.5 / min(s.width, s.height)
            for (lo in peaks) for (hi in peaks) {
                val radius = hypot(lo.x, lo.y) / step
                if (radius / family.low !in 0.44..1.11) continue
                val ratio = family.high / family.low
                if (abs(hypot(hi.x, hi.y) / hypot(lo.x, lo.y) - ratio) > 0.10) continue
                if (hypot(hi.x - lo.x * ratio, hi.y - lo.y * ratio) > tolerance) continue
                result.add(Peak((lo.x + hi.x / ratio) / 2,
                    (lo.y + hi.y / ratio) / 2, sqrt(lo.power * hi.power)))
            }
            return result.sortedByDescending { it.power }.take(24)
        }
        val candidates = ArrayList<Candidate>()
        for (family in families) {
            val rp = pairs(r, redPeaks, family)
            val bp = pairs(b, bluePeaks, family)
            diagnostic("angle_family redDirectionDegrees=${family.redAngle * 180 / PI} " +
                "redPairs=${rp.size} bluePairs=${bp.size}")
            for (x in rp) for (y in bp) {
                check()
                val rx = hypot(x.x, x.y)
                val ry = hypot(y.x, y.y)
                if (abs(rx - ry) / max(rx, ry) > 0.05) continue
                if (abs(x.x * y.x + x.y * y.y) / (rx * ry) > 0.08) continue
                val scale = (rx + ry) / (2 * step * family.low)
                if (scale !in 0.45..1.10) continue
                for (mirror in listOf(false, true)) {
                    val reference = if (mirror) -family.redAngle else family.redAngle
                    candidates.add(Candidate((reference - atan2(x.y, x.x)) * 180 / PI, scale,
                        sqrt(x.power * y.power), mirror))
                }
            }
        }

        // 横纵或斜向峰只提供初值，所有候选都用完整的八个频谱峰评分。
        fun templateScore(candidate: Candidate, degrees: Double, scale: Double,
                          read: (Spectrum, Double, Double) -> Double): Double {
            val redTones = WatermarkSpectralTemplate.red
            val blueTones = WatermarkSpectralTemplate.blue
            var logarithm = 0.0
            for ((spectrum, tones) in listOf(r to redTones, b to blueTones)) for (tone in tones) {
                val angle = -degrees * PI / 180 + (if (candidate.mirrored) -tone.angle else tone.angle)
                val value = read(spectrum, tone.frequency * scale * step, angle)
                if (value <= 0) return 0.0
                logarithm += ln(value)
            }
            return exp(logarithm / (redTones.size + blueTones.size))
        }
        fun coarseAmplitude(s: Spectrum, frequency: Double, angle: Double): Double {
            val x = (frequency * cos(angle) * s.width).roundToInt()
            val y = (frequency * sin(angle) * s.height).roundToInt()
            var power = 0.0
            for (dy in -1..1) for (dx in -1..1) {
                power = max(power, s.power[Math.floorMod(y + dy, s.height) * s.width + Math.floorMod(x + dx, s.width)])
            }
            return sqrt(power) * 2 / s.power.size
        }
        val selected = ArrayList<Candidate>()
        val ranked = candidates.map { it.copy(score = templateScore(it, it.degrees, it.scale, ::coarseAmplitude)) }
        for (candidate in ranked.sortedByDescending { it.score }) {
            if (candidate.score <= 0) continue
            if (selected.any { angleDistance(it.degrees, candidate.degrees) < 2 &&
                        abs(it.scale - candidate.scale) < 0.015 }) continue
            selected.add(candidate)
            if (selected.size == 4) break
        }
        diagnostic("angle_spectrum sampled=${w}x$h step=$step fft=${r.width}x${r.height} " +
            "redPeaks=${redPeaks.size} bluePeaks=${bluePeaks.size} candidates=${selected.size}")
        timing("angle_peak_pairing")
        // 在连续频率上精化，避免 FFT 栅格误差使大图边缘的正文采样错位。
        val refinementDeadline = System.nanoTime() + 2_500_000_000L
        val refined = selected.map { seed ->
            fun score(degrees: Double, scale: Double): Double {
                return templateScore(seed, degrees, scale) { spectrum, frequency, angle ->
                    amplitude(spectrum, frequency, angle, check)
                }
            }
            var best = seed.copy(score = score(seed.degrees, seed.scale))
            refinement@ for ((angleStep, scaleStep) in listOf(0.2 to 0.002, 0.05 to 0.0005, 0.01 to 0.0001)) {
                for (pass in 0 until 2) {
                    val center = best
                    for (da in -1..1) for (ds in -1..1) {
                        check()
                        if (System.nanoTime() >= refinementDeadline) break@refinement
                        val scale = center.scale + ds * scaleStep
                        if (scale !in 0.45..1.10) continue
                        val angle = center.degrees + da * angleStep
                        val value = score(angle, scale)
                        if (value > best.score) best = seed.copy(degrees = angle, scale = scale, score = value)
                    }
                }
            }
            diagnostic("angle_candidate compensationDegrees=${best.degrees} scale=${best.scale} " +
                "pilotAmplitude=${best.score} template=multi " +
                "mirror=${best.mirrored}")
            best
        }.sortedByDescending { it.score }
        timing("angle_spectral_refinement")
        return refined
    }

    private fun angleDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 180
        return min(d, 180 - d)
    }

    private fun spectrum(input: DoubleArray, w: Int, h: Int, check: () -> Unit): Spectrum {
        fun padded(n: Int): Int { var size = 1; while (size < n) size *= 2; return size }
        val fw = padded(w)
        val fh = padded(h)
        val real = DoubleArray(fw * fh)
        val imaginary = DoubleArray(real.size)
        val mean = input.average()
        val wx = DoubleArray(w) { 0.5 - 0.5 * cos(2 * PI * it / (w - 1)) }
        val wy = DoubleArray(h) { 0.5 - 0.5 * cos(2 * PI * it / (h - 1)) }
        for (y in 0 until h) {
            check()
            for (x in 0 until w) real[y * fw + x] = (input[y * w + x] - mean) * wx[x] * wy[y]
        }
        val values = real.copyOf()
        val re = DoubleArray(max(fw, fh))
        val im = DoubleArray(re.size)
        for (y in 0 until fh) {
            check()
            for (x in 0 until fw) { re[x] = real[y * fw + x]; im[x] = 0.0 }
            fft(re, im, fw)
            for (x in 0 until fw) { real[y * fw + x] = re[x]; imaginary[y * fw + x] = im[x] }
        }
        for (x in 0 until fw) {
            check()
            for (y in 0 until fh) { re[y] = real[y * fw + x]; im[y] = imaginary[y * fw + x] }
            fft(re, im, fh)
            for (y in 0 until fh) { real[y * fw + x] = re[y]; imaginary[y * fw + x] = im[y] }
        }
        return Spectrum(fw, fh, values,
            DoubleArray(real.size) { real[it] * real[it] + imaginary[it] * imaginary[it] })
    }

    private fun peaks(s: Spectrum, step: Int, check: () -> Unit): List<Peak> {
        fun power(x: Int, y: Int) = s.power[Math.floorMod(y, s.height) * s.width + Math.floorMod(x, s.width)]
        val peaks = ArrayList<Peak>()
        for (y in -s.height / 2 + 1 until s.height / 2 - 1) {
            check()
            for (x in 0 until s.width / 2 - 1) {
            if (x == 0 && y <= 0) continue // 共轭峰只保留半平面，180° 歧义由头部解决。
            val radius = hypot(x.toDouble() / s.width, y.toDouble() / s.height) / step
            if (radius !in (15.0 / WatermarkLocator.TILE * 0.43)..(21.0 / WatermarkLocator.TILE * 1.12)) continue
            val p = power(x, y)
            if (p <= 0 || (-1..1).any { dy -> (-1..1).any { dx -> power(x + dx, y + dy) > p } }) continue
            fun offset(before: Double, after: Double): Double {
                val a = ln(max(before, 1e-20)); val c = ln(max(after, 1e-20)); val b = ln(p)
                val denominator = a - 2 * b + c
                return if (abs(denominator) < 1e-12) 0.0 else (0.5 * (a - c) / denominator).coerceIn(-0.5, 0.5)
            }
            peaks.add(Peak((x + offset(power(x - 1, y), power(x + 1, y))) / s.width,
                (y + offset(power(x, y - 1), power(x, y + 1))) / s.height, p))
            }
        }
        return peaks.sortedByDescending { it.power }.take(64)
    }

    private fun amplitude(s: Spectrum, frequency: Double, angle: Double, check: () -> Unit): Double {
        val ax = 2 * PI * frequency * cos(angle)
        val ay = 2 * PI * frequency * sin(angle)
        val dc = cos(ax); val ds = sin(ax)
        var real = 0.0; var imaginary = 0.0
        for (y in 0 until s.height) {
            if (y % 32 == 0) check()
            var c = cos(ay * y); var t = sin(ay * y)
            for (x in 0 until s.width) {
                val v = s.values[y * s.width + x]
                real += v * c; imaginary += v * t
                val next = c * dc - t * ds
                t = t * dc + c * ds; c = next
            }
        }
        return hypot(real, imaginary) * 2 / s.values.size
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray, n: Int) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val v = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = v
            }
        }
        var size = 2
        while (size <= n) {
            val dc = cos(-2 * PI / size); val ds = sin(-2 * PI / size)
            for (start in 0 until n step size) {
                var c = 1.0; var s = 0.0
                for (k in 0 until size / 2) {
                    val a = start + k; val b = a + size / 2
                    val r = real[b] * c - imaginary[b] * s
                    val v = real[b] * s + imaginary[b] * c
                    real[b] = real[a] - r; imaginary[b] = imaginary[a] - v
                    real[a] += r; imaginary[a] += v
                    val next = c * dc - s * ds; s = s * dc + c * ds; c = next
                }
            }
            size *= 2
        }
    }
}
