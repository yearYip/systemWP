package com.demo.systemwp

import kotlin.math.*

/**
 * 在收到的图片坐标内拟合八个已知定位波，不先旋转图片或做横纵投影。
 * 先以正余弦回归测相位，再解周期平移，最后联合精化角度、尺度和位置。
 * 输出仅为几何候选，身份仍由正文纠错及 GCM 认证决定。
 */
internal class WatermarkJointLocator private constructor(
    private val xs: DoubleArray,
    private val ys: DoubleArray,
    private val red: DoubleArray,
    private val blue: DoubleArray,
    private val wx: DoubleArray,
    private val wy: DoubleArray,
    private val step: Int,
    private val coordinateScale: Double
) {
    data class Geometry(val degrees: Double, val scale: Double, val mirrored: Boolean,
                        val centerX: Double, val centerY: Double, val score: Double)
    private data class Observation(val tone: WatermarkSpectralTemplate.Tone,
                                   val phase: Double, val amplitude: Double, val weight: Double)
    private data class ChannelFit(val coefficients: DoubleArray, val observations: List<Observation>)
    private data class Seed(val geometry: Geometry, val redFit: ChannelFit, val blueFit: ChannelFit)
    private data class Normal(val matrix: DoubleArray, val rhs: DoubleArray, val loss: Double)

    companion object {
        private const val PERIOD = WatermarkLocator.TILE * 1.0
        private const val OMEGA = 2 * PI / PERIOD

        fun prepare(pixels: IntArray, width: Int, height: Int, check: () -> Unit): WatermarkJointLocator {
            // 步长至多 6，避免定位频率混叠；只用一个中央窗口，不做多区域检测。
            val step = ceil(max(width, height) / 192.0).toInt().coerceIn(1, 6)
            val w = min(width / step, 192)
            val h = min(height / step, 192)
            val ox = (width - w * step) / 2
            val oy = (height - h * step) / 2
            val red = DoubleArray(w * h)
            val blue = DoubleArray(w * h)
            for (y in 0 until h) {
                check()
                for (x in 0 until w) {
                    var r = 0.0
                    var b = 0.0
                    for (dy in 0 until step) for (dx in 0 until step) {
                        val pixel = pixels[(oy + y * step + dy) * width + ox + x * step + dx]
                        val green = (pixel ushr 8) and 255
                        r += ((pixel ushr 16) and 255) - green
                        b += (pixel and 255) - green
                    }
                    red[y * w + x] = r / (step * step)
                    blue[y * w + x] = b / (step * step)
                }
            }
            return WatermarkJointLocator(
                DoubleArray(w) { ox + it * step + (step - 1) / 2.0 - (width - 1) / 2.0 },
                DoubleArray(h) { oy + it * step + (step - 1) / 2.0 - (height - 1) / 2.0 },
                red, blue,
                DoubleArray(w) { 0.5 - 0.5 * cos(2 * PI * it / max(1, w - 1)) },
                DoubleArray(h) { 0.5 - 0.5 * cos(2 * PI * it / max(1, h - 1)) },
                step, max(width, height).toDouble())
        }

        private fun wrap(angle: Double): Double = atan2(sin(angle), cos(angle))
        private fun periodic(position: Double): Double = (position % PERIOD + PERIOD) % PERIOD
        private fun distance(a: Double, b: Double): Double {
            val d = abs(a - b) % PERIOD
            return min(d, PERIOD - d)
        }

        /** 带主元的稠密线性求解，小矩阵用于回归和阻尼 Gauss–Newton。 */
        private fun solve(matrix: DoubleArray, rhs: DoubleArray, damping: Double): DoubleArray? {
            val n = rhs.size
            val a = matrix.copyOf()
            val b = rhs.copyOf()
            for (i in 0 until n) a[i * n + i] += damping * max(abs(a[i * n + i]), 1.0)
            for (column in 0 until n) {
                var pivot = column
                for (row in column + 1 until n) if (abs(a[row * n + column]) > abs(a[pivot * n + column])) pivot = row
                if (!a[pivot * n + column].isFinite() || abs(a[pivot * n + column]) < 1e-10) return null
                if (pivot != column) {
                    for (j in 0 until n) {
                        val value = a[column * n + j]
                        a[column * n + j] = a[pivot * n + j]
                        a[pivot * n + j] = value
                    }
                    val value = b[column]; b[column] = b[pivot]; b[pivot] = value
                }
                for (row in column + 1 until n) {
                    val factor = a[row * n + column] / a[column * n + column]
                    for (j in column until n) a[row * n + j] -= factor * a[column * n + j]
                    b[row] -= factor * b[column]
                }
            }
            val result = DoubleArray(n)
            for (row in n - 1 downTo 0) {
                var value = b[row]
                for (j in row + 1 until n) value -= a[row * n + j] * result[j]
                result[row] = value / a[row * n + row]
            }
            return result.takeIf { it.all { value -> value.isFinite() } }
        }
    }

    fun locate(candidates: List<WatermarkAngleEstimator.Candidate>, maxMillis: Long,
               check: () -> Unit, diagnostic: (String) -> Unit): List<Geometry> {
        if (xs.size < 16 || ys.size < 16 || maxMillis <= 0) return emptyList()
        val deadline = System.nanoTime() + maxMillis * 1_000_000
        fun expired() = System.nanoTime() >= deadline
        val seeds = ArrayList<Seed>()
        poseLoop@ for (candidate in candidates.take(4)) for (halfTurn in 0..1) {
            for (mirror in listOf(candidate.mirrored, !candidate.mirrored)) {
                check()
                if (expired()) break@poseLoop
                val geometry = Geometry((candidate.degrees + halfTurn * 180 + 360) % 360,
                    candidate.scale, mirror, 0.0, 0.0, 0.0)
                val redFit = fitChannel(WatermarkSpectralTemplate.red, red, geometry, check, ::expired) ?: continue
                val blueFit = fitChannel(WatermarkSpectralTemplate.blue, blue, geometry, check, ::expired) ?: continue
                val positions = translations(redFit.observations + blueFit.observations, check)
                for ((x, y, score) in positions.take(2)) {
                    seeds.add(Seed(geometry.copy(centerX = x, centerY = y, score = score), redFit, blueFit))
                }
                diagnostic("joint_phase degrees=${geometry.degrees} scale=${geometry.scale} mirror=$mirror " +
                    "positions=${positions.size} phaseScore=${positions.firstOrNull()?.third}")
            }
        }
        val selected = ArrayList<Seed>()
        for (seed in seeds.sortedByDescending { it.geometry.score }) {
            val g = seed.geometry
            if (selected.any {
                val a = abs(it.geometry.degrees - g.degrees) % 360
                min(a, 360 - a) < 0.05 && it.geometry.mirrored == g.mirrored &&
                    abs(it.geometry.scale - g.scale) < 0.001 &&
                    distance(it.geometry.centerX, g.centerX) < 2 && distance(it.geometry.centerY, g.centerY) < 2
            }) continue
            selected.add(seed)
            if (selected.size == 4) break
        }
        val result = selected.map { seed ->
            val refined = if (expired()) seed.geometry else refine(seed, check, ::expired)
            diagnostic("joint_geometry degrees=${refined.degrees} scale=${refined.scale} mirror=${refined.mirrored} " +
                "centerX=${refined.centerX} centerY=${refined.centerY} phaseScore=${refined.score}")
            refined
        }
        diagnostic("joint_complete seeds=${seeds.size} retained=${result.size} " +
            "reason=${if (expired()) "refinement_budget" else "complete"}")
        return result
    }

    /** 同时回归本通道的四个波和背景平面，避免裁剪窗口造成其他频率泄漏到相位。 */
    private fun fitChannel(tones: List<WatermarkSpectralTemplate.Tone>, values: DoubleArray,
                           geometry: Geometry, check: () -> Unit, expired: () -> Boolean): ChannelFit? {
        val n = 3 + tones.size * 2
        val matrix = DoubleArray(n * n)
        val rhs = DoubleArray(n)
        val basis = DoubleArray(n)
        val angle = geometry.degrees * PI / 180
        val c = cos(angle); val s = sin(angle); val sign = if (geometry.mirrored) -1 else 1
        val ax = tones.map { OMEGA * geometry.scale * (sign * it.x * c + it.y * s) }
        val ay = tones.map { OMEGA * geometry.scale * (-sign * it.x * s + it.y * c) }
        val dc = ax.map { cos(it * step) }; val ds = ax.map { sin(it * step) }
        val cs = DoubleArray(tones.size); val sn = DoubleArray(tones.size)
        var squares = 0.0
        var totalWeight = 0.0
        for (y in ys.indices) {
            check()
            if (expired()) return null
            for (i in tones.indices) {
                val phase = ax[i] * xs[0] + ay[i] * ys[y]
                cs[i] = cos(phase); sn[i] = sin(phase)
            }
            for (x in xs.indices) {
                val weight = wx[x] * wy[y]
                val value = values[y * xs.size + x]
                basis[0] = 1.0; basis[1] = xs[x] / coordinateScale; basis[2] = ys[y] / coordinateScale
                for (i in tones.indices) {
                    basis[3 + i * 2] = sn[i]; basis[4 + i * 2] = cs[i]
                    val next = cs[i] * dc[i] - sn[i] * ds[i]
                    sn[i] = sn[i] * dc[i] + cs[i] * ds[i]; cs[i] = next
                }
                for (i in 0 until n) {
                    rhs[i] += weight * basis[i] * value
                    for (j in i until n) matrix[i * n + j] += weight * basis[i] * basis[j]
                }
                squares += weight * value * value; totalWeight += weight
            }
        }
        for (i in 0 until n) for (j in 0 until i) matrix[i * n + j] = matrix[j * n + i]
        val coefficients = solve(matrix, rhs, 1e-8) ?: return null
        val residual = max(0.001, (squares - coefficients.indices.sumOf { coefficients[it] * rhs[it] }) / max(1.0, totalWeight))
        val observations = tones.mapIndexed { i, tone ->
            val a = coefficients[3 + i * 2]; val b = coefficients[4 + i * 2]
            val amplitude = hypot(a, b)
            Observation(tone, wrap(atan2(b, a) - tone.phase), amplitude,
                amplitude * amplitude / (residual + amplitude * amplitude))
        }
        return ChannelFit(coefficients, observations)
    }

    /** 由多组非共线波的相位生成周期位置假设，八个信号共同消除周期歧义。 */
    private fun translations(observations: List<Observation>, check: () -> Unit): List<Triple<Double, Double, Double>> {
        val weights = observations.sumOf { it.weight }
        if (weights <= 1e-8) return emptyList()
        fun score(x: Double, y: Double): Double = observations.sumOf {
            it.weight * cos(OMEGA * (it.tone.x * x + it.tone.y * y) - it.phase)
        } / weights
        val pairs = observations.indices.flatMap { i -> (i + 1 until observations.size).map { j -> i to j } }
            .filter { (i, j) ->
                val a = observations[i].tone; val b = observations[j].tone
                abs(a.x * b.y - a.y * b.x) > 30
            }.sortedByDescending { (i, j) -> observations[i].weight * observations[j].weight }.take(6)
        val strongest = ArrayList<Triple<Double, Double, Double>>()
        for ((i, j) in pairs) {
            check()
            val a = observations[i]; val b = observations[j]
            val determinant = (a.tone.x * b.tone.y - a.tone.y * b.tone.x).toDouble()
            val loA = min(0, a.tone.x) + min(0, a.tone.y) - 1
            val hiA = max(0, a.tone.x) + max(0, a.tone.y) + 1
            val loB = min(0, b.tone.x) + min(0, b.tone.y) - 1
            val hiB = max(0, b.tone.x) + max(0, b.tone.y) + 1
            for (m in loA..hiA) for (n in loB..hiB) {
                val pa = a.phase / (2 * PI) + m; val pb = b.phase / (2 * PI) + n
                val x = (pa * b.tone.y - pb * a.tone.y) / determinant * PERIOD
                val y = (a.tone.x * pb - b.tone.x * pa) / determinant * PERIOD
                if (x < 0 || x >= PERIOD || y < 0 || y >= PERIOD) continue
                val value = score(x, y)
                if (strongest.size == 12 && value <= strongest.last().third) continue
                strongest.add(Triple(x, y, value)); strongest.sortByDescending { it.third }
                if (strongest.size > 12) strongest.removeAt(strongest.lastIndex)
            }
        }
        val refined = strongest.map { seed ->
            var x = seed.first; var y = seed.second
            repeat(10) {
                check()
                var xx = 0.0; var xy = 0.0; var yy = 0.0; var gx = 0.0; var gy = 0.0
                for (wave in observations) {
                    val kx = OMEGA * wave.tone.x; val ky = OMEGA * wave.tone.y
                    val error = wrap(kx * x + ky * y - wave.phase)
                    xx += wave.weight * kx * kx; xy += wave.weight * kx * ky; yy += wave.weight * ky * ky
                    gx += wave.weight * kx * error; gy += wave.weight * ky * error
                }
                val determinant = xx * yy - xy * xy
                if (determinant > 1e-12) {
                    x = periodic(x - ((yy * gx - xy * gy) / determinant).coerceIn(-5.0, 5.0))
                    y = periodic(y - ((xx * gy - xy * gx) / determinant).coerceIn(-5.0, 5.0))
                }
            }
            Triple(x, y, score(x, y))
        }.sortedByDescending { it.third }
        val selected = ArrayList<Triple<Double, Double, Double>>()
        for (position in refined) {
            if (position.third < 0.35) continue
            if (selected.none { distance(it.first, position.first) < 2 && distance(it.second, position.second) < 2 }) {
                selected.add(position)
                if (selected.size == 2) break
            }
        }
        return selected
    }

    private fun attenuation(increment: Double): Double = if (abs(increment) < 1e-8) 1.0
        else sin(step * increment / 2) / (step * sin(increment / 2))

    private fun refine(seed: Seed, check: () -> Unit, expired: () -> Boolean): Geometry {
        val g = seed.geometry
        val parameters = doubleArrayOf(g.degrees, ln(g.scale), g.centerX, g.centerY,
            max(0.01, seed.redFit.observations.map { it.amplitude }.average()),
            max(0.01, seed.blueFit.observations.map { it.amplitude }.average()),
            seed.redFit.coefficients[0], seed.redFit.coefficients[1], seed.redFit.coefficients[2],
            seed.blueFit.coefficients[0], seed.blueFit.coefficients[1], seed.blueFit.coefficients[2])
        var current = parameters
        var normal = model(current, g.mirrored, check, expired) ?: return g
        var damping = 0.01
        for (iteration in 0 until 12) {
            check()
            if (expired()) break
            val delta = solve(normal.matrix, normal.rhs, damping) ?: break
            val candidate = DoubleArray(current.size) { current[it] + delta[it] }
            candidate[0] = current[0] + delta[0].coerceIn(-0.15, 0.15)
            candidate[1] = (current[1] + delta[1].coerceIn(-0.003, 0.003)).coerceIn(ln(0.45), ln(1.10))
            candidate[2] = current[2] + delta[2].coerceIn(-3.0, 3.0)
            candidate[3] = current[3] + delta[3].coerceIn(-3.0, 3.0)
            candidate[4] = candidate[4].coerceAtLeast(0.01); candidate[5] = candidate[5].coerceAtLeast(0.01)
            val next = model(candidate, g.mirrored, check, expired) ?: break
            if (next.loss < normal.loss) {
                current = candidate; normal = next; damping = max(1e-6, damping / 3)
            } else damping = min(1e6, damping * 10)
        }
        return g.copy(degrees = (current[0] % 360 + 360) % 360, scale = exp(current[1]),
            centerX = periodic(current[2]), centerY = periodic(current[3]))
    }

    /** 四个几何参数、两个通道幅度及两个背景平面，共同拟合两通道的八个波。 */
    private fun model(p: DoubleArray, mirrored: Boolean, check: () -> Unit, expired: () -> Boolean): Normal? {
        val count = p.size
        val matrix = DoubleArray(count * count); val rhs = DoubleArray(count)
        val gradient = DoubleArray(count)
        val angle = p[0] * PI / 180; val c = cos(angle); val s = sin(angle)
        val scale = exp(p[1]); val sign = if (mirrored) -1 else 1
        val channels = listOf(WatermarkSpectralTemplate.red to red, WatermarkSpectralTemplate.blue to blue)
        var loss = 0.0
        for ((channel, data) in channels.withIndex()) {
            val tones = data.first
            val amplitudeIndex = 4 + channel; val background = 6 + channel * 3
            val smoothing = tones.map {
                attenuation(OMEGA * scale * (sign * it.x * c + it.y * s)) *
                    attenuation(OMEGA * scale * (-sign * it.x * s + it.y * c))
            }
            for (y in ys.indices) {
                check()
                if (expired()) return null
                for (x in xs.indices) {
                    val dx = xs[x]; val dy = ys[y]
                    val u = scale * sign * (c * dx - s * dy)
                    val v = scale * (s * dx + c * dy)
                    val du = scale * sign * (-s * dx - c * dy) * PI / 180
                    val dv = scale * (c * dx - s * dy) * PI / 180
                    var signal = 0.0; var ga = 0.0; var gs = 0.0; var gx = 0.0; var gy = 0.0
                    for (i in tones.indices) {
                        val tone = tones[i]
                        val phase = OMEGA * (tone.x * (u + p[2]) + tone.y * (v + p[3])) + tone.phase
                        val derivative = smoothing[i] * cos(phase)
                        signal += smoothing[i] * sin(phase)
                        ga += derivative * OMEGA * (tone.x * du + tone.y * dv)
                        gs += derivative * OMEGA * (tone.x * u + tone.y * v)
                        gx += derivative * OMEGA * tone.x; gy += derivative * OMEGA * tone.y
                    }
                    val nx = dx / coordinateScale; val ny = dy / coordinateScale
                    val prediction = p[background] + p[background + 1] * nx + p[background + 2] * ny + p[amplitudeIndex] * signal
                    val residual = data.second[y * xs.size + x] - prediction
                    // Huber 权重限制局部彩色背景对几何更新的影响。
                    val weight = wx[x] * wy[y] * min(1.0, 8.0 / max(8.0, abs(residual)))
                    gradient.fill(0.0)
                    gradient[0] = p[amplitudeIndex] * ga; gradient[1] = p[amplitudeIndex] * gs
                    gradient[2] = p[amplitudeIndex] * gx; gradient[3] = p[amplitudeIndex] * gy
                    gradient[amplitudeIndex] = signal
                    gradient[background] = 1.0; gradient[background + 1] = nx; gradient[background + 2] = ny
                    for (i in 0 until count) {
                        rhs[i] += weight * gradient[i] * residual
                        for (j in i until count) matrix[i * count + j] += weight * gradient[i] * gradient[j]
                    }
                    val error = abs(residual)
                    loss += wx[x] * wy[y] * (if (error <= 8.0) residual * residual else 16.0 * error - 64.0)
                }
            }
        }
        for (i in 0 until count) for (j in 0 until i) matrix[i * count + j] = matrix[j * count + i]
        return Normal(matrix, rhs, loss)
    }
}
