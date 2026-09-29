package com.khatwa.app.challenge

import com.khatwa.app.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Tiny HTTP helper for the free open-data services (OpenStreetMap, Open-Meteo). Their usage
 * policies ask apps to identify themselves, so every request carries the app's User-Agent.
 */
object Http {
    val USER_AGENT = "khatwa/${BuildConfig.VERSION_NAME} (+https://github.com/Ahmad-Nayfeh/khatwa)"

    fun get(url: String, timeoutMs: Int = 20_000): String = request(url, null, timeoutMs)

    fun postForm(url: String, fields: Map<String, String>, timeoutMs: Int = 30_000): String =
        request(url, fields.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }, timeoutMs)

    private fun request(url: String, body: String?, timeoutMs: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code from ${URL(url).host}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
