package com.example.floatbit_android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** 成交实时推送驱动价格，REST 仅提供首值或断线补救，界面最多每 50ms 合并一次。 */
class MarketClient(
    private var symbols: List<String>,
    private val onState: (String) -> Unit = {},
    private val onUpdate: (Ticker) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var stream: ContractStream? = null
    private var stopped = true
    private var state = "connecting"
    private var generation = 0
    private var activeNetwork = MarketNetwork.client
    private val values = HashMap<String, Ticker>()
    private val pending = HashMap<String, Ticker>()
    private var scheduled = false
    private val inFlight = HashMap<String, MarketRequest>()
    private val flush = Runnable {
        scheduled = false
        val batch = pending.values.toList()
        pending.clear()
        if (!stopped) batch.forEach(onUpdate)
    }
    private val poll = object : Runnable {
        override fun run() {
            if (stopped) return
            snapshot(generation, initial = false)
            main.postDelayed(this, 5000)
        }
    }
    companion object {
        // 只复用本进程内缓存；界面会标记缓存，成功连接后立即替换。
        private val recent = ConcurrentHashMap<String, Ticker>()
    }
    fun start() {
        if (!stopped) {
            if (activeNetwork !== MarketNetwork.client) refresh()
            return
        }
        stopped = false
        activeNetwork = MarketNetwork.client
        val gen = ++generation
        symbols.forEach { symbol -> recent[symbol]?.let {
            values[symbol] = it.copy(source = "cached")
            publish(values.getValue(symbol))
        } }
        stream = ContractStream({ activeNetwork }, { data ->
            main.post {
                if (!stopped && gen == generation && data.optString("s") in symbols) {
                    val symbol = data.getString("s")
                    MarketPrices.live(values[symbol], data, SystemClock.elapsedRealtime())?.let(::publish)
                }
            }
        }, { value -> main.post {
            if (!stopped && gen == generation) { state = value; onState(value) }
        } }).also { it.start(symbols) }
        snapshot(gen, initial = true)
        main.postDelayed(poll, 5000)
    }
    fun refresh() { stop(); start() }
    fun stop() {
        stopped = true
        generation++
        main.removeCallbacksAndMessages(null)
        scheduled = false
        pending.clear()
        stream?.close()
        stream = null
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
    }
    fun updateSymbols(newSymbols: List<String>) {
        val changed = symbols.toSet() != newSymbols.toSet()
        symbols = newSymbols.distinct()
        values.keys.retainAll(symbols.toSet())
        if (changed) refresh() else {
            start()
            symbols.forEach { values[it]?.let(::publish) }
        }
    }
    private fun snapshot(gen: Int, initial: Boolean) {
        val requestedAt = SystemClock.elapsedRealtime()
        val http = ContractHttp(activeNetwork)
        symbols.filter { it !in inFlight && (initial || state != "live" ||
            requestedAt - (values[it]?.receivedAt ?: 0L) > 15000) }.forEach { symbol ->
            inFlight[symbol] = http.get("ticker/24hr?symbol=$symbol") { result ->
                val ticker = try {
                    val data = JSONObject(result.getOrThrow())
                    val last = data.getString("lastPrice").toDouble()
                    val change = data.getString("priceChangePercent").toDouble()
                    require(last.isFinite() && last > 0 && change.isFinite())
                    Ticker(symbol, last, change, eventTime = data.optLong("closeTime"),
                        open = data.optDouble("openPrice"), receivedAt = SystemClock.elapsedRealtime(), source = "snapshot")
                } catch (e: Exception) { Ticker(symbol, Double.NaN, Double.NaN, MarketErrors.label(e)) }
                main.post {
                    if (!stopped && generation == gen && symbol in symbols) {
                        inFlight.remove(symbol)
                        publish(MarketPrices.snapshot(values[symbol], ticker, requestedAt))
                    }
                }
            }
        }
    }
    private fun publish(ticker: Ticker) {
        if (stopped || ticker.symbol !in symbols) return
        values[ticker.symbol] = ticker
        if (ticker.last.isFinite()) recent[ticker.symbol] = ticker
        pending[ticker.symbol] = ticker
        if (!scheduled) { scheduled = true; main.postDelayed(flush, 50) }
    }
}
