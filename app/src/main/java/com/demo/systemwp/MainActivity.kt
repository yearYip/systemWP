package com.demo.systemwp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Display
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var etUsername: EditText
    private lateinit var etDeviceId: EditText
    private lateinit var btnToggle: Button
    private lateinit var tvResult: TextView
    private lateinit var tvParseElapsed: TextView
    private lateinit var tvParseStep: TextView
    private lateinit var tvParseSteps: TextView
    private lateinit var tvCompletedParseSteps: TextView
    private var lastParseProgress: WatermarkParseProgress? = null
    private val visitedParseStages = mutableSetOf<WatermarkParseProgress.Stage>()
    private val skippedParseStages = mutableSetOf<WatermarkParseProgress.Stage>()
    private val budgetExitedParseStages = mutableSetOf<WatermarkParseProgress.Stage>()
    private val completedParseSteps = mutableListOf<WatermarkParseProgress>()

    private var startIssued = false
    private var parseJob: Job? = null
    private var parseTimerJob: Job? = null
    private var parseStartedAt: Long? = null
    private lateinit var btnCancelParse: Button

    private val identityHandler = Handler(Looper.getMainLooper())

    private val commitIdentity = Runnable {
        if (!applyIdentity()) return@Runnable
        updateToggleText()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.grant_overlay, Toast.LENGTH_LONG).show()
            return@Runnable
        }
        startWatermark()
    }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { parseImage(it) }
        }

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { parseVideo(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = WatermarkPrefs.prefs(this)

        etUsername = findViewById(R.id.et_username)
        etDeviceId = findViewById(R.id.et_device_id)
        btnToggle = findViewById(R.id.btn_toggle)
        tvResult = findViewById(R.id.tv_result)
        tvParseElapsed = findViewById(R.id.tv_parse_elapsed)
        tvParseStep = findViewById(R.id.tv_parse_step)
        tvParseSteps = findViewById(R.id.tv_parse_steps)
        tvCompletedParseSteps = findViewById(R.id.tv_parse_completed_steps)
        btnCancelParse = findViewById(R.id.btn_cancel_parse)
        btnCancelParse.setOnClickListener {
            parseJob?.cancel()
            finishParseTimer()
            finishParseProgress(cancelled = true)
            btnCancelParse.visibility = android.view.View.GONE
            tvResult.setText(R.string.parse_cancelled)
        }

        etUsername.setText(prefs.getString(WatermarkPrefs.KEY_USERNAME, ""))
        etDeviceId.setText(prefs.getString(WatermarkPrefs.KEY_DEVICE_ID, ""))

        btnToggle.setOnClickListener { onToggle() }
        btnToggle.setOnLongClickListener {
            toggleDebugOverlay()
            true
        }
        findViewById<Button>(R.id.btn_pick).setOnClickListener {
            pickImage.launch("image/*")
        }
        findViewById<Button>(R.id.btn_pick_video).setOnClickListener {
            pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }
        watchIdentityChanges()
    }

    override fun onResume() {
        super.onResume()
        updateToggleText()
        // 水印默认开启：回到界面时若已启用且具备悬浮窗权限，直接拉起服务
        if (isEnabled() && Settings.canDrawOverlays(this)) {
            startWatermark()
        }
    }

    /** 用户名/设备 ID 输入停顿后提交，触发水印内容重建并自动开启。 */
    private fun watchIdentityChanges() {
        etUsername.doAfterTextChanged { scheduleIdentityCommit() }
        etDeviceId.doAfterTextChanged { scheduleIdentityCommit() }
    }

    private fun scheduleIdentityCommit() {
        identityHandler.removeCallbacks(commitIdentity)
        identityHandler.postDelayed(commitIdentity, IDENTITY_COMMIT_DELAY_MS)
    }

    private fun onToggle() {
        if (isEnabled() && Settings.canDrawOverlays(this)) {
            stopWatermark()
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.grant_overlay, Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIFICATIONS
                )
            }
            applyIdentity()
            startWatermark()
        }
        updateToggleText()
    }

    private fun startWatermark() {
        prefs.edit().putBoolean(WatermarkPrefs.KEY_ENABLED, true).apply()
        if (startIssued) return
        startIssued = true
        val intent = Intent(this, WatermarkService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopWatermark() {
        prefs.edit().putBoolean(WatermarkPrefs.KEY_ENABLED, false).apply()
        startIssued = false
        stopService(Intent(this, WatermarkService::class.java))
    }

    private fun isEnabled(): Boolean = WatermarkPrefs.isEnabled(this)

    /** 长按开关：临时把暗水印放大到肉眼可见，用来确认悬浮层是否真的在画。 */
    private fun toggleDebugOverlay() {
        val makeVisible = prefs.getInt(WatermarkPrefs.KEY_DEBUG_ALPHA, 0) == 0
        prefs.edit()
            .putInt(
                WatermarkPrefs.KEY_DEBUG_ALPHA,
                if (makeVisible) LocatedWatermarkCodec.DEBUG_OVERLAY_ALPHA else 0
            )
            .apply()
        if (isEnabled() && Settings.canDrawOverlays(this)) startWatermark()
        Toast.makeText(
            this,
            if (makeVisible) R.string.debug_on else R.string.debug_off,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateToggleText() {
        val label = when {
            !isEnabled() -> R.string.toggle_off
            !Settings.canDrawOverlays(this) -> R.string.toggle_grant
            else -> R.string.toggle_on
        }
        btnToggle.setText(label)
    }

    /** 写入输入框内容；确实发生变化时返回 true。 */
    private fun applyIdentity(): Boolean {
        val userId = etUsername.text.toString().trim()
        val deviceId = etDeviceId.text.toString().trim()
        val savedUserId = prefs.getString(WatermarkPrefs.KEY_USERNAME, "").orEmpty()
        val savedDeviceId = prefs.getString(WatermarkPrefs.KEY_DEVICE_ID, "").orEmpty()
        if (userId == savedUserId && deviceId == savedDeviceId) return false
        prefs.edit()
            .putString(WatermarkPrefs.KEY_USERNAME, userId)
            .putString(WatermarkPrefs.KEY_DEVICE_ID, deviceId)
            .apply()
        return true
    }

    // ---------------------------------------------------------------- 水印解析

    private fun stageTitle(stage: WatermarkParseProgress.Stage): String = getString(when (stage) {
        WatermarkParseProgress.Stage.READ_IMAGE -> R.string.parse_step_read
        WatermarkParseProgress.Stage.DIRECT_READ -> R.string.parse_step_direct
        WatermarkParseProgress.Stage.HEADER_PROBE -> R.string.parse_step_probe
        WatermarkParseProgress.Stage.SPECTRAL_LOCATION -> R.string.parse_step_spectral
        WatermarkParseProgress.Stage.ORIGINAL_SEARCH -> R.string.parse_step_original
        WatermarkParseProgress.Stage.SCALED_SEARCH -> R.string.parse_step_scaled
        WatermarkParseProgress.Stage.ROTATION_SEARCH -> R.string.parse_step_rotation
        WatermarkParseProgress.Stage.SCREEN_COMPENSATION -> R.string.parse_step_screen
    })

    private fun showParseProgress(progress: WatermarkParseProgress) {
        val previous = lastParseProgress
        if (previous != null && (previous.stage != progress.stage ||
                (previous.frameIndex != null && previous.frameIndex != progress.frameIndex))) {
            recordCompletedStep(previous)
        }
        if (previous == null || progress.frameIndex != previous.frameIndex) {
            visitedParseStages.clear()
            skippedParseStages.clear()
            budgetExitedParseStages.clear()
        }
        if (progress.budgetExceeded) {
            budgetExitedParseStages.add(progress.stage)
            recordCompletedStep(progress)
        }
        skippedParseStages.addAll(progress.skippedStages)
        if (progress.stage == WatermarkParseProgress.Stage.SCREEN_COMPENSATION) {
            skippedParseStages.addAll(WatermarkParseProgress.Stage.entries.filter {
                it != progress.stage && it !in visitedParseStages
            })
        }
        skippedParseStages.remove(progress.stage)
        visitedParseStages.add(progress.stage)
        lastParseProgress = progress
        val title = stageTitle(progress.stage)
        val frame = if (progress.frameIndex != null && progress.frameCount != null)
            getString(R.string.processing_video, progress.frameIndex, progress.frameCount) + "\n" else ""
        tvParseStep.visibility = android.view.View.VISIBLE
        val runningCount = if (progress.budgetExceeded) 0 else 1
        val completedCount = visitedParseStages.size - runningCount + skippedParseStages.count { it !in visitedParseStages }
        tvParseStep.text = frame + getString(R.string.parse_step_progress,
            completedCount, WatermarkParseProgress.Stage.entries.size, title)
        val steps = SpannableStringBuilder()
        for (stage in WatermarkParseProgress.Stage.entries) {
            val (status, color) = when {
                stage in budgetExitedParseStages -> R.string.parse_step_budget_exited to R.color.parse_step_budget_exited
                stage == progress.stage -> R.string.parse_step_running to R.color.parse_step_running
                stage in visitedParseStages ->
                    R.string.parse_step_completed to R.color.parse_step_completed
                stage in skippedParseStages -> R.string.parse_step_skipped to R.color.parse_step_skipped
                else -> R.string.parse_step_pending to R.color.parse_step_pending
            }
            if (steps.isNotEmpty()) steps.append('\n')
            val start = steps.length
            steps.append(getString(R.string.parse_step_item, stage.number, stageTitle(stage), getString(status)))
            steps.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, color)),
                start, steps.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tvParseSteps.text = steps
        tvParseSteps.visibility = android.view.View.VISIBLE
        tvResult.text = frame + progress.detail.ifBlank { title }
    }

    private fun recordCompletedStep(progress: WatermarkParseProgress) {
        val last = completedParseSteps.lastOrNull()
        if (last == null || last.stage != progress.stage || last.frameIndex != progress.frameIndex) {
            completedParseSteps.add(progress)
        } else if (progress.budgetExceeded || progress.spectrumChecked) {
            completedParseSteps[completedParseSteps.lastIndex] = progress
        }
    }

    private fun finishParseProgress(cancelled: Boolean = false) {
        // 取消按钮和任务 finally 都可能调用，保留首次生成的角度和执行记录。
        if (lastParseProgress == null) return
        val recordedSteps = completedParseSteps + listOfNotNull(lastParseProgress)
        val rotationFrames = recordedSteps.filter {
            it.stage == WatermarkParseProgress.Stage.ROTATION_SEARCH
        }.map { it.frameIndex }.toSet()
        val angleEstimates = recordedSteps.filter {
            it.stage == WatermarkParseProgress.Stage.SPECTRAL_LOCATION &&
                it.spectrumChecked && it.frameIndex in rotationFrames
        }.distinctBy { it.frameIndex }
        // 正常返回包括认证成功和解析失败；取消时不把尚未结束的当前步骤计入完成记录。
        if (!cancelled) lastParseProgress?.let { recordCompletedStep(it) }
        tvCompletedParseSteps.text = buildString {
            if (angleEstimates.isNotEmpty()) {
                appendLine(getString(R.string.parse_estimated_angles_title))
                angleEstimates.forEach { progress ->
                    val angles = if (progress.estimatedAngles.isEmpty()) {
                        getString(R.string.parse_estimated_angles_empty)
                    } else progress.estimatedAngles.joinToString("、") { String.format(Locale.US, "%.3f°", it) }
                    appendLine(if (progress.frameIndex != null) {
                        getString(R.string.parse_estimated_angles_frame, progress.frameIndex, angles)
                    } else angles)
                }
                appendLine()
            }
            appendLine(getString(R.string.parse_completed_steps_title))
            if (completedParseSteps.isEmpty()) {
                append(getString(R.string.parse_completed_steps_empty))
            } else {
                completedParseSteps.forEachIndexed { index, progress ->
                    if (index > 0) appendLine()
                    val stepTitle = if (progress.frameIndex != null) {
                        getString(R.string.parse_completed_step_frame, progress.frameIndex, stageTitle(progress.stage))
                    } else stageTitle(progress.stage)
                    val title = if (progress.budgetExceeded) {
                        stepTitle + " · " + getString(R.string.parse_step_budget_exited)
                    } else stepTitle
                    append(getString(R.string.parse_completed_step_item, index + 1, title))
                }
            }
        }
        tvCompletedParseSteps.visibility = android.view.View.VISIBLE
        tvParseStep.visibility = android.view.View.GONE
        tvParseSteps.visibility = android.view.View.GONE
        tvParseStep.text = ""
        tvParseSteps.text = ""
        lastParseProgress = null
        visitedParseStages.clear()
        skippedParseStages.clear()
        budgetExitedParseStages.clear()
    }

    /** 使用单调时钟独立刷新计时，不依赖后台算法发送进度的频率。 */
    private fun startParseTimer() {
        parseTimerJob?.cancel()
        completedParseSteps.clear()
        tvCompletedParseSteps.text = ""
        tvCompletedParseSteps.visibility = android.view.View.GONE
        lastParseProgress = null
        visitedParseStages.clear()
        skippedParseStages.clear()
        budgetExitedParseStages.clear()
        val startedAt = SystemClock.elapsedRealtime()
        parseStartedAt = startedAt
        tvParseElapsed.visibility = android.view.View.VISIBLE
        tvParseElapsed.text = getString(R.string.parse_elapsed_running, 0.0)
        parseTimerJob = lifecycleScope.launch {
            while (true) {
                delay(1_000)
                tvParseElapsed.text = getString(R.string.parse_elapsed_running,
                    (SystemClock.elapsedRealtime() - startedAt) / 1_000.0)
            }
        }
    }

    private fun finishParseTimer() {
        val startedAt = parseStartedAt ?: return
        parseTimerJob?.cancel()
        parseTimerJob = null
        parseStartedAt = null
        tvParseElapsed.text = getString(R.string.parse_elapsed_finished,
            (SystemClock.elapsedRealtime() - startedAt) / 1_000.0)
    }

    private fun formatParseResult(result: String): CharSequence {
        val formatted = SpannableStringBuilder(result)
        val identityLines = Regex("(?m)^(?:用户名：|设备 ID：|时间戳：)[^\\r\\n]*")
        identityLines.findAll(result).forEach { match ->
            formatted.setSpan(StyleSpan(Typeface.BOLD), match.range.first,
                match.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return formatted
    }

    private fun parseImage(uri: Uri) {
        parseJob?.cancel()
        startParseTimer()
        showParseProgress(WatermarkParseProgress(WatermarkParseProgress.Stage.READ_IMAGE))
        btnCancelParse.visibility = android.view.View.VISIBLE
        tvResult.setText(R.string.processing)
        parseJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    decodeAndExtract(uri) { progress ->
                        withContext(Dispatchers.Main) { showParseProgress(progress) }
                    }
                }
                tvResult.text = formatParseResult(result)
            } finally {
                if (parseJob == currentCoroutineContext()[Job]) {
                    finishParseTimer()
                    finishParseProgress(cancelled = currentCoroutineContext()[Job]?.isCancelled == true)
                    btnCancelParse.visibility = android.view.View.GONE
                }
            }
        }
    }

    private fun parseVideo(uri: Uri) {
        parseJob?.cancel()
        startParseTimer()
        showParseProgress(WatermarkParseProgress(WatermarkParseProgress.Stage.READ_IMAGE))
        tvResult.setText(R.string.processing)
        btnCancelParse.visibility = android.view.View.VISIBLE
        parseJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    decodeAndExtractVideo(uri) { progress ->
                        withContext(Dispatchers.Main) { showParseProgress(progress) }
                    }
                }
                tvResult.text = formatParseResult(result)
            } finally {
                // 被新任务替换后，旧任务不能修改新任务的按钮状态。
                if (parseJob == currentCoroutineContext()[Job]) {
                    finishParseTimer()
                    finishParseProgress(cancelled = currentCoroutineContext()[Job]?.isCancelled == true)
                    btnCancelParse.visibility = android.view.View.GONE
                }
            }
        }
    }

    /** 短视频约每五秒取一帧，长视频均匀采样；找到首个通过认证的水印后停止。 */
    private suspend fun decodeAndExtractVideo(
        uri: Uri,
        onProgress: suspend (WatermarkParseProgress) -> Unit
    ): String {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.takeIf { it > 0 }
                ?: return "解析失败：无法读取视频时长"
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            if (width.toLong() * height > MAX_DECODE_PIXELS) {
                return "解析失败：视频分辨率过大，请选择较低分辨率的录屏"
            }
            val sampleCount = minOf(
                MAX_VIDEO_FRAMES.toLong(),
                (duration - 1) / VIDEO_FRAME_INTERVAL_MS + 2
            ).toInt()
            // 包含开头与结尾；极短视频去重，避免反复解析同一个位置。
            val positions = (0 until sampleCount).map {
                it.toLong() * (duration - 1) / (sampleCount - 1)
            }.distinct()
            var decodedFrames = 0
            var failedFrames = 0
            var best: LocatedWatermarkCodec.Detection? = null
            var bestScale = 1.0
            val scaleSearch = WatermarkScaleSearch()
            val timings = WatermarkTimings()
            for ((index, position) in positions.withIndex()) {
                currentCoroutineContext().ensureActive()
                suspend fun frameProgress(progress: WatermarkParseProgress) {
                    onProgress(progress.copy(frameIndex = index + 1, frameCount = positions.size))
                }
                frameProgress(WatermarkParseProgress(WatermarkParseProgress.Stage.READ_IMAGE,
                    "正在读取视频帧…"))
                val frame = try {
                    retriever.getFrameAtTime(position * 1_000, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "video frame unavailable at ${position}ms", e)
                    null
                }
                if (frame == null) {
                    failedFrames++
                    continue
                }
                val result = try {
                    val coroutine = currentCoroutineContext()
                    coroutine.ensureActive()
                    if (frame.width.toLong() * frame.height > MAX_DECODE_PIXELS) {
                        failedFrames++
                        continue
                    }
                    decodedFrames++
                    val originalScale = 1.0
                    frameProgress(WatermarkParseProgress(WatermarkParseProgress.Stage.DIRECT_READ))
                    val original = detectDirectScreenWatermark(frame) { coroutine.ensureActive() }
                    if (original.payload != null) {
                        WatermarkScaleSearch.Result(original, originalScale)
                    } else {
                        scaleSearch.detect(frame, original, originalScale,
                            onStage = { frameProgress(it) },
                            knownScaleFallback = {
                                detectScreenSizeCompensation(frame, original) { coroutine.ensureActive() }
                            })
                    }
                } finally {
                    frame.recycle()
                }
                val detection = result.detection
                timings.add(result.timings)
                Log.i(LOG_TAG, "video position=${position}ms scale=${result.scale} " +
                    "status=${detection.status} score=${detection.score}")
                val payload = detection.payload
                if (payload != null) {
                    return buildString {
                        appendLine("录屏解析结果：")
                        appendLine("视频尺寸：$width x $height")
                        appendLine("视频时长：${formatVideoPosition(duration)}")
                        appendLine("抽帧位置（约）：${formatVideoPosition(position)}")
                        appendLine("已解析帧数：$decodedFrames")
                        appendLine("抽帧缩放补偿比例：${formatScale(result.scale)}")
                        appendLine("水印格式：${detection.format}")
                        if (detection.rotationDegrees != 0.0) {
                            appendLine("旋转补偿：顺时针 ${String.format(Locale.US, "%.3f", detection.rotationDegrees)}°")
                        }
                        if (detection.horizontalMirrored) appendLine("水平镜像补偿：已应用")
                        appendLine()
                        appendLine("检测到屏幕暗水印：")
                        appendLine("用户名：${payload.userId.ifEmpty { "（未填写）" }}")
                        appendLine("设备 ID：${payload.deviceId.ifEmpty { "（未填写）" }}")
                        appendLine("时间戳：${formatTimestamp(payload.timestamp)}")
                        appendLine("已找到首个有效水印，停止抽帧。")
                        appendLine("（载荷由本机 Keystore 密钥加密，仅本机可解）")
                        appendWatermarkTimings(timings, video = true)
                    }
                }
                val previous = best
                if (previous == null || detection.status.ordinal > previous.status.ordinal ||
                    (detection.status == previous.status && detection.score > previous.score)
                ) {
                    best = detection
                    bestScale = result.scale
                }
            }
            return buildString {
                appendLine("录屏解析结果：")
                appendLine("视频尺寸：$width x $height")
                appendLine("视频时长：${formatVideoPosition(duration)}")
                appendLine("已解析帧数：$decodedFrames / ${positions.size}")
                if (failedFrames > 0) appendLine("无法读取或尺寸过大的帧：$failedFrames")
                appendLine()
                if (decodedFrames == 0) {
                    appendLine("未能读取可解析的视频帧，请检查视频是否损坏或格式是否受支持。")
                } else {
                    when (best?.status) {
                        LocatedWatermarkCodec.Status.INSUFFICIENT_DATA ->
                            appendLine("抽样帧中检测到疑似水印头部，但数据不足，无法还原身份。")
                        LocatedWatermarkCodec.Status.VALIDATION_FAILED ->
                            appendLine("抽样帧中检测到疑似水印头部，但载荷校验未通过。")
                        else -> appendLine("抽样帧中未定位到可验证的水印。")
                    }
                    appendLine("水印同步相关强度：${formatScore(best?.score ?: 0.0)}")
                    appendLine("最佳候选补偿比例：${formatScale(bestScale)}（未经认证）")
                    appendLine("已对抽样帧按头部和频谱证据分流，并在预算内尝试原尺度、缩放或旋转解析。")
                    appendLine("比例搜索为有限候选搜索，不保证覆盖所有缩放或严重裁剪。")
                    appendLine("录屏压缩、缩放或裁剪可能削弱水印，建议使用原始高画质录屏。")
                    appendLine("结果仅覆盖抽样帧，不能据此判断整段视频不存在水印。")
                }
                appendWatermarkTimings(timings, video = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            return "解析失败：视频帧过大，内存不足"
        } catch (e: Exception) {
            return "录屏解析失败：${e.message ?: "无法读取视频"}"
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(LOG_TAG, "video retriever release failed", e)
            }
        }
    }

    private fun formatVideoPosition(millis: Long): String =
        String.format(Locale.US, "%02d:%02d:%02d.%03d",
            millis / 3_600_000, millis / 60_000 % 60, millis / 1_000 % 60, millis % 1_000)

    private fun formatScale(scale: Double): String = String.format(Locale.US, "%.4f", scale)

    private fun StringBuilder.appendWatermarkTimings(timings: WatermarkTimings?, video: Boolean = false) {
        appendLine()
        appendLine(if (video) "定位阶段耗时（所有已解析帧累计）：" else "定位阶段耗时（所有候选累计）：")
        fun duration(stage: String): String = timings?.milliseconds(stage)?.let {
            String.format(Locale.US, "%.1f ms", it)
        } ?: "未执行"
        appendLine("角度估计：${duration("angle_estimation")}")
        appendLine("原尺度头部预检：${duration("header_probe")}")
        appendLine("原尺度路径：${duration("route_original")}")
        appendLine("缩放路径：${duration("route_scaled")}")
        appendLine("旋转及镜像路径：${duration("route_rotation")}")
        appendLine("八信号联合精定位准备：${duration("joint_signal_prepare")}")
        appendLine("八信号联合拟合：${duration("joint_signal_fit")}")
        appendLine("联合精定位候选解码：${duration("joint_geometry_decode")}")
        appendLine("角度候选头部精校准：${duration("angle_header_refinement")}")
        appendLine("角度候选正文解码及认证：${duration("angle_body_decode")}")
        appendLine("定位总耗时：${duration("locator_total")}")
        appendLine("（总耗时包含上述阶段及其他定位步骤，不含文件读取和默认位置检测）")
    }

    private fun detectDirectScreenWatermark(
        bitmap: Bitmap,
        checkCancelled: () -> Unit
    ): LocatedWatermarkCodec.Detection = LocatedWatermarkCodec.detectDirect(bitmap, checkCancelled)

    /** 按图片原始尺寸解码，避免额外重采样损伤屏幕暗水印。 */
    private fun decodeBitmap(uri: Uri): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    /** 默认位置读取和频谱定位均未认证后，尝试设备显示尺寸补偿。 */
    private fun detectScreenSizeCompensation(
        bitmap: Bitmap,
        original: LocatedWatermarkCodec.Detection,
        checkCancelled: () -> Unit = {}
    ): WatermarkScaleSearch.Result {
        var best = original
        var bestScale = 1.0
        if (best.payload != null) return WatermarkScaleSearch.Result(best, bestScale)
        val metrics = resources.displayMetrics
        val mode = getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
        val scales = mutableListOf<Double>()
        if (mode != null && mode.physicalWidth > 0 && mode.physicalHeight > 0) {
            val sx = metrics.widthPixels.toDouble() / mode.physicalWidth
            val sy = metrics.heightPixels.toDouble() / mode.physicalHeight
            if (kotlin.math.abs(sx - sy) < 0.01) scales.add(sx)
        }
        // 仅纵横比相符时，把“完整截图经过等比缩放”作为额外假设。
        val sx = metrics.widthPixels.toDouble() / bitmap.width
        val sy = metrics.heightPixels.toDouble() / bitmap.height
        if (kotlin.math.abs(sx - sy) / maxOf(sx, sy) < 0.005) scales.add(sx)
        val tried = mutableListOf<Double>()
        for (scale in scales) {
            checkCancelled()
            if (!scale.isFinite() || scale <= 0 || kotlin.math.abs(scale - 1.0) < 0.001 ||
                tried.any { kotlin.math.abs(it - scale) < 0.001 }) continue
            tried.add(scale)
            val width = kotlin.math.round(bitmap.width * scale).toInt()
            val height = kotlin.math.round(bitmap.height * scale).toInt()
            if (width < 64 || height < 64 || width.toLong() * height > MAX_DECODE_PIXELS) continue
            Log.i(LOG_TAG, "uniform resample ${bitmap.width}x${bitmap.height} -> ${width}x$height")
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            val result = try {
                LocatedWatermarkCodec.detectDirectWithMirror(scaled, checkCancelled)
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
            if (result.payload != null) {
                return WatermarkScaleSearch.Result(result, scale)
            }
            if (result.status.ordinal > best.status.ordinal ||
                (result.status == best.status && result.score > best.score)) {
                best = result
                bestScale = scale
            }
        }
        return WatermarkScaleSearch.Result(best, bestScale)
    }
    private suspend fun decodeAndExtract(
        uri: Uri,
        onProgress: suspend (WatermarkParseProgress) -> Unit
    ): String {
        return try {
            val coroutine = currentCoroutineContext()
            coroutine.ensureActive()
            onProgress(WatermarkParseProgress(WatermarkParseProgress.Stage.READ_IMAGE,
                "正在读取待解析图片…"))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val size = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L

            val width = bounds.outWidth
            val height = bounds.outHeight
            val tooLarge = width.toLong() * height > MAX_DECODE_PIXELS
            val displayWidth = resources.displayMetrics.widthPixels
            val displayHeight = resources.displayMetrics.heightPixels
            val resampled = width != displayWidth || height != displayHeight

            // 只解码一次，供屏幕暗水印的直接读取和定位搜索使用。
            val bitmap = if (tooLarge) null else decodeBitmap(uri)
            try {
                coroutine.ensureActive()
                val originalScale = 1.0
                val original = bitmap?.let {
                    onProgress(WatermarkParseProgress(WatermarkParseProgress.Stage.DIRECT_READ))
                    detectDirectScreenWatermark(it) { coroutine.ensureActive() }
                }
                coroutine.ensureActive()
                // 直接读取未通过认证时，再进行屏幕暗水印定位搜索。
                val searched = bitmap != null && original != null &&
                    original.payload == null
                val screenResult = if (searched && bitmap != null && original != null) {
                    WatermarkScaleSearch().detect(bitmap, original, originalScale,
                        onStage = onProgress,
                        knownScaleFallback = {
                            detectScreenSizeCompensation(bitmap, original) { coroutine.ensureActive() }
                        })
                } else {
                    original?.let { WatermarkScaleSearch.Result(it, originalScale) }
                }
                val screenDetection = screenResult?.detection
                val screenPayload = screenDetection?.payload
                val mimeType = bounds.outMimeType.orEmpty()
                Log.i(
                    LOG_TAG,
                    "detect ${width}x$height mime=$mimeType hit=${screenPayload != null} " +
                        "scale=${screenResult?.scale} status=${screenDetection?.status} " +
                        "score=${screenDetection?.score}"
                )

                buildString {
                    appendLine("解析结果：")
                    appendLine("图片尺寸：$width x $height")
                    appendLine("文件大小：${formatSize(size)}")
                    appendLine()
                    when {
                        tooLarge ->
                            appendLine("图片过大，已跳过水印提取（仅显示尺寸信息）。")

                        screenPayload != null -> {
                            appendLine("检测到屏幕暗水印：")
                            appendLine("用户名：${screenPayload.userId.ifEmpty { "（未填写）" }}")
                            appendLine("设备 ID：${screenPayload.deviceId.ifEmpty { "（未填写）" }}")
                            appendLine("时间戳：${formatTimestamp(screenPayload.timestamp)}")
                            appendLine("图片缩放补偿比例：${formatScale(screenResult?.scale ?: originalScale)}")
                            appendLine("水印格式：${screenDetection?.format.orEmpty()}")
                            if (screenDetection != null && screenDetection.rotationDegrees != 0.0) {
                                appendLine("旋转补偿：顺时针 ${String.format(Locale.US, "%.3f", screenDetection.rotationDegrees)}°")
                            }
                            if (screenDetection?.horizontalMirrored == true) appendLine("水平镜像补偿：已应用")
                            appendLine("（载荷由本机 Keystore 密钥加密，仅本机可解）")
                        }

                        else -> {
                            when (screenDetection?.status) {
                                LocatedWatermarkCodec.Status.INSUFFICIENT_DATA -> {
                                    appendLine("检测到疑似水印头部，但截图保留的数据不足，无法还原身份。")
                                    appendLine("头部覆盖：${screenDetection.headerBitsObserved}/32 比特")
                                    if (screenDetection.bodyBitsRequired > 0) appendLine(
                                        "正文覆盖：${screenDetection.bodyBitsObserved}/${screenDetection.bodyBitsRequired} 比特"
                                    )
                                    appendLine("请保留更大的截图区域后重试。")
                                }
                                LocatedWatermarkCodec.Status.VALIDATION_FAILED -> {
                                    appendLine("检测到疑似水印头部，但载荷校验未通过。")
                                    appendLine("可能存在像素损坏、识别误差，或图片使用了其他密钥。")
                                }
                                else -> {
                                    appendLine("未定位到可验证的水印。")
                                    appendLine("裁剪可能移除了定位头部；请尝试保留更大的区域，并确认水印已开启。")
                                }
                            }
                            appendLine("水印同步相关强度：${formatScore(screenDetection?.score ?: 0.0)}")
                            if (searched) {
                                appendLine("最佳候选水印格式：${screenDetection?.format.orEmpty()}")
                                appendLine("最佳候选补偿比例：${formatScale(screenResult?.scale ?: 1.0)}（未经认证）")
                                appendLine("已按头部和频谱证据分流，在预算内尝试候选路径；仍可能因裁剪或信号损伤失败。")
                            }
                            if (resampled) appendLine(
                                "图片尺寸与当前屏幕不同，已优先按原图解析，并尝试适用的等比缩放。"
                            )
                            if (isLossyFormat(mimeType)) appendLine(
                                "该图片格式可能包含有损压缩（$mimeType），建议使用 PNG 原图。"
                            )
                        }
                    }
                    appendWatermarkTimings(screenResult?.timings)
                }
            } finally {
                bitmap?.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            "解析失败：图片过大，内存不足"
        } catch (e: Exception) {
            "解析失败：${e.message}"
        }
    }

    private fun formatTimestamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    private fun formatScore(score: Double): String =
        String.format(Locale.US, "%.2f", score)

    /** 这些格式可能采用有损编码，仅作为建议，不据此判断水印已丢失。 */
    private fun isLossyFormat(mimeType: String): Boolean {
        val type = mimeType.lowercase(Locale.US)
        return type.contains("jpeg") || type.contains("jpg") ||
            type.contains("webp") || type.contains("heic") || type.contains("heif")
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L ->
            String.format(Locale.US, "%.2f MB", bytes / 1024f / 1024f)
        bytes >= 1024L ->
            String.format(Locale.US, "%.1f KB", bytes / 1024f)
        else -> "$bytes B"
    }

    companion object {
        private const val LOG_TAG = "SystemWP"
        private const val REQ_NOTIFICATIONS = 100

        /** 输入停顿多久后提交身份变更，避免每敲一个字就重建水印。 */
        private const val IDENTITY_COMMIT_DELAY_MS = 400L

        /** 超过该像素数跳过整图解码与提取，避免内存不足。 */
        private const val MAX_DECODE_PIXELS = 20_000_000L
        private const val VIDEO_FRAME_INTERVAL_MS = 5_000L
        private const val MAX_VIDEO_FRAMES = 120
    }
}
