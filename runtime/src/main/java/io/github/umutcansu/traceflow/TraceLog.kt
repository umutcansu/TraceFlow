package io.github.umutcansu.traceflow

import android.os.Build
import android.util.Log
import io.github.umutcansu.traceflow.remote.RemoteSender
import org.json.JSONObject

/**
 * Runtime bridge for trace logs injected by the ASM plugin.
 *
 * Produces logs in two formats:
 * 1. Human-readable: `ENTER`, `EXIT `, `CATCH`, `BRANCH`
 * 2. Structural JSON: `JSON` -- consumed by the Android Studio Trace Flow plugin.
 *
 * Logcat filter: `tag:TraceFlow`
 */
object TraceLog {

  private const val TAG_ENTER  = "TraceFlow ENTER"
  private const val TAG_EXIT   = "TraceFlow EXIT "
  private const val TAG_CATCH  = "TraceFlow CATCH"
  private const val TAG_BRANCH = "TraceFlow BRANCH"
  private const val TAG_JSON   = "TraceFlow JSON"

  // Can be disabled at runtime (in prod release builds the plugin doesn't inject anyway)
  @JvmField
  var enabled: Boolean = true

  @Volatile
  private var remoteSender: RemoteSender? = null

  /** Device model (auto-detected) included in every remote JSON event. */
  @Volatile
  private var deviceModel: String = ""

  /** User-defined tag for identifying this device/session in remote logs. */
  @Volatile
  private var deviceTag: String = ""

  /**
   * Start sending trace events to a remote HTTP endpoint.
   *
   * Events are batched and sent as JSON arrays via POST.
   * Logcat output continues regardless.
   *
   * ```kotlin
   * TraceLog.startRemote("https://api.example.com/traces")
   * // with tag to identify the device:
   * TraceLog.startRemote(
   *   endpoint = "https://api.example.com/traces",
   *   tag = "qa-team-1",
   *   headers = mapOf("Authorization" to "Bearer token123")
   * )
   * ```
   */
  @JvmStatic
  @JvmOverloads
  fun startRemote(
    endpoint: String,
    tag: String = "",
    headers: Map<String, String> = emptyMap(),
    batchSize: Int = 10,
    flushIntervalMs: Long = 3000L,
  ) {
    remoteSender?.stop()
    deviceModel = Build.MODEL
    deviceTag = tag
    remoteSender = RemoteSender(endpoint, headers, batchSize, flushIntervalMs)
  }

  /** Stop remote sending. Pending events are flushed before shutdown. */
  @JvmStatic
  fun stopRemote() {
    remoteSender?.stop()
    remoteSender = null
  }

  // -- Entry -----------------------------------------------------------------

  @JvmStatic
  fun enter(className: String, method: String, file: String, line: Int) {
    if (!enabled) return
    val src = "$file:$line"
    Log.d(TAG_ENTER, "[$className] $method()  src:$src")
    emitJson("ENTER", className, method, file, line, params = null)
  }

  @JvmStatic
  fun enterWithParams(
    className: String,
    method: String,
    file: String,
    line: Int,
    paramNames: Array<String>,
    paramValues: Array<Any?>,
  ) {
    if (!enabled) return
    val src = "$file:$line"
    val paramStr = buildParamString(paramNames, paramValues)
    Log.d(TAG_ENTER, "[$className] $method()  src:$src\n  $paramStr")
    emitJson("ENTER", className, method, file, line, params = buildParamMap(paramNames, paramValues))
  }

  // -- Exit ------------------------------------------------------------------

  @JvmStatic
  fun exit(className: String, method: String, file: String, line: Int, startTimeMs: Long) {
    if (!enabled) return
    val duration = formatDuration(System.currentTimeMillis() - startTimeMs)
    Log.d(TAG_EXIT, "[$className] $method  [$duration]  src:$file:$line")
    emitJson("EXIT", className, method, file, line,
      extra = mapOf("durationMs" to (System.currentTimeMillis() - startTimeMs)))
  }

  @JvmStatic
  fun exitWithResult(
    className: String,
    method: String,
    file: String,
    line: Int,
    startTimeMs: Long,
    result: Any?,
  ) {
    if (!enabled) return
    val duration = formatDuration(System.currentTimeMillis() - startTimeMs)
    val resultStr = safeToString(result)
    Log.d(TAG_EXIT, "[$className] $method  [$duration]  src:$file:$line\n  result: $resultStr")
    emitJson("EXIT", className, method, file, line,
      extra = mapOf("durationMs" to (System.currentTimeMillis() - startTimeMs), "result" to resultStr))
  }

  // -- Catch -----------------------------------------------------------------

  @JvmStatic
  fun caught(
    className: String,
    method: String,
    file: String,
    catchLine: Int,
    tryStartLine: Int,
    throwable: Throwable?,
  ) {
    if (!enabled) return
    val exType = throwable?.javaClass?.simpleName ?: "Exception"
    val exMsg  = throwable?.message ?: "-"
    Log.w(TAG_CATCH, "[$className] $method  src:$file:$catchLine\n" +
      "  try started: line $tryStartLine -> catch: line $catchLine\n" +
      "  $exType: $exMsg")
    emitJson("CATCH", className, method, file, catchLine,
      extra = mapOf("tryStartLine" to tryStartLine, "exception" to exType, "message" to exMsg))
  }

  // -- Branch ----------------------------------------------------------------

  @JvmStatic
  fun branch(
    className: String,
    method: String,
    file: String,
    line: Int,
    conditionResult: Boolean,
  ) {
    if (!enabled) return
    val verdict = if (conditionResult) "TRUE -- entered if block" else "FALSE -- entered else block"
    Log.v(TAG_BRANCH, "[$className] $method  src:$file:$line\n  condition -> $verdict")
    emitJson("BRANCH", className, method, file, line,
      extra = mapOf("conditionResult" to conditionResult))
  }

  // -- JSON emit -------------------------------------------------------------

  private fun emitJson(
    type: String,
    className: String,
    method: String,
    file: String,
    line: Int,
    params: Map<String, String>? = null,
    extra: Map<String, Any?> = emptyMap(),
  ) {
    try {
      val json = JSONObject().apply {
        put("type", type)
        put("class", className)
        put("method", method)
        put("file", file)
        put("line", line)
        put("threadId", Thread.currentThread().id)
        put("threadName", Thread.currentThread().name)
        put("ts", System.currentTimeMillis())
        if (deviceModel.isNotEmpty()) put("deviceModel", deviceModel)
        if (deviceTag.isNotEmpty()) put("tag", deviceTag)
        if (params != null) {
          val pJson = JSONObject()
          params.forEach { (k, v) -> pJson.put(k, v) }
          put("params", pJson)
        }
        extra.forEach { (k, v) -> put(k, v) }
      }
      val jsonStr = json.toString()
      Log.d(TAG_JSON, jsonStr)
      remoteSender?.enqueue(jsonStr)
    } catch (_: Exception) {
      // JSON creation errors must not interrupt the trace flow
    }
  }

  // -- Helpers ---------------------------------------------------------------

  private fun buildParamString(names: Array<String>, values: Array<Any?>): String {
    return names.zip(values).joinToString("\n  ") { (name, value) ->
      "$name: ${safeToString(value)}"
    }
  }

  private fun buildParamMap(names: Array<String>, values: Array<Any?>): Map<String, String> {
    return names.zip(values).associate { (name, value) -> name to safeToString(value) }
  }

  @JvmStatic
  fun safeToString(value: Any?): String {
    if (value == null) return "null"
    return try {
      val str = value.toString()
      if (str.length > 200) str.take(200) + "..." else str
    } catch (_: Exception) {
      "<toString error>"
    }
  }

  private fun formatDuration(ms: Long): String = when {
    ms >= 60_000 -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
    ms >= 1_000  -> "${ms / 1000}s ${ms % 1000}ms"
    else         -> "${ms}ms"
  }
}
