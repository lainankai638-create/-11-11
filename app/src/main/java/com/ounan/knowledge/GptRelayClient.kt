package com.ounan.knowledge

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** HTTPS-only personal relay. Bearer token stays in MainActivity RAM, never in the APK or prefs. */
internal object GptRelayClient {
    fun exchange(endpoint: String, token: String, payload: JSONObject): JSONObject {
        val base = URL(endpoint.trim().trimEnd('/'))
        require(base.protocol == "https" && base.host.isNotBlank() && base.userInfo == null && base.query == null && base.ref == null) {
            "请填写 https:// 开头的个人接口地址"
        }
        require(token.isNotBlank()) { "请填写连接口令" }
        val path = base.path.trimEnd('/') + "/api/turn"
        val requestUrl = URL(base.protocol,base.host,if(base.port > 0) base.port else 443,path)
        val connection = (requestUrl.openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 70000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size < 128000) { "单次请求超过128KB限制" }
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            val stream = if(code in 200..299) connection.inputStream else connection.errorStream
            val value = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(250000) } ?: ""
            if (code !in 200..299) error("服务器返回 HTTP $code：${runCatching { JSONObject(value).optString("error") }.getOrDefault("连接失败").take(120)}")
            return JSONObject(value)
        } finally {
            connection.disconnect()
        }
    }
}
