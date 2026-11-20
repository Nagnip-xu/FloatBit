package com.example.floatbit_android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.Call
import org.json.JSONObject

/** 合约列表每分钟更新；失败保留旧列表，手动刷新立即重试。 */
object SymbolRepo {
    private val main = Handler(Looper.getMainLooper())
    private var cache: List<SymbolInfo> = emptyList()
    private var updatedAt = 0L
    private var attemptedAt = 0L
    private var generation = 0
    private var call: Call? = null
    private val callbacks = ArrayList<(List<SymbolInfo>) -> Unit>()
    var loading = false
        private set
    var error: String? = null
        private set

    fun resetNetwork() {
        generation++
        call?.cancel()
        call = null
        callbacks.clear()
        loading = false
        updatedAt = 0L
        attemptedAt = 0L
        error = null
    }

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
        val gen = generation
        call = ContractHttp(MarketNetwork.client).get("exchangeInfo") { response ->
            val result = runCatching {
                val arr = JSONObject(response.getOrThrow()).getJSONArray("symbols")
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        if (o.optString("status") == "TRADING") add(SymbolInfo(
                            o.getString("symbol"), o.getString("baseAsset"), o.getString("quoteAsset")
                        ))
                    }
                }.also { require(it.isNotEmpty()) { "Empty contract list" } }
            }
            main.post {
                if (gen != generation) return@post
                result.onSuccess {
                    cache = it
                    updatedAt = SystemClock.elapsedRealtime()
                    error = null
                }.onFailure { error = MarketErrors.detail(it) }
                loading = false
                call = null
                val pending = callbacks.toList()
                callbacks.clear()
                pending.forEach { it(cache) }
            }
        }
    }

    fun search(keyword: String, limit: Int = 20): List<SymbolInfo> =
        ContractMarket.search(cache, keyword, limit)
}
