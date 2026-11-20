package com.example.floatbit_android

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** 并行尝试币安合约通道，以首条有效行情选路；所有状态只在串行队列修改。 */
class ContractStream(
    private val client: () -> OkHttpClient,
    private val onEvent: (JSONObject) -> Unit,
    private val onState: (String) -> Unit = {},
    private val endpoints: List<String> = listOf(
        "wss://fstream.binance.com/market/stream", "wss://fstream.binance.me/market/stream")
) {
    private val queue = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "contract-stream").apply { isDaemon = true }
    }
    private var generation = 0
    private var stopped = true
    private var symbols = emptyList<String>()
    private val candidates = HashSet<WebSocket>()
    private var winner: WebSocket? = null
    private var deadline: ScheduledFuture<*>? = null
    private var retry: ScheduledFuture<*>? = null
    private var failures = 0
    private var state = ""
    private fun dispatch(action: () -> Unit) {
        try { queue.execute(action) } catch (_: RejectedExecutionException) { }
    }
    private fun state(value: String) { if (state != value) { state = value; onState(value) } }
    fun start(newSymbols: List<String>) = dispatch {
        reset()
        symbols = newSymbols.distinct()
        stopped = false
        failures = 0
        connect()
    }
    fun close() {
        dispatch { stopped = true; reset(); queue.shutdown() }
    }
    private fun reset() {
        generation++
        deadline?.cancel(false)
        retry?.cancel(false)
        candidates.forEach { it.cancel() }
        candidates.clear()
        winner = null
    }
    private fun connect() {
        if (stopped || symbols.isEmpty()) return
        val gen = ++generation
        state("connecting")
        val streams = symbols.flatMap { s ->
            listOf("${s.lowercase(Locale.ROOT)}@aggTrade", "${s.lowercase(Locale.ROOT)}@ticker")
        }.joinToString("/")
        deadline = queue.schedule({ reconnect(gen) }, 6500, TimeUnit.MILLISECONDS)
        endpoints.forEach { endpoint ->
            val socket = client().newWebSocket(Request.Builder().url("$endpoint?streams=$streams").build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val data = runCatching { JSONObject(text).optJSONObject("data") }.getOrNull() ?: return
                        val type = data.optString("e")
                        if (type !in listOf("aggTrade", "24hrTicker") ||
                            !(data.optDouble(if (type == "aggTrade") "p" else "c") > 0)) return
                        dispatch {
                            if (stopped || gen != generation || data.optString("s") !in symbols ||
                                (winner != null && winner !== webSocket)) return@dispatch
                            if (winner == null) {
                                winner = webSocket
                                failures = 0
                                deadline?.cancel(false)
                                candidates.filter { it !== webSocket }.forEach { it.cancel() }
                                candidates.clear()
                                candidates.add(webSocket)
                            }
                            state("live")
                            onEvent(data)
                        }
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = failed(gen, webSocket)
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = failed(gen, webSocket)
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.cancel()
                        failed(gen, webSocket)
                    }
                })
            candidates.add(socket)
        }
    }
    private fun failed(gen: Int, socket: WebSocket) = dispatch {
        if (stopped || gen != generation) return@dispatch
        candidates.remove(socket)
        if (winner === socket || candidates.isEmpty()) reconnect(gen)
    }
    private fun reconnect(gen: Int) {
        if (stopped || gen != generation) return
        reset()
        state("reconnecting")
        val delay = minOf(500L * (1L shl minOf(failures++, 4)), 8000L)
        retry = queue.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
    }
}
