package com.example.floatbit_android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale

/** 合约 WebSocket + REST 快照；无推送时每 15 秒兜底，每个合约最多一个在途请求。 */
class MarketClient(
    private var symbols: List<String>,
    private val onUpdate: (Ticker) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var stopped = true
    private var connected = false
    private var generation = 0
    private var reconnecting = false
    private val received = HashSet<String>()
    private val liveTimes = HashMap<String, Long>()
    private val inFlight = HashMap<String, Call>()
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
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
    }

    fun updateSymbols(newSymbols: List<String>) {
        symbols = newSymbols.toList()
        start()
    }

    private fun snapshot(gen: Int) {
        val requestedAt = SystemClock.elapsedRealtime()
        val http = ContractHttp(MarketNetwork.client)
        symbols.filterNot { it in inFlight }.forEach { symbol ->
            inFlight[symbol] = http.get("ticker/24hr?symbol=$symbol") { result ->
                val ticker = try {
                    val data = JSONObject(result.getOrThrow())
                    val last = data.getString("lastPrice").toDouble()
                    val change = data.getString("priceChangePercent").toDouble()
                    require(last.isFinite() && change.isFinite())
                    Ticker(symbol, last, change)
                } catch (e: Exception) {
                    Ticker(symbol, Double.NaN, Double.NaN, MarketErrors.label(e))
                }
                main.post {
                    if (!stopped && generation == gen) {
                        inFlight.remove(symbol)
                        publish(ticker, gen, requestedAt)
                    }
                }
            }
        }
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
        if (stopped || generation != gen || symbols.isEmpty()) return
        val streams = symbols.joinToString("/") { "${it.lowercase(Locale.ROOT)}@ticker" }
        ws = MarketNetwork.client.newWebSocket(
            Request.Builder().url("${ContractMarket.WS}?streams=$streams").build(),
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
