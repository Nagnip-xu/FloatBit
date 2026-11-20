package com.example.floatbit_android

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONTokener
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

data class HttpProxySettings(val host: String, val port: Int) {
    init {
        require(host.isNotBlank() && Regex("[A-Za-z0-9.\\-:_]+" ).matches(host) && host.count { it == ':' } != 1) {
            "代理地址只填写主机名或 IP，不包含 http:// 或路径"
        }
        require(port in 1..65535) { "代理端口应在 1–65535 之间" }
    }
}

/** REST、K 线和 WebSocket 共用网络配置；HTTPS 仍校验币安证书。 */
object MarketNetwork {
    private var settings: HttpProxySettings? = null
    @Volatile var client: OkHttpClient = createClient(null)
        private set

    @Synchronized fun configure(proxy: HttpProxySettings?) {
        if (settings == proxy) return
        settings = proxy
        client = createClient(proxy)
    }

    fun createClient(proxy: HttpProxySettings?): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .apply {
            // 留空时使用 Android 系统网络/VPN 和系统代理；显式代理同时用于 HTTPS 与 WSS。
            if (proxy != null) proxy(Proxy(Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(proxy.host, proxy.port)))
        }
        .build()
}

class MarketHttpException(val status: Int, val apiCode: Int?) : IOException("HTTP $status")

/** 一组最多两次连接尝试；取消会终止当前连接，并阻止后续备用请求。 */
class MarketRequest {
    @Volatile var canceled = false
        private set
    private var active: Call? = null

    @Synchronized fun cancel() {
        canceled = true
        active?.cancel()
    }

    @Synchronized internal fun attach(call: Call): Boolean {
        if (canceled) { call.cancel(); return false }
        active = call
        return true
    }
}

/** 主接口不可达时自动直连其官方 CDN；拒绝限流/无此合约等错误的无效重试。 */
class ContractHttp(
    private val client: OkHttpClient,
    private val base: HttpUrl = ContractMarket.REST.toHttpUrl(),
    private val fallback: (() -> HttpUrl?)? = if (base == ContractMarket.REST.toHttpUrl()) {
        { BinanceCdnRoute.resolve(client) }
    } else null
) {
    fun get(path: String, onResult: (Result<String>) -> Unit): MarketRequest {
        val request = MarketRequest()
        val preferred = if (base == ContractMarket.REST.toHttpUrl()) BinanceCdnRoute.preferred() else null
        attempt(request, path, preferred ?: base, preferred != null, true, onResult)
        return request
    }

    private fun attempt(
        group: MarketRequest, path: String, route: HttpUrl, cdn: Boolean,
        canSwitch: Boolean, onResult: (Result<String>) -> Unit
    ) {
        if (group.canceled) return
        val url = (route.toString().trimEnd('/') + "/" + path.trimStart('/')).toHttpUrl()
        val builder = Request.Builder().url(url)
        if (cdn) builder.header("Host", "fapi.binance.com")
        val call = client.newCall(builder.build())
        if (!group.attach(call)) return
        fun finish(result: Result<String>) {
            if (group.canceled) return
            val error = result.exceptionOrNull()
            val retryable = error is IOException &&
                (error !is MarketHttpException || error.status in 500..599)
            if (retryable && canSwitch) {
                if (cdn) {
                    BinanceCdnRoute.invalidate(route.host)
                    attempt(group, path, base, false, false, onResult)
                    return
                }
                val alternative = runCatching { fallback?.invoke() }.getOrNull()
                if (alternative != null && alternative != route && !group.canceled) {
                    attempt(group, path, alternative, true, false, onResult)
                    return
                }
            }
            if (result.isSuccess && cdn) BinanceCdnRoute.succeeded(route.host)
            if (!group.canceled) onResult(result)
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = finish(Result.failure(e))

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        val body = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            val code = runCatching { JSONObject(body).getInt("code") }.getOrNull()
                            throw MarketHttpException(it.code, code)
                        }
                        val data = runCatching { JSONTokener(body).nextValue() }.getOrNull()
                        if (data !is JSONObject && data !is JSONArray) throw IOException("行情响应异常")
                        body
                    }
                }
                finish(result)
            }
        })
    }
}

/** 短提示用于价格栏；详细提示放在可换行的状态区。 */
object MarketErrors {
    fun label(error: Throwable): String = when (error) {
        is MarketHttpException -> when {
            error.apiCode == -1121 -> "无此合约"
            error.status == 429 || error.status == 418 -> "请求限流"
            error.status == 451 || error.status == 403 -> "访问受限"
            else -> "HTTP ${error.status}"
        }
        is UnknownHostException -> "域名错误"
        is SocketTimeoutException -> "连接超时"
        is InterruptedIOException -> "连接超时"
        is SSLException -> "连接失败"
        is IOException -> "连接失败"
        else -> "数据异常"
    }

    fun detail(error: Throwable): String = when (error) {
        is MarketHttpException -> when (error.status) {
            429, 418 -> "币安请求限流（HTTP ${error.status}），请稍后刷新"
            451 -> "币安当前网络或地区访问受限（HTTP 451），请检查网络"
            else -> "币安合约请求失败（HTTP ${error.status}），请检查网络后刷新"
        }
        is UnknownHostException -> "无法解析币安合约域名，请检查网络或点击「网络」设置代理"
        is SSLException -> "币安安全连接失败，请检查网络、代理及手机时间"
        else -> "无法连接币安合约接口，请检查网络或点击「网络」设置代理后刷新"
    }
}
