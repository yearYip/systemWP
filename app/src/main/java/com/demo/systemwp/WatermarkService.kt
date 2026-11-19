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
import android.os.SystemClock
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
        // TODO(system-watermark)：此处预留系统水印接口的连接/创建入口。
        // 以下仅为伪代码，接口、方法名及传输格式须以系统方提供的协议为准：
        // systemWatermarkSession = systemWatermarkApi.open(displayId)
        // 系统侧按显示器管理全屏层；覆盖锁屏、不接收输入、捕获画面包含水印等由系统保证。
        // 正式接入时选择系统层或现有悬浮窗，避免两者同时显示导致水印重复叠加。
        // 若不再创建 WatermarkView，需将其每分钟刷新及身份变化处理迁到 Service，
        // 不能继续依赖 View 的 onDraw/onAttachedToWindow 触发生成和提交。
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
        // TODO(system-watermark)：停止水印时隐藏/释放系统侧会话（伪代码）：
        // systemWatermarkSession?.close()
        // 多屏会话均需释放；服务断连后的恢复策略与资源归属须按系统协议实现。
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
     * 优先尝试系统窗口类型，以实际获授的 INTERNAL_SYSTEM_WINDOW 权限为准；
     * 仅放入 priv-app 不保证取得该权限。
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
     * 全屏暗水印：由 [LocatedWatermarkCodec] 生成正文棋盘及多方向频谱定位模板。
     * 继续复用低透明度 shader 平铺，不在每个显示帧执行编码或定位计算。
     *
     * 用户名/设备 ID 变化时在下一帧重建 tile；身份未变时每分钟更新时间戳。
     */
    private class WatermarkView(context: Context) : View(context) {

        private val paint = Paint().apply {
            isAntiAlias = false
            isFilterBitmap = false
            isDither = false
            alpha = LocatedWatermarkCodec.OVERLAY_ALPHA
        }

        private var shader: BitmapShader? = null
        private var identity: String? = null
        private var lastBuiltAt = 0L
        private val refreshWatermark = object : Runnable {
            override fun run() {
                invalidate()
                postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            invalidate()
            postDelayed(refreshWatermark, REFRESH_INTERVAL_MS)
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(refreshWatermark)
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val current = currentShader() ?: return
            paint.alpha = currentAlpha()
            // TODO(system-watermark)：调试透明度变化时也要更新系统层，不应等待下一分钟。
            // systemWatermarkSession.setAlpha(currentAlpha()) // 伪代码；按变化更新，避免逐帧调用。
            paint.shader = current
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }

        /** 默认极低幅度；长按开关可临时切到可见模式，便于确认悬浮层是否真的画上去了。 */
        private fun currentAlpha(): Int {
            val debug = WatermarkPrefs.prefs(context)
                .getInt(WatermarkPrefs.KEY_DEBUG_ALPHA, 0)
            return if (debug > 0) debug else LocatedWatermarkCodec.OVERLAY_ALPHA
        }

        /** 身份未变且未满一分钟时复用 tile；使用单调时钟判断刷新间隔。 */
        private fun currentShader(): BitmapShader? {
            val prefs = WatermarkPrefs.prefs(context)
            val userId = prefs.getString(WatermarkPrefs.KEY_USERNAME, "").orEmpty()
            val deviceId = prefs.getString(WatermarkPrefs.KEY_DEVICE_ID, "").orEmpty()
            val next = "$userId\u0000$deviceId"
            if (next == identity &&
                SystemClock.elapsedRealtime() - lastBuiltAt < REFRESH_INTERVAL_MS
            ) return shader

            // 构建失败时不缓存身份，下一帧继续重试
            val built = build(userId, deviceId) ?: return null
            identity = next
            shader = built
            lastBuiltAt = SystemClock.elapsedRealtime()
            removeCallbacks(refreshWatermark)
            postDelayed(refreshWatermark, REFRESH_INTERVAL_MS)
            return built
        }

        private fun build(userId: String, deviceId: String): BitmapShader? {
            return try {
                val startedAt = SystemClock.elapsedRealtime()
                val payload = WatermarkPayload(userId, deviceId, System.currentTimeMillis())
                val plain = payload.encode()
                val sealed = WatermarkCrypto.seal(plain)
                if (sealed == null) {
                    Log.w(TAG, "tile skipped: Keystore seal failed")
                    return null
                }
                val tile = LocatedWatermarkCodec.buildTile(sealed)
                if (tile == null) {
                    Log.w(TAG, "tile skipped: buildTile failed, sealed=${sealed.size}B")
                    return null
                }
                // TODO(system-watermark)：W2 纹理已生成，在这里预留提交位置（伪代码）：
                // systemWatermarkSession.submit(tile, alpha = currentAlpha(),
                //     repeat = true, originX = 0, originY = 0)
                // 传递原始 672×672 纹理，按目标显示器像素 1:1 平铺；透明度只应用一次。
                // Bitmap/Surface/共享缓冲区等传输形式待定；异步提交完成前不得提前回收纹理。
                // 提交成功才更新系统侧状态；失败如何重试或回落到悬浮窗需显式约定。
                // 系统服务重连、显示器新增或配置变化后，需为相应会话重新提交有效纹理。
                Log.i(TAG, "tile ready: format=W2 plain=${plain.size}B sealed=${sealed.size}B " +
                    "buildMs=${SystemClock.elapsedRealtime() - startedAt}")
                LocatedWatermarkCodec.createShader(tile)
            } catch (e: Exception) {
                Log.w(TAG, "tile build failed", e)
                null
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "watermark"
        private const val NOTIFICATION_ID = 1
        private const val REFRESH_INTERVAL_MS = 60_000L

        /**
         * 历史变量名保留：2021 在 AOSP 中实际对应隐藏类型 TYPE_BOOT_PROGRESS，
         * 不是名为 TYPE_INTERNAL_SYSTEM_WINDOW 的 SDK 窗口类型。
         * INTERNAL_SYSTEM_WINDOW 是权限名称；后续系统专用水印接口不应依赖该历史类型。
         */
        private const val TYPE_INTERNAL_SYSTEM_WINDOW = 2021
    }
}
