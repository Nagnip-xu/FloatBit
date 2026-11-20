package com.example.floatbit_android

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 仅在主域名连接失败时，查询其当前 CNAME，直连币安使用的 CloudFront 分发。
 * 不写死分发地址；保留 Host: fapi.binance.com，且仍验证 CDN 的 HTTPS 证书。
 * DNS 服务只收到域名，不接收币种、价格查询或账户信息。
 */
object BinanceCdnRoute {
    @Volatile private var cached: Pair<HttpUrl, Long>? = null
    @Volatile private var preferred = false
    private var retryAfter = 0L

    fun preferred(): HttpUrl? =
        cached?.takeIf { preferred && it.second > System.currentTimeMillis() }?.first

    @Synchronized fun resolve(client: OkHttpClient): HttpUrl? {
        val now = System.currentTimeMillis()
        cached?.takeIf { it.second > now }?.let { return it.first }
        if (now < retryAfter) return null
        retryAfter = now + 15_000
        val answer = runCatching {
            val dnsClient = client.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
            val request = Request.Builder()
                .url("https://doh.pub/dns-query?name=fapi.binance.com&type=CNAME")
                .header("Accept", "application/dns-json").build()
            dnsClient.newCall(request).execute().use {
                if (!it.isSuccessful) return@use null
                parseAnswer(it.body?.string().orEmpty())
            }
        }.getOrNull() ?: return null
        cached = answer.first to (now + answer.second * 1000L)
        return answer.first
    }

    @Synchronized fun succeeded(host: String) {
        if (cached?.first?.host == host) preferred = true
    }

    @Synchronized fun invalidate(host: String) {
        if (cached?.first?.host == host) {
            cached = null
            preferred = false
        }
    }

    internal fun parseAnswer(body: String): Pair<HttpUrl, Int>? {
        val json = JSONObject(body)
        if (json.optInt("Status", -1) != 0) return null
        val answers = json.optJSONArray("Answer") ?: return null
        for (i in 0 until answers.length()) {
            val answer = answers.getJSONObject(i)
            if (answer.optInt("type") != 5 ||
                answer.optString("name").trimEnd('.') != "fapi.binance.com") continue
            val host = answer.optString("data").trimEnd('.')
            if (!Regex("[a-z0-9]+\\.cloudfront\\.net").matches(host)) continue
            return "https://$host/fapi/v1".toHttpUrl() to answer.optInt("TTL", 60).coerceIn(30, 300)
        }
        return null
    }
}
