package com.example.floatbit_android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/** 合约列表每分钟更新；失败时保留旧列表，始终回调，支持手动重试。 */
object SymbolRepo {
    private val client = OkHttpClient()
    private val main = Handler(Looper.getMainLooper())
    private var cache: List<SymbolInfo> = emptyList()
    private var updatedAt = 0L
    private var attemptedAt = 0L
    private val callbacks = ArrayList<(List<SymbolInfo>) -> Unit>()
    var loading = false
        private set
    var error: String? = null
        private set

    fun load(force: Boolean = false, onResult: (List<SymbolInfo>) -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (!force && (cache.isNotEmpty() && now - updatedAt < 60_000 ||
                error != null && now - attemptedAt < 5_000)) {
            onResult(cache)
            return
        }
        callbacks.add(onResult)
        if (loading) return
        loading = true
        attemptedAt = now
        client.newCall(Request.Builder().url("${ContractMarket.REST}/exchangeInfo").build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = finish(null)

                override fun onResponse(call: Call, response: Response) {
                    val list = try {
                        response.use {
                            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                            val arr = JSONObject(it.body?.string() ?: "").getJSONArray("symbols")
                            buildList {
                                for (i in 0 until arr.length()) {
                                    val o = arr.getJSONObject(i)
                                    if (o.optString("status") == "TRADING") add(SymbolInfo(
                                        o.getString("symbol"), o.getString("baseAsset"), o.getString("quoteAsset")
                                    ))
                                }
                            }.also { if (it.isEmpty()) throw IOException("Empty contract list") }
                        }
                    } catch (_: Exception) { null }
                    finish(list)
                }
            })
    }

    private fun finish(list: List<SymbolInfo>?) {
        main.post {
            if (list != null) {
                cache = list
                updatedAt = SystemClock.elapsedRealtime()
                error = null
            } else error = "合约列表更新失败，请检查网络并点击刷新"
            loading = false
            val pending = callbacks.toList()
            callbacks.clear()
            pending.forEach { it(cache) }
        }
    }

    fun search(keyword: String, limit: Int = 20): List<SymbolInfo> =
        ContractMarket.search(cache, keyword, limit)
}
