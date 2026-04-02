package io.github.umutcansu.traceflow.remote

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.json.JSONArray
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Sends trace events to a remote HTTP endpoint in batches.
 *
 * Events are queued and flushed when [batchSize] is reached or
 * [flushIntervalMs] elapses — whichever comes first.
 *
 * All network I/O runs on a dedicated background thread.
 * Errors are silently logged to Logcat — the app must never crash
 * because of remote tracing.
 */
internal class RemoteSender(
    private val endpoint: String,
    private val headers: Map<String, String> = emptyMap(),
    private val batchSize: Int = 10,
    private val flushIntervalMs: Long = 3000L,
    private val maxRetries: Int = 3,
    private val maxQueueSize: Int = 1000,
) {

    init {
        if (endpoint.startsWith("http://")) {
            Log.w("TraceFlow", "WARNING: Remote endpoint uses HTTP (not HTTPS). Trace data will be sent unencrypted. Use HTTPS in production.")
        }
    }

    private val queue = ConcurrentLinkedQueue<String>()
    private val thread = HandlerThread("TraceFlow-Remote").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile
    private var running = true

    init {
        scheduleFlush()
    }

    /** Enqueue a JSON string for sending. Non-blocking, safe from any thread. */
    fun enqueue(json: String) {
        if (!running) return
        if (queue.size >= maxQueueSize) {
            queue.poll() // drop oldest to prevent unbounded memory growth
        }
        queue.add(json)
        if (queue.size >= batchSize) {
            handler.post { flush() }
        }
    }

    /** Stop the sender. Attempts one final flush before shutting down. */
    fun stop() {
        running = false
        try {
            handler.post {
                flush()
                thread.quitSafely()
            }
        } catch (_: Exception) {
            // Handler may reject if looper already quit
            thread.quitSafely()
        }
    }

    private fun scheduleFlush() {
        handler.postDelayed({
            if (running) {
                flush()
                scheduleFlush()
            }
        }, flushIntervalMs)
    }

    private fun flush() {
        if (queue.isEmpty()) return

        val batch = mutableListOf<String>()
        while (batch.size < batchSize) {
            val item = queue.poll() ?: break
            batch.add(item)
        }
        if (batch.isEmpty()) return

        val jsonArray = JSONArray()
        for (item in batch) {
            try {
                jsonArray.put(org.json.JSONObject(item))
            } catch (_: Exception) {
                // skip malformed entries
            }
        }

        var attempt = 0
        while (attempt < maxRetries) {
            try {
                post(jsonArray.toString())
                return
            } catch (e: Exception) {
                attempt++
                if (attempt >= maxRetries) {
                    Log.w("TraceFlow", "Remote send failed after $maxRetries retries: ${e.message}")
                }
            }
        }
    }

    private fun post(body: String) {
        val url = URL(endpoint)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json")
            for ((key, value) in headers) {
                conn.setRequestProperty(key, value)
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw RuntimeException("HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }
}
