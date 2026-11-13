package com.demo.systemwp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

private const val TAG = "SystemWP"

class WatermarkService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addOverlay()
        observeIdentityChanges()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        prefsListener?.let {
            WatermarkPrefs.prefs(this).unregisterOnSharedPreferenceChangeListener(it)
        }
        prefsListener = null
        removeOverlay()
        super.onDestroy()
    }

    /** 任意水印相关配置变化都重绘，[WatermarkView] 会据新身份重建 tile。 */
    private fun observeIdentityChanges() {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            overlayView?.invalidate()
        }
        prefsListener = listener
        WatermarkPrefs.prefs(this).registerOnSharedPreferenceChangeListener(listener)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.watermark_running))
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun addOverlay() {
        if (overlayView != null) return
        val view = WatermarkView(this)
        val type = windowType()
        // 系统窗口类型失败时（ROM 未授予或被策略拦截）回退到普通悬浮窗
        val added = tryAddView(view, type) ||
            (type == TYPE_INTERNAL_SYSTEM_WINDOW &&
                tryAddView(view, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY))
        Log.i(TAG, "addOverlay type=$type added=$added")
        if (added) overlayView = view
    }

    private fun tryAddView(view: View, type: Int): Boolean {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        return try {
            windowManager?.addView(view, params)
            true
        } catch (e: Exception) {
            Log.w(TAG, "addView(type=$type) failed", e)
            false
        }
    }

    private fun removeOverlay() {
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
    }

    /**
     * 优先使用系统窗口类型（platform 签名 + priv-app 才生效），
     * 否则降级为普通悬浮窗，保证普通安装也能运行。
     */
    private fun windowType(): Int {
        val hasInternal = checkSelfPermission(
            "android.permission.INTERNAL_SYSTEM_WINDOW"
        ) == PackageManager.PERMISSION_GRANTED
        return if (hasInternal) {
            TYPE_INTERNAL_SYSTEM_WINDOW
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
    }

    /**
     * 全屏隐形暗水印：由 [ScreenWatermarkCodec] 生成的 4×4 棋盘纹理平铺而成。
     * 幅度约 [ScreenWatermarkCodec.OVERLAY_ALPHA] 个灰阶且小块内局部均值为零，
     * 正常观看距离下不可见，但屏幕像素里真实存在，截屏后可被本应用相关检测还原。
     *
     * 用户名/设备 ID 变化时在下一帧重建 tile，因此界面改完立即生效。
     */
    private class WatermarkView(context: Context) : View(context) {

        private val paint = Paint().apply {
            isAntiAlias = false
            isFilterBitmap = false
            isDither = false
            alpha = ScreenWatermarkCodec.OVERLAY_ALPHA
        }

        private var shader: BitmapShader? = null
        private var identity: String? = null

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val current = currentShader() ?: return
            paint.alpha = currentAlpha()
            paint.shader = current
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }

        /** 默认极低幅度；长按开关可临时切到可见模式，便于确认悬浮层是否真的画上去了。 */
        private fun currentAlpha(): Int {
            val debug = WatermarkPrefs.prefs(context)
                .getInt(WatermarkPrefs.KEY_DEBUG_ALPHA, 0)
            return if (debug > 0) debug else ScreenWatermarkCodec.OVERLAY_ALPHA
        }

        /** 身份未变则复用上一次的 tile，避免每帧做加密与位图生成。 */
        private fun currentShader(): BitmapShader? {
            val prefs = WatermarkPrefs.prefs(context)
            val userId = prefs.getString(WatermarkPrefs.KEY_USERNAME, "").orEmpty()
            val deviceId = prefs.getString(WatermarkPrefs.KEY_DEVICE_ID, "").orEmpty()
            val next = "$userId\u0000$deviceId"
            if (next == identity) return shader

            // 构建失败时不缓存身份，下一帧继续重试
            val built = build(userId, deviceId) ?: return null
            identity = next
            shader = built
            return built
        }

        private fun build(userId: String, deviceId: String): BitmapShader? {
            return try {
                val payload = WatermarkPayload(userId, deviceId, System.currentTimeMillis())
                val plain = payload.encode()
                val sealed = WatermarkCrypto.seal(plain)
                if (sealed == null) {
                    Log.w(TAG, "tile skipped: Keystore seal failed")
                    return null
                }
                val tile = ScreenWatermarkCodec.buildTile(sealed)
                if (tile == null) {
                    Log.w(TAG, "tile skipped: buildTile failed, sealed=${sealed.size}B")
                    return null
                }
                Log.i(TAG, "tile ready: plain=${plain.size}B sealed=${sealed.size}B")
                ScreenWatermarkCodec.createShader(tile)
            } catch (e: Exception) {
                Log.w(TAG, "tile build failed", e)
                null
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "watermark"
        private const val NOTIFICATION_ID = 1

        /**
         * WindowManager.LayoutParams.TYPE_INTERNAL_SYSTEM_WINDOW 的数值。
         * 该常量在 SDK 中是 @hide，只能以字面量引用；
         * 未持有 INTERNAL_SYSTEM_WINDOW 权限时添加窗口会抛 BadTokenException。
         */
        private const val TYPE_INTERNAL_SYSTEM_WINDOW = 2021
    }
}
