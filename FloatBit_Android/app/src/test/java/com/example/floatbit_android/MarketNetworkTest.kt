package com.example.floatbit_android

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MarketNetworkTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before fun setup() {
        server = MockWebServer()
        server.start()
        client = MarketNetwork.createClient(null)
    }

    @After fun cleanup() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        server.shutdown()
    }

    @Test fun allRestResourcesKeepTheContractPath() {
        val http = ContractHttp(client, server.url("/fapi/v1"))
        for (resource in listOf("exchangeInfo", "ticker/24hr?symbol=MUUSDT",
            "klines?symbol=SKHYNIXUSDT&interval=15m&limit=30")) {
            server.enqueue(MockResponse().setBody("{}"))
            val done = CompletableFuture<Result<String>>()
            http.get(resource) { done.complete(it) }
            assertEquals("{}", done.get(3, TimeUnit.SECONDS).getOrThrow())
            assertEquals("/fapi/v1/$resource", server.takeRequest().path)
        }
    }

    @Test fun invalidSymbolAndRateLimitAreReportedWithoutRetrying() {
        val http = ContractHttp(client, server.url("/fapi/v1"))
        for ((status, body, label) in listOf(
            Triple(400, "{\"code\":-1121,\"msg\":\"Invalid symbol.\"}", "无此合约"),
            Triple(429, "{\"code\":-1003}", "请求限流")
        )) {
            server.enqueue(MockResponse().setResponseCode(status).setBody(body))
            val done = CompletableFuture<Result<String>>()
            http.get("ticker/24hr?symbol=MUUSDT") { done.complete(it) }
            val error = done.get(3, TimeUnit.SECONDS).exceptionOrNull()!!
            assertEquals(label, MarketErrors.label(error))
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun bodyReadFailuresReachTheCallbackWithinTheCallTimeout() {
        client = client.newBuilder().callTimeout(250, TimeUnit.MILLISECONDS).build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val done = CompletableFuture<Result<String>>()
        ContractHttp(client, server.url("/fapi/v1")).get("exchangeInfo") { done.complete(it) }
        assertEquals("连接超时", MarketErrors.label(done.get(3, TimeUnit.SECONDS).exceptionOrNull()!!))
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationDoesNotPublishErrorsOrStartMoreRequests() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val callback = CountDownLatch(1)
        val call = ContractHttp(client, server.url("/fapi/v1")).get("exchangeInfo") { callback.countDown() }
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        call.cancel()
        assertFalse(callback.await(300, TimeUnit.MILLISECONDS))
        assertEquals(1, server.requestCount)
    }

    @Test fun theConfiguredProxyCarriesRestAndWebSocketRequests() {
        client = MarketNetwork.createClient(HttpProxySettings(server.hostName, server.port))
        server.enqueue(MockResponse().setBody("{\"lastPrice\":\"100\"}"))
        val rest = CompletableFuture<Result<String>>()
        // Mock proxy uses HTTP/WS; production constants below require HTTPS/WSS.
        ContractHttp(client, "http://fapi.binance.com/fapi/v1".toHttpUrl())
            .get("ticker/24hr?symbol=MUUSDT") { rest.complete(it) }
        assertTrue(rest.get(3, TimeUnit.SECONDS).isSuccess)
        assertTrue(server.takeRequest().requestLine.startsWith("GET http://fapi.binance.com/fapi/v1/"))

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("ticker") }
        }))
        val message = CompletableFuture<String>()
        val ws = client.newWebSocket(Request.Builder()
            .url("ws://fstream.binance.com/market/stream?streams=muusdt@ticker").build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { message.complete(text) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    message.completeExceptionally(t)
                }
            })
        try {
            assertEquals("ticker", message.get(3, TimeUnit.SECONDS))
            assertTrue(server.takeRequest().requestLine.contains("fstream.binance.com/market/stream"))
            assertTrue(ContractMarket.REST.startsWith("https://"))
            assertTrue(ContractMarket.WS.startsWith("wss://"))
        } finally { ws.cancel() }
    }
}
