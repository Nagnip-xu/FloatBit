package com.example.floatbit_android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 合约 WebSocket + 启动 REST 快照；断线时每 15 秒用 REST 兜底。 */
class MarketClient(
    private var symbols: List<String>,
    private val onUpdate: (Ticker) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var stopped = true
    private var connected = false
    private var generation = 0
    private var reconnecting = false
    private val received = HashSet<String>()
    private val liveTimes = HashMap<String, Long>()
    private val poll = object : Runnable {
        override fun run() {
            if (stopped) return
            if (!connected || symbols.any { SystemClock.elapsedRealtime() - (liveTimes[it] ?: 0L) > 15_000 }) {
                snapshot(generation)
            }
            main.postDelayed(this, 15_000)
        }
    }

    fun start() {
        stop()
        stopped = false
        received.clear()
        liveTimes.clear()
        connect(generation)
        snapshot(generation)
        main.postDelayed(poll, 15_000)
    }

    fun stop() {
        stopped = true
        connected = false
        reconnecting = false
        generation++
        main.removeCallbacksAndMessages(null)
        ws?.cancel()
        ws = null
        client.dispatcher.cancelAll()
    }

    fun updateSymbols(newSymbols: List<String>) {
        symbols = newSymbols.toList()
        start()
    }

    private fun snapshot(gen: Int) {
        val requestedAt = SystemClock.elapsedRealtime()
        symbols.forEach { symbol ->
            requestTicker(symbol, gen, requestedAt, 0, null)
        }
    }

    /** 逐个尝试合约 REST 备用域名，避免手机 DNS/路由只失败一个入口时整行报错。 */
    private fun requestTicker(
        symbol: String,
        gen: Int,
        requestedAt: Long,
        hostIndex: Int,
        lastFailure: Throwable?
    ) {
        val urls = ContractMarket.restUrls("ticker/24hr?symbol=$symbol")
        if (hostIndex >= urls.size) {
            publish(
                Ticker(symbol, Double.NaN, Double.NaN, networkError(lastFailure)),
                gen,
                snapshotAt = requestedAt
            )
            return
        }
        val request = Request.Builder().url(urls[hostIndex]).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                requestTicker(symbol, gen, requestedAt, hostIndex + 1, e)
            }

            override fun onResponse(call: Call, response: Response) {
                var ticker: Ticker? = null
                var retry = false
                var failure: Throwable? = null
                response.use {
                    val body = it.body?.string().orEmpty()
                    val data = try { JSONObject(body) } catch (_: Exception) { null }
                    if (!it.isSuccessful) {
                        val code = data?.optInt("code") ?: 0
                        if (code == -1121) {
                            ticker = Ticker(symbol, Double.NaN, Double.NaN, "无此合约")
                        } else {
                            failure = IOException("HTTP ${it.code}")
                            retry = hostIndex + 1 < urls.size
                        }
                    } else {
                        try {
                            ticker = Ticker(
                                symbol,
                                data?.getString("lastPrice")?.toDouble()
                                    ?: throw IOException("missing lastPrice"),
                                data?.getString("priceChangePercent")?.toDouble()
                                    ?: throw IOException("missing priceChangePercent")
                            )
                        } catch (e: Exception) {
                            failure = e
                            retry = hostIndex + 1 < urls.size
                        }
                    }
                }
                if (retry) {
                    requestTicker(symbol, gen, requestedAt, hostIndex + 1, failure)
                } else {
                    publish(
                        ticker ?: Ticker(
                            symbol,
                            Double.NaN,
                            Double.NaN,
                            if (failure is IOException && failure?.message?.startsWith("HTTP") == true) {
                                "行情加载失败 (${failure?.message})"
                            } else {
                                "行情数据异常"
                            }
                        ),
                        gen,
                        snapshotAt = requestedAt
                    )
                }
            }
        })
    }

    private fun networkError(error: Throwable?): String = when (error) {
        is UnknownHostException -> "无法解析行情域名"
        is SocketTimeoutException -> "行情连接超时"
        is SSLException -> "行情安全连接失败"
        else -> "行情网络错误"
    }

    private fun publish(ticker: Ticker, gen: Int, snapshotAt: Long? = null) {
        main.post {
            if (stopped || generation != gen || !symbols.contains(ticker.symbol)) return@post
            if (ticker.last.isNaN() && ticker.symbol in received) return@post
            if (snapshotAt != null && (liveTimes[ticker.symbol] ?: 0L) > snapshotAt) return@post
            if (snapshotAt == null && !ticker.last.isNaN()) liveTimes[ticker.symbol] = SystemClock.elapsedRealtime()
            if (!ticker.last.isNaN()) received.add(ticker.symbol)
            onUpdate(ticker)
        }
    }

    private fun connect(gen: Int) {
        connect(gen, 0)
    }

    private fun connect(gen: Int, hostIndex: Int) {
        if (stopped || generation != gen || symbols.isEmpty()) return
        val streams = symbols.joinToString("/") { "${it.lowercase(Locale.ROOT)}@ticker" }
        val hosts = ContractMarket.WS_HOSTS
        val url = "${hosts[hostIndex % hosts.size]}?streams=$streams"
        ws = client.newWebSocket(Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    main.post { if (!stopped && generation == gen && ws === webSocket) connected = true }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val data = JSONObject(text).optJSONObject("data") ?: return
                        if (data.optString("e") != "24hrTicker") return
                        publish(Ticker(data.getString("s"), data.getString("c").toDouble(),
                            data.getString("P").toDouble()), gen)
                    } catch (_: Exception) { }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    scheduleReconnect(gen, webSocket, hostIndex)

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                    scheduleReconnect(gen, webSocket, hostIndex)
            })
    }

    private fun scheduleReconnect(gen: Int, socket: WebSocket, hostIndex: Int) {
        main.post {
            if (stopped || generation != gen || ws !== socket) return@post
            connected = false
            if (reconnecting) return@post
            reconnecting = true
            main.postDelayed({
                reconnecting = false
                if (!stopped && generation == gen) {
                    connect(gen, (hostIndex + 1) % ContractMarket.WS_HOSTS.size)
                }
            }, 3_000)
        }
    }
}
