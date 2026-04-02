package io.github.umutcansu.traceflow.studio.remote

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.util.io.HttpRequests
import io.github.umutcansu.traceflow.studio.model.TraceEvent
import io.github.umutcansu.traceflow.studio.model.TraceEventType
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Polls a remote HTTP endpoint for trace events and delivers them
 * via the [onEvent] callback.
 *
 * Uses IntelliJ Platform's [HttpRequests] API which respects IDE proxy/network
 * settings and avoids OS-level socket restrictions that block raw HttpURLConnection.
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
    lastTimestamp = 0L
    eventCount = 0

    pollerThread = Thread({
      onConnected()
      while (running.get()) {
        try {
          poll()
        } catch (e: Exception) {
          if (running.get()) {
            onError("Poll error: [${e.javaClass.simpleName}] ${e.message}")
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
    val url = "${endpoint}${separator}since=$lastTimestamp"

    val body = HttpRequests.request(url)
      .connectTimeout(15000)
      .readTimeout(15000)
      .apply {
        for ((key, value) in headers) {
          tuner { conn -> conn.setRequestProperty(key, value) }
        }
      }
      .readString()

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
      deviceManufacturer = obj.get("deviceManufacturer")?.asString ?: "",
      deviceModel = obj.get("deviceModel")?.asString ?: "",
      tag = obj.get("tag")?.asString ?: "",
    )
  }
}
