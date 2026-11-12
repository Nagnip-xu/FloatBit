package com.example.floatbit_android

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 币安 WebSocket 实时行情客户端 (与桌面版同源, 行情专用域名)。
 * - 订阅多个交易对的 @ticker 流
 * - 自动重连
 * - 通过 onUpdate 回调把最新行情抛到主线程
 */
class MarketClient(
    private var symbols: List<String>,
    private val onUpdate: (Ticker) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val main = Handler(Looper.getMainLooper())
    private var closedByUser = false
    private var reconnecting = false

    companion object {
        private const val WS_BASE = "wss://data-stream.binance.vision/stream"
    }

    fun start() {
        closedByUser = false
        connect()
    }

    fun stop() {
        closedByUser = true
        ws?.close(1000, "bye")
        ws = null
    }

    /** 更新订阅的交易对并重连 */
    fun updateSymbols(newSymbols: List<String>) {
        symbols = newSymbols
        ws?.close(1000, "resubscribe")
        ws = null
        connect()
    }

    private fun streamsParam(): String =
        symbols.joinToString("/") { "${it.lowercase()}@ticker" }

    private fun connect() {
        if (symbols.isEmpty()) return
        val url = "$WS_BASE?streams=${streamsParam()}"
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val data = JSONObject(text).optJSONObject("data") ?: return
                    if (data.optString("e") != "24hrTicker") return
                    val ticker = Ticker(
                        symbol = data.getString("s"),
                        last = data.getString("c").toDouble(),
                        changePct = data.getString("P").toDouble()
                    )
                    main.post { onUpdate(ticker) }
                } catch (_: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closedByUser) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (closedByUser || reconnecting) return
        reconnecting = true
        main.postDelayed({
            reconnecting = false
            connect()
        }, 3000)
    }
}
