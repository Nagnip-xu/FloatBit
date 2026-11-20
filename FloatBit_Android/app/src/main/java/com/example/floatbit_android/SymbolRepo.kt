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
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 合约列表每分钟更新；失败时保留旧列表，始终回调，支持手动重试。 */
object SymbolRepo {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
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
        requestExchangeInfo(0, null)
    }

    private fun requestExchangeInfo(hostIndex: Int, lastFailure: Throwable?) {
        val urls = ContractMarket.restUrls("exchangeInfo")
        if (hostIndex >= urls.size) {
            finish(null, networkError(lastFailure))
            return
        }
        client.newCall(Request.Builder().url(urls[hostIndex]).build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    requestExchangeInfo(hostIndex + 1, e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
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
                    } catch (e: Exception) {
                        if (hostIndex + 1 < urls.size) {
                            requestExchangeInfo(hostIndex + 1, e)
                            return
                        }
                        finish(null, if (e.message?.startsWith("HTTP") == true) {
                            "合约列表请求失败（${e.message}）"
                        } else {
                            networkError(e)
                        })
                        return
                    }
                    finish(result, null)
                }
            })
    }

    private fun networkError(error: Throwable?): String = when (error) {
        is UnknownHostException -> "合约列表域名无法解析，请检查网络"
        is SocketTimeoutException -> "合约列表连接超时，请检查网络"
        is SSLException -> "合约列表安全连接失败"
        else -> "合约列表更新失败，请检查网络并点击刷新"
    }

    private fun finish(list: List<SymbolInfo>?, failure: String?) {
        main.post {
            if (list != null) {
                cache = list
                updatedAt = SystemClock.elapsedRealtime()
                error = null
            } else error = failure ?: "合约列表更新失败，请检查网络并点击刷新"
            loading = false
            val pending = callbacks.toList()
            callbacks.clear()
            pending.forEach { it(cache) }
        }
    }

    fun search(keyword: String, limit: Int = 20): List<SymbolInfo> =
        ContractMarket.search(cache, keyword, limit)
}
