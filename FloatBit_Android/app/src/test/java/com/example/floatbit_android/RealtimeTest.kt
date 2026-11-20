package com.example.floatbit_android

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class RealtimeTest {
    @Test fun tradeFirstShowsPriceWithoutWaitingForStatisticsAndLateTickerCannotRollItBack() {
        val trade = MarketPrices.live(null, JSONObject("""{"s":"MUUSDT","e":"aggTrade","p":"110","T":200}"""), 500)!!
        assertEquals(110.0, trade.last, 0.0)
        assertEquals("", trade.formatChange())
        val value = MarketPrices.live(trade, JSONObject("""{"s":"MUUSDT","e":"24hrTicker","c":"100","C":100,"o":"100"}"""), 600)!!
        assertEquals(110.0, value.last, 0.0)
        assertEquals(200L, value.eventTime)
        assertEquals(10.0, value.changePct, 0.0001)
    }

    @Test fun slowSnapshotOnlyFillsStatisticsAfterLivePriceArrives() {
        val trade = Ticker("MUUSDT", 110.0, Double.NaN, eventTime = 200, receivedAt = 500, source = "live")
        val snapshot = Ticker("MUUSDT", 100.0, 0.0, eventTime = 100, open = 100.0, receivedAt = 600)
        val value = MarketPrices.snapshot(trade, snapshot, 400)
        assertEquals(110.0, value.last, 0.0)
        assertEquals(10.0, value.changePct, 0.0001)
        assertEquals("live", value.source)
        assertEquals(value, MarketPrices.snapshot(value, Ticker("MUUSDT", Double.NaN, Double.NaN), 700))
    }

    @Test fun alternateWinsByActualMarketDataWhilePrimaryHandshakeStaysSilent() {
        val primary = MockWebServer()
        val alternate = MockWebServer()
        val client = MarketNetwork.createClient(null)
        primary.start(); alternate.start()
        primary.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        alternate.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"result":null}""")
                webSocket.send("""{"data":{"s":"MUUSDT","e":"aggTrade","p":"110","T":200}}""")
            }
        }))
        val result = CompletableFuture<JSONObject>()
        val stream = ContractStream({ client }, { result.complete(it) }, endpoints = listOf(
            primary.url("/market/stream").toString(), alternate.url("/market/stream").toString()))
        try {
            stream.start(listOf("MUUSDT"))
            assertEquals("110", result.get(3, TimeUnit.SECONDS).getString("p"))
            val path = alternate.takeRequest(1, TimeUnit.SECONDS)!!.path!!
            assertTrue(path.contains("muusdt@aggTrade"))
            assertTrue(path.contains("muusdt@ticker"))
            assertEquals(1, primary.requestCount)
        } finally {
            stream.close()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            primary.shutdown(); alternate.shutdown()
        }
    }

    @Test fun fallbackDoesNotWaitForThePrimaryHttpTimeout() {
        val primary = MockWebServer()
        val alternate = MockWebServer()
        val client = OkHttpClient.Builder().callTimeout(4, TimeUnit.SECONDS).build()
        primary.start(); alternate.start()
        primary.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        alternate.enqueue(MockResponse().setBody("{\"lastPrice\":\"110\"}"))
        val result = CompletableFuture<Result<String>>()
        try {
            ContractHttp(client, primary.url("/fapi/v1"), { alternate.url("/fapi/v1") })
                .get("ticker/24hr?symbol=MUUSDT") { result.complete(it) }
            assertTrue(result.get(2, TimeUnit.SECONDS).getOrThrow().contains("110"))
            assertEquals("fapi.binance.com", alternate.takeRequest().getHeader("Host"))
        } finally {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            primary.shutdown(); alternate.shutdown()
        }
    }
}
