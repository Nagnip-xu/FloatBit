package com.example.floatbit_android

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException

/** 通过 REST 获取 K线 收盘价序列 (用于走势图)。轻量, 带内存缓存。 */
object KlineRepo {
    private const val BASE = "https://data-api.binance.vision/api/v3/klines"
    private val client = OkHttpClient()
    private val main = Handler(Looper.getMainLooper())
    private val cache = HashMap<String, List<Double>>()

    /** 异步获取收盘价序列; 结果回调在主线程 */
    fun getCloses(symbol: String, onResult: (List<Double>) -> Unit) {
        cache[symbol]?.let { onResult(it); return }
        val url = "$BASE?symbol=$symbol&interval=15m&limit=30"
        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string() ?: return
                    val arr = JSONArray(body)
                    val closes = ArrayList<Double>(arr.length())
                    for (i in 0 until arr.length()) {
                        closes.add(arr.getJSONArray(i).getString(4).toDouble())
                    }
                    cache[symbol] = closes
                    main.post { onResult(closes) }
                } catch (_: Exception) {
                }
            }
        })
    }

    fun clear(symbol: String) = cache.remove(symbol)
}
