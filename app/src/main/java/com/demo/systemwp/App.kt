package com.demo.systemwp

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

/**
 * 共享偏好配置常量，水印服务与界面共用同一份配置。
 */
object WatermarkPrefs {
    const val NAME = "watermark"
    const val KEY_USERNAME = "username"
    const val KEY_DEVICE_ID = "device_id"
    const val KEY_ENABLED = "enabled"

    /** 调试用：覆盖悬浮层 alpha（0 表示用默认隐形值）。 */
    const val KEY_DEBUG_ALPHA = "debug_alpha"

    /** 默认开启：装机后无需手动点击即可生效。 */
    const val DEFAULT_ENABLED = true

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
}

class App : Application()
