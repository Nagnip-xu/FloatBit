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
    private const val BASE = "${ContractMarket.REST}/klines"
    private val client = OkHttpClient()
    private val main = Handler(Looper.getMainLooper())
    private val cache = HashMap<String, Pair<Long, List<Double>>>()

    /** 异步获取收盘价序列; 结果回调在主线程 */
    fun getCloses(symbol: String, onResult: (List<Double>) -> Unit) {
        cache[symbol]?.let { (time, values) ->
            if (android.os.SystemClock.elapsedRealtime() - time < 60_000) { onResult(values); return }
        }
        val url = "$BASE?symbol=$symbol&interval=15m&limit=30"
        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { main.post { onResult(emptyList()) } }
            override fun onResponse(call: Call, response: Response) {
                val closes = try { response.use {
                    if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                    val body = it.body?.string() ?: throw IOException("Empty response")
                    val arr = JSONArray(body)
                    val closes = ArrayList<Double>(arr.length())
                    for (i in 0 until arr.length()) {
                        closes.add(arr.getJSONArray(i).getString(4).toDouble())
                    }
                    closes.toList()
                } } catch (_: Exception) { emptyList() }
                main.post {
                    if (closes.isNotEmpty()) cache[symbol] = android.os.SystemClock.elapsedRealtime() to closes
                    onResult(closes)
                }
            }
        })
    }

    fun clear(symbol: String) = cache.remove(symbol)
}
