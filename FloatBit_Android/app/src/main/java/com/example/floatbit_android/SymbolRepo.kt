package com.example.floatbit_android

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/** 交易对信息 */
data class SymbolInfo(val symbol: String, val base: String, val quote: String)

/** 获取币安全部交易对 (用于搜索)。带内存缓存。 */
object SymbolRepo {
    private const val URL = "https://data-api.binance.vision/api/v3/exchangeInfo"
    private val client = OkHttpClient()
    private val main = Handler(Looper.getMainLooper())
    private var cache: List<SymbolInfo> = emptyList()

    /** 异步加载全部交易对; 已缓存则直接回调 */
    fun load(onResult: (List<SymbolInfo>) -> Unit) {
        if (cache.isNotEmpty()) { onResult(cache); return }
        client.newCall(Request.Builder().url(URL).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string() ?: return
                    val arr = JSONObject(body).getJSONArray("symbols")
                    val list = ArrayList<SymbolInfo>(arr.length())
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        if (o.optString("status") != "TRADING") continue
                        list.add(
                            SymbolInfo(
                                o.getString("symbol"),
                                o.getString("baseAsset"),
                                o.getString("quoteAsset")
                            )
                        )
                    }
                    cache = list
                    main.post { onResult(list) }
                } catch (_: Exception) {
                }
            }
        })
    }

    /** 按关键词搜索, USDT 优先, USDC 次之 */
    fun search(keyword: String, limit: Int = 20): List<SymbolInfo> {
        val kw = keyword.trim().uppercase()
        if (kw.isEmpty()) return emptyList()
        return cache.asSequence()
            .filter { it.symbol.contains(kw) || it.base.contains(kw) }
            .sortedWith(compareBy({ quoteRank(it.quote) }, { it.symbol }))
            .take(limit)
            .toList()
    }

    private fun quoteRank(q: String) = when (q) {
        "USDT" -> 0
        "USDC" -> 1
        else -> 2
    }
}
