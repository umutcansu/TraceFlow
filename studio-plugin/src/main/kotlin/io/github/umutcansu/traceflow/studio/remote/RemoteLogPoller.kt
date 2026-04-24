package io.github.umutcansu.traceflow.studio.remote

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
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
  /** Server-side filters sent as query params on every poll (schema v2). */
  private val platformFilter: String? = null,
  private val appIdFilter: String? = null,
  private val userIdFilter: String? = null,
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
    val sb = StringBuilder(endpoint).append(separator).append("since=").append(lastTimestamp)
    platformFilter?.takeIf { it.isNotBlank() }?.let { sb.append("&platform=").append(urlEncode(it)) }
    appIdFilter?.takeIf   { it.isNotBlank() }?.let { sb.append("&appId=").append(urlEncode(it)) }
    userIdFilter?.takeIf  { it.isNotBlank() }?.let { sb.append("&userId=").append(urlEncode(it)) }
    val url = sb.toString()

    val body = HttpRequests.request(url)
      .connectTimeout(15000)
      .readTimeout(15000)
      .apply {
        for ((key, value) in headers) {
          tuner { conn -> conn.setRequestProperty(key, value) }
        }
      }
      .readString()

    // Grace-parse: accept both legacy raw-array response (v1 server) and the
    // envelope { events, nextCursor } introduced in schema v2. This lets a
    // newer plugin continue to work against older servers during rollout.
    val parsed: JsonElement = gson.fromJson(body, JsonElement::class.java) ?: return
    val array: JsonArray = when {
      parsed.isJsonArray -> parsed.asJsonArray
      parsed.isJsonObject -> parsed.asJsonObject.getAsJsonArray("events") ?: return
      else -> return
    }

    for (element in array) {
      val obj = element.asJsonObject ?: continue
      val event = parseEvent(obj) ?: continue
      if (event.timestampMs > lastTimestamp) {
        lastTimestamp = event.timestampMs
      }
      eventCount++
      onEvent(event)
    }

    // If the server indicates more pages are available, chain immediately
    // without waiting for the next pollIntervalMs tick. Bounded by server limit.
    if (parsed.isJsonObject) {
      val cursor = parsed.asJsonObject.get("nextCursor")
      if (cursor != null && !cursor.isJsonNull) {
        // lastTimestamp was just advanced above; falling through to the next
        // scheduled poll would also work, but chasing the cursor flushes
        // backlogs quickly on first connect.
        poll()
      }
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
        obj.get(key)?.let { extra[key] = it.toString().removeSurrounding("\"") }
      }

    // Stack frames (JS exceptions): join into extra["stack"] for the detail view.
    obj.getAsJsonArray("stack")?.let { arr ->
      extra["stack"] = arr.joinToString("\n") { it.asString }
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
      platform    = obj.get("platform")?.takeIf { !it.isJsonNull }?.asString,
      appId       = obj.get("appId")?.takeIf { !it.isJsonNull }?.asString,
      appVersion  = obj.get("appVersion")?.takeIf { !it.isJsonNull }?.asString,
      buildNumber = obj.get("buildNumber")?.takeIf { !it.isJsonNull }?.asString,
      userId      = obj.get("userId")?.takeIf { !it.isJsonNull }?.asString,
      deviceId    = obj.get("deviceId")?.takeIf { !it.isJsonNull }?.asString,
      sessionId   = obj.get("sessionId")?.takeIf { !it.isJsonNull }?.asString,
      runtime     = obj.get("runtime")?.takeIf { !it.isJsonNull }?.asString,
    )
  }

  private fun urlEncode(s: String): String =
    java.net.URLEncoder.encode(s, Charsets.UTF_8)
}
