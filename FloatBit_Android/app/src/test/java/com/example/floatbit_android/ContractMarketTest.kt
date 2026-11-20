package com.example.floatbit_android

import org.junit.Assert.*
import org.junit.Test

class ContractMarketTest {
    private val symbols = listOf("MUBARAK", "MU", "SNDK", "SKHYNIX", "CSOPSKHYNIX2L", "NVDA").map {
        SymbolInfo("${it}USDT", it, "USDT")
    }

    @Test fun exactStockCodePrecedesPartialMatches() {
        for (coin in listOf("MU", "SNDK", "SKHYNIX")) {
            assertEquals(coin, ContractMarket.search(symbols, coin, 8).first().base)
        }
        assertEquals("MUUSDT", ContractMarket.search(symbols, " mu / usdt ", 8).first().symbol)
        assertTrue(ContractMarket.search(symbols, "NVDAB", 8).isEmpty())
    }

    @Test fun quoteParsingKeepsLongNameAndUsesContractEndpoints() {
        assertEquals("SKHYNIX", Ticker("SKHYNIXUSDT", 830.91, -2.435).coin)
        assertEquals("NVDAB", Ticker("NVDABUSDT", Double.NaN, Double.NaN).coin)
        assertEquals("USDT", ContractMarket.quote("SNDKUSDT"))
        assertEquals("https://fapi.binance.com/fapi/v1", ContractMarket.REST)
        assertEquals("wss://fstream.binance.com/market/stream", ContractMarket.WS)
    }

    @Test fun proxySettingsRejectUrlsAndInvalidPorts() {
        for ((host, port) in listOf("" to 7890, "http://host" to 7890, "a/b" to 7890,
            "a b" to 7890, "host" to 0, "host" to 65536)) {
            assertTrue(runCatching { HttpProxySettings(host, port) }.isFailure)
        }
        assertEquals("192.168.1.100", HttpProxySettings("192.168.1.100", 7890).host)
    }
}
