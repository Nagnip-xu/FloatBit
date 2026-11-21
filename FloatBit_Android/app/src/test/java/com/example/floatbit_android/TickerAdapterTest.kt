package com.example.floatbit_android

import android.app.Application
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TickerAdapterTest {
    @Test fun identicalDisplayedNumbersDoNotNotifyTheListButChangedPriceDoes() {
        val adapter = TickerAdapter(false) {}
        adapter.setSymbols(listOf("MUUSDT"))
        var changes = 0
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) { changes++ }
        })
        val initial = Ticker("MUUSDT", 608.121, 1.234, source = "live")
        adapter.updateTicker(initial)
        adapter.updateTicker(initial.copy(last = 608.122, receivedAt = 500, eventTime = 1000))
        assertEquals(1, changes)
        adapter.updateTicker(initial.copy(last = 609.12))
        assertEquals(2, changes)
    }

    @Test fun cachedToLiveTransitionStillNotifiesEvenIfThePriceIsUnchanged() {
        val adapter = TickerAdapter(false) {}
        adapter.setSymbols(listOf("MUUSDT"))
        var changes = 0
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) { changes++ }
        })
        val initial = Ticker("MUUSDT", 608.12, 1.23, source = "cached")
        adapter.updateTicker(initial)
        adapter.updateTicker(initial.copy(source = "live"))
        assertEquals(2, changes)
    }
}
