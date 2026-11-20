package com.example.floatbit_android

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 合约 WebSocket + 启动 REST 快照；断线时每 15 秒用 REST 兜底。 */
class MarketClient(
    private var symbols: List<String>,
    private val onUpdate: (Ticker) -> Unit
) {
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var stopped = true
    private var connected = false
    private var generation = 0
    private var reconnecting = false
    private val received = HashSet<String>()
    private val poll = object : Runnable {
        override fun run() {
            if (stopped) return
            if (!connected) snapshot(generation)
            main.postDelayed(this, 15_000)
        }
    }

    fun start() {
        stop()
        stopped = false
        received.clear()
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
        symbols.forEach { symbol ->
            val request = Request.Builder().url("${ContractMarket.REST}/ticker/24hr?symbol=$symbol").build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    publish(Ticker(symbol, Double.NaN, Double.NaN, "网络错误"), gen)
                }

                override fun onResponse(call: Call, response: Response) {
                    val ticker = try {
                        response.use {
                            val data = JSONObject(it.body?.string() ?: "")
                            if (!it.isSuccessful) {
                                Ticker(symbol, Double.NaN, Double.NaN,
                                    if (data.optInt("code") == -1121) "无此合约" else "行情加载失败")
                            } else Ticker(symbol, data.getString("lastPrice").toDouble(),
                                data.getString("priceChangePercent").toDouble())
                        }
                    } catch (_: Exception) { Ticker(symbol, Double.NaN, Double.NaN, "行情加载失败") }
                    publish(ticker, gen, snapshot = true)
                }
            })
        }
    }

    private fun publish(ticker: Ticker, gen: Int, snapshot: Boolean = false) {
        main.post {
            if (stopped || generation != gen || !symbols.contains(ticker.symbol)) return@post
            if (ticker.symbol in received && (ticker.last.isNaN() || snapshot && connected)) return@post
            if (!ticker.last.isNaN()) received.add(ticker.symbol)
            onUpdate(ticker)
        }
    }

    private fun connect(gen: Int) {
        if (stopped || generation != gen || symbols.isEmpty()) return
        val streams = symbols.joinToString("/") { "${it.lowercase(Locale.ROOT)}@ticker" }
        ws = client.newWebSocket(Request.Builder().url("${ContractMarket.WS}?streams=$streams").build(),
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
                    scheduleReconnect(gen, webSocket)

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                    scheduleReconnect(gen, webSocket)
            })
    }

    private fun scheduleReconnect(gen: Int, socket: WebSocket) {
        main.post {
            if (stopped || generation != gen || ws !== socket) return@post
            connected = false
            if (reconnecting) return@post
            reconnecting = true
            main.postDelayed({
                reconnecting = false
                if (!stopped && generation == gen) connect(gen)
            }, 3_000)
        }
    }
}
