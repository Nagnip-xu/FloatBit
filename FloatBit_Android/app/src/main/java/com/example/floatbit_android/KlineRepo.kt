package com.example.floatbit_android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray

/** 合约 K 线收盘价；合并同币种请求，保留一分钟缓存。 */
object KlineRepo {
    private val main = Handler(Looper.getMainLooper())
    private val cache = HashMap<String, Pair<Long, List<Double>>>()
    private val calls = HashMap<String, MarketRequest>()
    private val pending = HashMap<String, MutableList<(List<Double>) -> Unit>>()
    private var generation = 0

    fun resetNetwork() {
        generation++
        calls.values.forEach { it.cancel() }
        calls.clear()
        pending.clear()
        cache.clear()
    }

    fun getCloses(symbol: String, onResult: (List<Double>) -> Unit) {
        cache[symbol]?.let { (time, values) ->
            if (SystemClock.elapsedRealtime() - time < 60_000) { onResult(values); return }
        }
        pending[symbol]?.let { it.add(onResult); return }
        pending[symbol] = mutableListOf(onResult)
        val gen = generation
        calls[symbol] = ContractHttp(MarketNetwork.client)
            .get("klines?symbol=$symbol&interval=15m&limit=30") { result ->
                val closes = runCatching {
                    val arr = JSONArray(result.getOrThrow())
                    List(arr.length()) { arr.getJSONArray(it).getString(4).toDouble() }
                        .also { require(it.all(Double::isFinite)) }
                }.getOrDefault(emptyList())
                main.post {
                    if (generation != gen) return@post
                    if (closes.isNotEmpty()) cache[symbol] = SystemClock.elapsedRealtime() to closes
                    calls.remove(symbol)
                    pending.remove(symbol)?.forEach { it(closes) }
                }
            }
    }

    fun clear(symbol: String) = cache.remove(symbol)
}
