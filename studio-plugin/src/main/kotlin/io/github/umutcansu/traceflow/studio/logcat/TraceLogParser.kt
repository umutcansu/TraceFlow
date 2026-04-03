package io.github.umutcansu.traceflow.studio.logcat

import io.github.umutcansu.traceflow.studio.model.TraceEvent
import io.github.umutcansu.traceflow.studio.model.TraceEventType
import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * Parses logcat lines containing the JSON tag into TraceEvent instances.
 *
 * Expected logcat format (ADB logcat -v threadtime):
 * `MM-DD HH:mm:ss.SSS  PID  TID D JSON : {...json...}`
 */
object TraceLogParser {

  private val gson = Gson()
  private const val JSON_TAG = "TraceFlow JSON"

  fun parseLine(line: String): TraceEvent? {
    if (!line.contains(JSON_TAG)) return null

    val jsonStart = line.indexOf('{')
    if (jsonStart < 0) return null

    return try {
      val jsonStr = line.substring(jsonStart)
      val obj = gson.fromJson(jsonStr, JsonObject::class.java)
      parseJson(obj)
    } catch (_: Exception) {
      null
    }
  }

  private fun parseJson(obj: JsonObject): TraceEvent? {
    val type = when (obj.get("type")?.asString) {
      "ENTER"  -> TraceEventType.ENTER
      "EXIT"   -> TraceEventType.EXIT
      "CATCH"  -> TraceEventType.CATCH
      "BRANCH" -> TraceEventType.BRANCH
      else     -> return null
    }

    val extra = mutableMapOf<String, String>()

    // Parameter map
    obj.getAsJsonObject("params")?.entrySet()?.forEach { (k, v) ->
      extra[k] = v.asString
    }

    // Other fields
    listOf("result", "durationMs", "exception", "message", "tryStartLine", "conditionResult")
      .forEach { key ->
        obj.get(key)?.let { extra[key] = it.toString().removeSurrounding("\"") }
      }

    return TraceEvent(
      type               = type,
      className          = obj.get("class")?.asString ?: "",
      method             = obj.get("method")?.asString ?: "",
      file               = obj.get("file")?.asString ?: "",
      line               = obj.get("line")?.asInt ?: 0,
      threadId           = obj.get("threadId")?.asLong ?: 0,
      threadName         = obj.get("threadName")?.asString ?: "",
      timestampMs        = obj.get("ts")?.asLong ?: System.currentTimeMillis(),
      extra              = extra,
      deviceManufacturer = obj.get("deviceManufacturer")?.asString ?: "",
      deviceModel        = obj.get("deviceModel")?.asString ?: "",
      tag                = obj.get("tag")?.asString ?: "",
    )
  }
}
