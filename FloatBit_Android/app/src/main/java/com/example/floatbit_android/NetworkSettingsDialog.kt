package com.example.floatbit_android

import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 仅设置本应用网络；不修改手机系统代理，也不需要币安账户或 API 密钥。 */
object NetworkSettingsDialog {
    fun show(context: Context, onSaved: () -> Unit) {
        val current = Prefs.getProxy(context)
        MaterialAlertDialogBuilder(context)
            .setTitle("行情网络")
            .setMessage("当前：${current?.let { "HTTP 代理 ${it.host}:${it.port}" } ?: "自动连接"}\n\n" +
                "默认自动连接币安合约行情，无需预设代理。自动连接失败时，可使用覆盖本应用的 VPN，或设置你自己的 HTTP 代理。")
            .setPositiveButton("设置 HTTP 代理") { _, _ -> editProxy(context, onSaved) }
            .setNeutralButton("自动连接") { _, _ ->
                Prefs.setProxy(context, null)
                onSaved()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editProxy(context: Context, onSaved: () -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_network, null)
        val host = view.findViewById<EditText>(R.id.proxy_host)
        val port = view.findViewById<EditText>(R.id.proxy_port)
        Prefs.getProxy(context)?.let {
            host.setText(it.host)
            port.setText(it.port.toString())
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("HTTP 代理")
            .setView(view)
            .setPositiveButton("保存并重连", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val proxyHost = host.text.toString().trim()
                val proxyPort = port.text.toString().toIntOrNull()
                if (proxyPort == null || proxyPort !in 1..65535) {
                    port.error = "请输入 1–65535 的端口"
                    return@setOnClickListener
                }
                val proxy = try { HttpProxySettings(proxyHost, proxyPort) } catch (e: IllegalArgumentException) {
                    host.error = e.message
                    return@setOnClickListener
                }
                Prefs.setProxy(context, proxy)
                dialog.dismiss()
                onSaved()
            }
        }
        dialog.show()
    }
}
