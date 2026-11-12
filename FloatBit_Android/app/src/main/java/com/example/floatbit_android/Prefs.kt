package com.example.floatbit_android

import android.content.Context

/** 轻量配置存储: 自选币种 + 涨跌配色 + 主题 + 悬浮窗透明度 */
object Prefs {
    private const val FILE = "floatbit"
    private const val KEY_SYMBOLS = "symbols"
    private const val KEY_RED_UP = "red_up"
    private const val KEY_DARK = "dark"
    private const val KEY_OPACITY = "opacity"

    private val DEFAULT_SYMBOLS = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT")

    fun getSymbols(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY_SYMBOLS, null)
        if (raw.isNullOrBlank()) return DEFAULT_SYMBOLS
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setSymbols(ctx: Context, symbols: List<String>) {
        prefs(ctx).edit().putString(KEY_SYMBOLS, symbols.joinToString(",")).apply()
    }

    /** true = 红涨绿跌(中国习惯); false = 绿涨红跌(默认) */
    fun isRedUp(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_RED_UP, false)
    fun setRedUp(ctx: Context, redUp: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_RED_UP, redUp).apply()
    }

    /** true = 暗色(默认); false = 亮色 */
    fun isDark(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_DARK, true)
    fun setDark(ctx: Context, dark: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_DARK, dark).apply()
    }

    /** 悬浮窗不透明度 0..100, 默认 95 */
    fun getOpacity(ctx: Context): Int = prefs(ctx).getInt(KEY_OPACITY, 95)
    fun setOpacity(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt(KEY_OPACITY, v).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
