package com.example.floatbit_android

import org.json.JSONObject

/** 用交易所成交时间排序，防止较慢的 ticker/REST 将最新成交价倒退。 */
object MarketPrices {
    fun live(previous: Ticker?, d: JSONObject, now: Long): Ticker? {
        val trade = d.optString("e") == "aggTrade"
        val price = d.optDouble(if (trade) "p" else "c")
        val eventTime = d.optLong(if (trade) "T" else "C", d.optLong("E"))
        if (!(price > 0) || eventTime <= 0) return previous
        val newest = previous == null || eventTime >= previous.eventTime
        val last = if (newest) price else previous!!.last
        val open = if (trade) previous?.open ?: Double.NaN else d.optDouble("o")
        val change = if (open > 0) (last - open) / open * 100 else previous?.changePct ?: Double.NaN
        return Ticker(d.getString("s"), last, change, eventTime = if (newest) eventTime else previous!!.eventTime,
            open = open, receivedAt = now, source = "live")
    }
    fun snapshot(previous: Ticker?, value: Ticker, requestedAt: Long): Ticker {
        if (previous != null && previous.last.isFinite() && (value.last.isNaN() ||
                (previous.source == "live" && previous.receivedAt >= requestedAt) || previous.eventTime > value.eventTime)) {
            val open = if (previous.open.isFinite()) previous.open else value.open
            return previous.copy(open = open, changePct = if (open > 0) (previous.last - open) / open * 100 else previous.changePct)
        }
        return value
    }
}
