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
import java.util.concurrent.Executors
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
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .apply {
            // 留空时使用 Android 系统网络/VPN 和系统代理；显式代理同时用于 HTTPS 与 WSS。
            if (proxy != null) proxy(Proxy(Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(proxy.host, proxy.port)))
        }
        .build()
}

class MarketHttpException(val status: Int, val apiCode: Int?) : IOException("HTTP $status")

/** 并行连接组，首个成功响应胜出，取消会关闭所有候选连接。 */
class MarketRequest {
    @Volatile var canceled = false
        private set
    private var completed = false
    private val active = ArrayList<Call>()
    @Synchronized fun cancel() {
        canceled = true
        active.forEach { it.cancel() }
        active.clear()
    }
    @Synchronized internal fun attach(call: Call): Boolean {
        if (canceled || completed) { call.cancel(); return false }
        active.add(call)
        return true
    }
    @Synchronized internal fun complete(result: Result<String>, callback: (Result<String>) -> Unit) {
        if (canceled || completed) return
        completed = true
        active.forEach { it.cancel() }
        active.clear()
        callback(result)
    }
    @Synchronized internal fun pending() = !canceled && !completed
}

/** 同时尝试主域名及其官方 CDN，避免先等主域名超时才显示首价。 */
class ContractHttp(
    private val client: OkHttpClient,
    private val base: HttpUrl = ContractMarket.REST.toHttpUrl(),
    private val fallback: (() -> HttpUrl?)? = if (base == ContractMarket.REST.toHttpUrl()) {
        { BinanceCdnRoute.resolve(client) }
    } else null
) {
    companion object {
        private val resolver = Executors.newCachedThreadPool { r -> Thread(r, "contract-route").apply { isDaemon = true } }
    }
    fun get(path: String, onResult: (Result<String>) -> Unit): MarketRequest {
        val group = MarketRequest()
        var failures = 0
        val total = if (fallback == null) 1 else 2
        fun finish(result: Result<String>) {
            synchronized(group) {
                if (!group.pending()) return
                val error = result.exceptionOrNull()
                if (result.isSuccess || (error is MarketHttpException && error.status in 400..499)
                    || ++failures == total) group.complete(result, onResult)
            }
        }
        fun attempt(route: HttpUrl, cdn: Boolean) {
            if (!group.pending()) return
            val url = (route.toString().trimEnd('/') + "/" + path.trimStart('/')).toHttpUrl()
            val builder = Request.Builder().url(url)
            if (cdn) builder.header("Host", "fapi.binance.com")
            val call = client.newCall(builder.build())
            if (!group.attach(call)) return
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = finish(Result.failure(e))
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            val body = it.body?.string().orEmpty()
                            if (!it.isSuccessful) throw MarketHttpException(it.code,
                                runCatching { JSONObject(body).getInt("code") }.getOrNull())
                            val data = runCatching { JSONTokener(body).nextValue() }.getOrNull()
                            if (data !is JSONObject && data !is JSONArray) throw IOException("行情响应异常")
                            body
                        }
                    }
                    finish(result)
                }
            })
        }
        attempt(base, false)
        if (fallback != null) resolver.execute {
            if (!group.pending()) return@execute
            val route = runCatching { fallback.invoke() }.getOrNull()
            if (route == null || route == base) finish(Result.failure(IOException("备用通道解析失败")))
            else if (group.pending()) attempt(route, true)
        }
        return group
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
        is UnknownHostException -> "无法解析合约域名，已尝试自动直连通道，请检查网络后刷新"
        is SSLException -> "币安安全连接失败，请检查网络、代理及手机时间"
        else -> "合约自动直连暂时失败，请检查网络后刷新；网络设置可选"
    }
}
