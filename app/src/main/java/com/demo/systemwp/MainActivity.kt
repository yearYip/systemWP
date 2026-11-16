package com.demo.systemwp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
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

    private var startIssued = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = WatermarkPrefs.prefs(this)

        etUsername = findViewById(R.id.et_username)
        etDeviceId = findViewById(R.id.et_device_id)
        btnToggle = findViewById(R.id.btn_toggle)
        tvResult = findViewById(R.id.tv_result)

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
                if (makeVisible) ScreenWatermarkCodec.DEBUG_OVERLAY_ALPHA else 0
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

    private fun parseImage(uri: Uri) {
        tvResult.setText(R.string.processing)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { decodeAndExtract(uri) }
            tvResult.text = result
        }
    }

    /** 解码整图（不缩放），因为盲水印的 LSB 位置依赖原始尺寸。 */
    private fun decodeBitmap(uri: Uri): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    /** 原图优先；缩放比例来自显示参数或完整截图比例，不能把裁剪尺寸当作屏幕尺寸。 */
    private fun detectScreenWatermark(bitmap: Bitmap): ScreenWatermarkCodec.Detection {
        var best = ScreenWatermarkCodec.detect(bitmap)
        if (best.payload != null) return best
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
            if (!scale.isFinite() || scale <= 0 || kotlin.math.abs(scale - 1.0) < 0.001 ||
                tried.any { kotlin.math.abs(it - scale) < 0.001 }) continue
            tried.add(scale)
            val width = kotlin.math.round(bitmap.width * scale).toInt()
            val height = kotlin.math.round(bitmap.height * scale).toInt()
            if (width < 64 || height < 64 || width.toLong() * height > MAX_DECODE_PIXELS) continue
            Log.i(LOG_TAG, "uniform resample ${bitmap.width}x${bitmap.height} -> ${width}x$height")
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            val result = try {
                ScreenWatermarkCodec.detect(scaled)
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
            if (result.payload != null) return result
            if (result.status.ordinal > best.status.ordinal ||
                (result.status == best.status && result.score > best.score)) best = result
        }
        return best
    }
    private fun decodeAndExtract(uri: Uri): String {
        return try {
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

            // 只解码一次，两种水印共用：屏幕暗水印（截屏）与 LSB 盲水印（图片文件）
            val bitmap = if (tooLarge) null else decodeBitmap(uri)
            val screenDetection = bitmap?.let { detectScreenWatermark(it) }
            val screenPayload = screenDetection?.payload
            val lsbPayload = bitmap?.let { WatermarkCodec.extract(it) }
            val mimeType = bounds.outMimeType.orEmpty()
            Log.i(
                LOG_TAG,
                "detect ${width}x$height mime=$mimeType hit=${screenPayload != null} " +
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
                        appendLine("（载荷由本机 Keystore 密钥加密，仅本机可解）")
                    }

                    lsbPayload != null -> {
                        val (u, d) = parsePayload(lsbPayload)
                        appendLine("检测到图片盲水印：")
                        appendLine("用户名：$u")
                        appendLine("设备 ID：$d")
                    }

                    else -> {
                        when (screenDetection?.status) {
                            ScreenWatermarkCodec.Status.INSUFFICIENT_DATA -> {
                                appendLine("检测到疑似水印头部，但截图保留的数据不足，无法还原身份。")
                                appendLine("头部覆盖：${screenDetection.headerBitsObserved}/32 比特")
                                if (screenDetection.bodyBitsRequired > 0) appendLine(
                                    "正文覆盖：${screenDetection.bodyBitsObserved}/${screenDetection.bodyBitsRequired} 比特"
                                )
                                appendLine("请保留更大的截图区域后重试。")
                            }
                            ScreenWatermarkCodec.Status.VALIDATION_FAILED -> {
                                appendLine("检测到疑似水印头部，但载荷校验未通过。")
                                appendLine("可能存在像素损坏、识别误差，或图片使用了其他密钥。")
                            }
                            else -> {
                                appendLine("未定位到可验证的水印。")
                                appendLine("裁剪可能移除了定位头部；请尝试保留更大的区域，并确认水印已开启。")
                            }
                        }
                        appendLine("水印同步相关强度：${formatScore(screenDetection?.score ?: 0.0)}")
                        if (resampled) appendLine(
                            "图片尺寸与当前屏幕不同，已优先按原图解析，并尝试适用的等比缩放。"
                        )
                        if (isLossyFormat(mimeType)) appendLine(
                            "该图片格式可能包含有损压缩（$mimeType），建议使用 PNG 原图。"
                        )
                    }
                }
            }
        } catch (_: OutOfMemoryError) {
            "解析失败：图片过大，内存不足"
        } catch (e: Exception) {
            "解析失败：${e.message}"
        }
    }

    private fun parsePayload(payload: String): Pair<String, String> {
        val lines = payload.lines()
        return (lines.getOrNull(0).orEmpty()) to (lines.getOrNull(1).orEmpty())
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

        /** 超过该像素数不整图解码，避免 OOM（盲水印提取要求原尺寸，故仅提示）。 */
        private const val MAX_DECODE_PIXELS = 20_000_000L
    }
}
