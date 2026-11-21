package com.example.floatbit_android

import android.os.Handler
import android.os.SystemClock

/** 主线程显示调度：各合约首值立即显示，后续每秒只取当前最新值。 */
class TickerPublisher(
    private val main: Handler,
    private val onUpdate: (Ticker) -> Unit
) {
    private class Slot(var latest: Ticker, var publishedAt: Long, var task: Runnable? = null)
    private val slots = HashMap<String, Slot>()

    fun submit(ticker: Ticker) {
        val now = SystemClock.uptimeMillis()
        val slot = slots[ticker.symbol]
        if (slot == null) {
            slots[ticker.symbol] = Slot(ticker, now)
            onUpdate(ticker)
            return
        }
        slot.latest = ticker
        if (slot.task != null) return
        val remaining = 1000L - (now - slot.publishedAt)
        if (remaining <= 0) {
            slot.publishedAt = now
            onUpdate(ticker)
        } else {
            val task = Runnable {
                slot.task = null
                slot.publishedAt = SystemClock.uptimeMillis()
                onUpdate(slot.latest)
            }
            slot.task = task
            main.postDelayed(task, remaining)
        }
    }

    fun reset() {
        slots.values.forEach { it.task?.let(main::removeCallbacks) }
        slots.clear()
    }
}
