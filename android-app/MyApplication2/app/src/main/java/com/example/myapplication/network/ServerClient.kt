package com.example.myapplication.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.myapplication.model.CellReport
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class ServerClient(context: Context) {

    interface Callback {
        fun onSuccess(json: String)
        fun onError(error: String)
    }

    private val ctx      = context.applicationContext
    private val executor = Executors.newFixedThreadPool(2)

    private val serverPrefs: SharedPreferences
        get() = ctx.getSharedPreferences("server_prefs", Context.MODE_PRIVATE)

    // Read the logged-in username saved by WelcomeActivity
    private val loggedInUsername: String
        get() = ctx.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            .getString("username", "") ?: ""

    companion object {
        private const val TAG          = "ServerClient"
        private const val DEFAULT_HOST = "192.168.1.100"
        private const val DEFAULT_PORT = 5000
        private const val TIMEOUT      = 8000
    }

    var host: String
        get() = serverPrefs.getString("host", DEFAULT_HOST) ?: DEFAULT_HOST
        set(v) { serverPrefs.edit().putString("host", v).apply() }

    var port: Int
        get() = serverPrefs.getInt("port", DEFAULT_PORT)
        set(v) { serverPrefs.edit().putInt("port", v).apply() }

    fun saveAddress(h: String, p: Int) { host = h; port = p }

    fun sendReport(report: CellReport) {
        val username = loggedInUsername          // read once per send
        executor.submit {
            try {
                val json   = report.toJsonWithUser(username)
                val status = post("http://$host:$port/api/report", json)
                Log.d(TAG, "Upload HTTP $status")
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed: ${e.message}")
            }
        }
    }

    fun fetchStats(from: Long, to: Long, deviceId: String?, cb: Callback) {
        executor.submit {
            try {
                val url = "http://$host:$port/api/stats?from=$from&to=$to" +
                        (deviceId?.let { "&device_id=$it" } ?: "")
                cb.onSuccess(get(url))
            } catch (e: Exception) {
                cb.onError(e.message ?: "Unknown error")
            }
        }
    }

    fun shutdown() = executor.shutdownNow()

    private fun post(endpoint: String, body: String): Int {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.doOutput     = true
        conn.connectTimeout = TIMEOUT
        conn.readTimeout    = TIMEOUT
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        conn.disconnect()
        return code
    }

    private fun get(endpoint: String): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod  = "GET"
        conn.connectTimeout = TIMEOUT
        conn.readTimeout    = TIMEOUT
        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
        val result = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return result
    }
}
