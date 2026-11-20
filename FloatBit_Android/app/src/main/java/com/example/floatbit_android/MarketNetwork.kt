package com.example.floatbit_android

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
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
        require(host.isNotBlank() && host.none { it.isWhitespace() || it in "/@?#" }) {
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

/** 单次请求有总超时；取消后不会新建请求或继续重试其他域名。 */
class ContractHttp(
    private val client: OkHttpClient,
    private val base: HttpUrl = ContractMarket.REST.toHttpUrl()
) {
    fun get(path: String, onResult: (Result<String>) -> Unit): Call {
        val url = (base.toString().trimEnd('/') + "/" + path.trimStart('/')).toHttpUrl()
        return client.newCall(Request.Builder().url(url).build()).also { call ->
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!call.isCanceled()) onResult(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            val body = it.body?.string().orEmpty()
                            if (!it.isSuccessful) {
                                val code = runCatching { JSONObject(body).getInt("code") }.getOrNull()
                                throw MarketHttpException(it.code, code)
                            }
                            if (body.isBlank()) throw IOException("行情响应为空")
                            body
                        }
                    }
                    if (!call.isCanceled()) onResult(result)
                }
            })
        }
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
