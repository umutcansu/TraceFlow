package io.github.umutcansu.traceflow.studio.remote

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import io.github.umutcansu.traceflow.studio.model.TraceEvent
import io.github.umutcansu.traceflow.studio.model.TraceEventType
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Polls a remote HTTP endpoint for trace events and delivers them
 * via the [onEvent] callback.
 *
 * Expected server contract:
 * ```
 * GET /traces?since={timestampMs}
 * Response: JSON array of trace event objects
 * ```
 */
class RemoteLogPoller(
  private val endpoint: String,
  private val headers: Map<String, String> = emptyMap(),
  private val pollIntervalMs: Long = 1500L,
  private val onEvent: (TraceEvent) -> Unit,
  private val onError: (String) -> Unit = {},
  private val onConnected: () -> Unit = {},
) : Disposable {

  private val running = AtomicBoolean(false)
  private var pollerThread: Thread? = null
  private val gson = Gson()

  @Volatile
  var lastTimestamp: Long = 0L
    private set

  @Volatile
  var eventCount: Int = 0
    private set

  fun start() {
    if (running.getAndSet(true)) return
    lastTimestamp = System.currentTimeMillis()
    eventCount = 0

    pollerThread = Thread({
      onConnected()
      while (running.get()) {
        try {
          poll()
        } catch (e: Exception) {
          if (running.get()) {
            onError("Poll error: ${e.message}")
          }
        }
        try {
          Thread.sleep(pollIntervalMs)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          break
        }
      }
    }, "TraceFlow-RemotePoller").also {
      it.isDaemon = true
      it.start()
    }
  }

  fun stop() {
    running.set(false)
    pollerThread?.interrupt()
    pollerThread = null
  }

  override fun dispose() = stop()

  private fun poll() {
    val separator = if (endpoint.contains('?')) '&' else '?'
    val url = URL("${endpoint}${separator}since=$lastTimestamp")
    val conn = url.openConnection() as HttpURLConnection
    try {
      conn.requestMethod = "GET"
      conn.connectTimeout = 5000
      conn.readTimeout = 5000
      for ((key, value) in headers) {
        conn.setRequestProperty(key, value)
      }

      val code = conn.responseCode
      if (code !in 200..299) return

      val body = InputStreamReader(conn.inputStream, Charsets.UTF_8).use { it.readText() }
      val array = gson.fromJson(body, JsonArray::class.java) ?: return

      for (element in array) {
        val obj = element.asJsonObject ?: continue
        val event = parseEvent(obj) ?: continue
        if (event.timestampMs > lastTimestamp) {
          lastTimestamp = event.timestampMs
        }
        eventCount++
        onEvent(event)
      }
    } finally {
      conn.disconnect()
    }
  }

  private fun parseEvent(obj: JsonObject): TraceEvent? {
    val type = when (obj.get("type")?.asString) {
      "ENTER" -> TraceEventType.ENTER
      "EXIT" -> TraceEventType.EXIT
      "CATCH" -> TraceEventType.CATCH
      "BRANCH" -> TraceEventType.BRANCH
      else -> return null
    }

    val extra = mutableMapOf<String, String>()

    obj.getAsJsonObject("params")?.entrySet()?.forEach { (k, v) ->
      extra[k] = v.asString
    }

    listOf("result", "durationMs", "exception", "message", "tryStartLine", "conditionResult")
      .forEach { key ->
        obj.get(key)?.let { extra[key] = it.asString }
      }

    return TraceEvent(
      type = type,
      className = obj.get("class")?.asString ?: "",
      method = obj.get("method")?.asString ?: "",
      file = obj.get("file")?.asString ?: "",
      line = obj.get("line")?.asInt ?: 0,
      threadId = obj.get("threadId")?.asLong ?: 0,
      threadName = obj.get("threadName")?.asString ?: "",
      timestampMs = obj.get("ts")?.asLong ?: System.currentTimeMillis(),
      extra = extra,
    )
  }
}
