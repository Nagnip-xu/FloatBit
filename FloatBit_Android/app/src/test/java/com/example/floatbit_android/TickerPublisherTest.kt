package com.example.floatbit_android

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class TickerPublisherTest {
    private val deliveries = mutableListOf<Pair<Long, Ticker>>()
    private val publisher = TickerPublisher(Handler(Looper.getMainLooper())) {
        deliveries.add(SystemClock.uptimeMillis() to it)
    }
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun price(last: Double, symbol: String = "MUUSDT") =
        Ticker(symbol, last, 1.0, source = "live")

    @Test fun eachSymbolGetsItsFirstPriceImmediatelyAndNoIdleRefreshes() {
        publisher.submit(price(100.0))
        advance(100)
        publisher.submit(price(200.0, "SNDKUSDT"))
        assertEquals(listOf("MUUSDT", "SNDKUSDT"), deliveries.map { it.second.symbol })
        advance(2000)
        assertEquals(2, deliveries.size)
    }

    @Test fun burstShowsLatestPriceOncePerSecondWithoutReplayingIntermediateTrades() {
        val start = SystemClock.uptimeMillis()
        publisher.submit(price(100.0))
        for (i in 1..36) {
            advance(60)
            publisher.submit(price(100.0 + i))
        }
        assertEquals(listOf(0L, 1000L, 2000L), deliveries.map { it.first - start })
        assertEquals(listOf(100.0, 116.0, 133.0), deliveries.map { it.second.last })
        advance(840)
        assertEquals(136.0, deliveries.last().second.last, 0.0)
        advance(2000)
        assertEquals(4, deliveries.size)
    }

    @Test fun sparseTradeAfterTheIntervalIsImmediate() {
        publisher.submit(price(100.0))
        advance(1500)
        publisher.submit(price(101.0))
        assertEquals(101.0, deliveries.last().second.last, 0.0)
        assertEquals(SystemClock.uptimeMillis(), deliveries.last().first)
    }

    @Test fun resetCancelsPendingOldPricesAndLetsNewSessionStartImmediately() {
        publisher.submit(price(100.0).copy(source = "cached"))
        advance(100)
        publisher.submit(price(101.0))
        publisher.reset()
        publisher.submit(price(200.0))
        advance(2000)
        assertEquals(listOf(100.0, 200.0), deliveries.map { it.second.last })
    }
}
