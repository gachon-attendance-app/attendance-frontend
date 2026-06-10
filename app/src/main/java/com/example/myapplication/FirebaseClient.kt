package com.example.myapplication

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object FirebaseClient {

    private val mainHandler = Handler(Looper.getMainLooper())
    private const val TAG = "FirebaseClient"

    fun get(path: String, callback: (JSONObject?) -> Unit) {
        request("GET", path, null, callback)
    }

    fun put(path: String, body: JSONObject, callback: (JSONObject?) -> Unit = {}) {
        request("PUT", path, body.toString(), callback)
    }

    fun patch(path: String, body: JSONObject, callback: (JSONObject?) -> Unit = {}) {
        request("PATCH", path, body.toString(), callback)
    }

    fun putRawBoolean(path: String, value: Boolean, callback: (JSONObject?) -> Unit = {}) {
        request("PUT", path, value.toString(), callback)
    }

    private fun request(
        method: String,
        path: String,
        bodyText: String?,
        callback: (JSONObject?) -> Unit
    ) {
        Thread {
            var connection: HttpURLConnection? = null

            try {
                val cleanPath = path.trim().trim('/')
                val urlText = if (cleanPath.isEmpty()) {
                    "${FirebaseConfig.BASE_URL}/.json"
                } else {
                    "${FirebaseConfig.BASE_URL}/$cleanPath.json"
                }

                Log.d(TAG, "→ $method $urlText")

                connection = URL(urlText).openConnection() as HttpURLConnection
                connection.requestMethod = method
                connection.connectTimeout = 15000   // 8→15초로 증가
                connection.readTimeout = 15000
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")

                if (bodyText != null) {
                    connection.doOutput = true
                    OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                        it.write(bodyText)
                        it.flush()
                    }
                }

                val responseCode = connection.responseCode
                Log.d(TAG, "← $responseCode for $cleanPath")

                val stream = if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    Log.e(TAG, "HTTP ERROR $responseCode for $cleanPath")
                    connection.errorStream
                }

                val responseText = stream?.let {
                    BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader ->
                        reader.readText()
                    }
                }.orEmpty()

                Log.d(TAG, "BODY[$cleanPath] = ${responseText.take(200)}")

                val json = when {
                    responseText.isBlank() -> {
                        Log.w(TAG, "BLANK response for $cleanPath")
                        null
                    }
                    responseText.trim() == "null" -> {
                        Log.w(TAG, "NULL response for $cleanPath")
                        null
                    }
                    responseText.trim().startsWith("{") -> JSONObject(responseText)
                    else -> {
                        Log.w(TAG, "NON-JSON response for $cleanPath: ${responseText.take(100)}")
                        JSONObject().put("value", responseText)
                    }
                }

                mainHandler.post {
                    callback(json)
                }
            } catch (e: Exception) {
                Log.e(TAG, "EXCEPTION for $path: ${e.javaClass.simpleName}: ${e.message}")
                mainHandler.post {
                    callback(null)
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }
}
