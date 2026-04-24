package io.github.umutcansu.traceflow

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.umutcansu.traceflow.remote.RemoteSender
import org.json.JSONObject
import java.util.UUID

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

  /** Master switch — disables both logcat and remote when false. */
  @JvmField
  @Volatile
  var enabled: Boolean = true

  /** Controls logcat output independently. Logcat logs are written only when both [enabled] and [logcatEnabled] are true. */
  @JvmField
  @Volatile
  var logcatEnabled: Boolean = true

  /** Controls remote sending independently. Events are sent only when both [enabled] and [remoteEnabled] are true. */
  @JvmField
  @Volatile
  var remoteEnabled: Boolean = true

  /** Parameter names containing any of these strings will have their values masked as "***". */
  @JvmField
  @Volatile
  var maskParams: List<String> = listOf("password", "token", "pin", "secret", "cvv", "ssn")

  @Volatile
  private var remoteSender: RemoteSender? = null

  /** Device model (auto-detected) included in every JSON event. */
  @Volatile
  private var deviceModel: String = Build.MODEL

  /** Device manufacturer (auto-detected) included in every JSON event. */
  @Volatile
  private var deviceManufacturer: String = Build.MANUFACTURER

  /** User-defined tag for identifying this device/session in remote logs. Can be changed at any time. */
  @JvmField
  @Volatile
  var deviceTag: String = ""

  // -- Schema v2 state (populated only by the Context-aware startRemote overload) -----
  // When schemaV2 is false, emitJson produces v1-shape events exactly as before.
  // This gate is what keeps the old no-Context startRemote entry point fully backwards
  // compatible: callers who didn't migrate opt out of v2 fields automatically.

  @Volatile private var schemaV2: Boolean = false
  @Volatile private var v2AppId: String? = null
  @Volatile private var v2AppVersion: String? = null
  @Volatile private var v2BuildNumber: String? = null
  @Volatile private var v2UserId: String? = null
  @Volatile private var v2DeviceId: String? = null   // persisted in SharedPreferences
  @Volatile private var v2SessionId: String? = null  // regenerated per startRemote call

  private const val PREFS_NAME = "traceflow_prefs"
  private const val PREFS_DEVICE_ID = "device_id"

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
    allowInsecure: Boolean = false,
    compress: Boolean = true,
  ) {
    remoteSender?.stop()
    deviceModel = Build.MODEL
    deviceManufacturer = Build.MANUFACTURER
    deviceTag = tag
    remoteSender = RemoteSender(endpoint, headers, batchSize, flushIntervalMs, allowInsecure = allowInsecure, compress = compress)
  }

  /**
   * Schema-v2 variant of [startRemote] that enables multi-platform fields on every
   * emitted event: `schemaVersion=2`, `platform="android-jvm"`, `runtime`, `appId`,
   * `appVersion`, `buildNumber`, `userId`, `deviceId` (persisted UUID), `sessionId`
   * (fresh UUID per call).
   *
   * This is an **additive** overload — the original [startRemote] without [Context]
   * keeps working exactly as before and continues to emit v1-shape events. Switching
   * to v2 is opt-in, typically from `Application.onCreate`:
   *
   * ```kotlin
   * TraceLog.startRemote(
   *   context = this,
   *   endpoint = "https://traceflow.example.com/traces",
   *   appId = BuildConfig.APPLICATION_ID,
   *   appVersion = BuildConfig.VERSION_NAME,
   *   buildNumber = BuildConfig.VERSION_CODE.toString(),
   * )
   * ```
   *
   * The persisted `deviceId` lives under `SharedPreferences("traceflow_prefs")`;
   * clear app data to reset.
   */
  @JvmStatic
  @JvmOverloads
  fun startRemote(
    context: Context,
    endpoint: String,
    tag: String = "",
    headers: Map<String, String> = emptyMap(),
    batchSize: Int = 10,
    flushIntervalMs: Long = 3000L,
    allowInsecure: Boolean = false,
    compress: Boolean = true,
    appId: String? = null,
    appVersion: String? = null,
    buildNumber: String? = null,
    userId: String? = null,
  ) {
    // Reuse the legacy overload for transport + basic device metadata, then
    // layer v2 state on top. Doing it in this order guarantees we share the
    // single code path that sets up RemoteSender.
    startRemote(endpoint, tag, headers, batchSize, flushIntervalMs, allowInsecure, compress)
    v2AppId = appId
    v2AppVersion = appVersion
    v2BuildNumber = buildNumber
    v2UserId = userId
    v2DeviceId = resolveOrCreateDeviceId(context)
    v2SessionId = UUID.randomUUID().toString()
    schemaV2 = true
  }

  /** Update the user id on the fly (e.g. after login/logout) without restarting remote. */
  @JvmStatic
  fun setUserId(userId: String?) { v2UserId = userId }

  private fun resolveOrCreateDeviceId(context: Context): String {
    val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.getString(PREFS_DEVICE_ID, null)?.let { return it }
    val fresh = UUID.randomUUID().toString()
    prefs.edit().putString(PREFS_DEVICE_ID, fresh).apply()
    return fresh
  }

  /**
   * Stop remote sending. Pending events are flushed before shutdown.
   *
   * Resets schema-v2 state so a subsequent legacy `startRemote(endpoint, ...)` call
   * doesn't leak `platform` / `sessionId` / `deviceId` from a previous v2 session.
   * The persisted `deviceId` on disk is kept — the next v2 `startRemote(context, ...)`
   * call re-reads it so the same device keeps its identity across sessions.
   */
  @JvmStatic
  fun stopRemote() {
    remoteSender?.stop()
    remoteSender = null
    schemaV2 = false
    v2SessionId = null
    v2DeviceId = null
    v2AppId = null
    v2AppVersion = null
    v2BuildNumber = null
    v2UserId = null
  }

  /** Returns true if remote sending is currently active. */
  @JvmStatic
  fun isRemoteActive(): Boolean = remoteSender != null

  // -- Entry -----------------------------------------------------------------

  @JvmStatic
  fun enter(className: String, method: String, file: String, line: Int) {
    if (!enabled) return
    if (logcatEnabled) Log.d(TAG_ENTER, "[$className] $method()  src:$file:$line")
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
    if (logcatEnabled) {
      val paramStr = buildParamString(paramNames, paramValues)
      Log.d(TAG_ENTER, "[$className] $method()  src:$file:$line\n  $paramStr")
    }
    emitJson("ENTER", className, method, file, line, params = buildParamMap(paramNames, paramValues))
  }

  // -- Exit ------------------------------------------------------------------

  @JvmStatic
  fun exit(className: String, method: String, file: String, line: Int, startTimeMs: Long) {
    if (!enabled) return
    val duration = formatDuration(System.currentTimeMillis() - startTimeMs)
    if (logcatEnabled) Log.d(TAG_EXIT, "[$className] $method  [$duration]  src:$file:$line")
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
    if (logcatEnabled) Log.d(TAG_EXIT, "[$className] $method  [$duration]  src:$file:$line\n  result: $resultStr")
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
    if (logcatEnabled) {
      Log.w(TAG_CATCH, "[$className] $method  src:$file:$catchLine\n" +
        "  try started: line $tryStartLine -> catch: line $catchLine\n" +
        "  $exType: $exMsg")
    }
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
    if (logcatEnabled) Log.v(TAG_BRANCH, "[$className] $method  src:$file:$line\n  condition -> $verdict")
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
    if (!logcatEnabled && (!remoteEnabled || remoteSender == null)) return
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
        if (deviceManufacturer.isNotEmpty()) put("deviceManufacturer", deviceManufacturer)
        if (deviceModel.isNotEmpty()) put("deviceModel", deviceModel)
        if (deviceTag.isNotEmpty()) put("tag", deviceTag)
        if (params != null) {
          val pJson = JSONObject()
          params.forEach { (k, v) -> pJson.put(k, v) }
          put("params", pJson)
        }
        extra.forEach { (k, v) -> put(k, v) }

        // Schema v2 fields — only emitted when the Context-aware startRemote
        // overload was used. The legacy overload keeps producing v1-shape events.
        if (schemaV2) {
          put("schemaVersion", 2)
          put("platform", "android-jvm")
          put("runtime", "jvm-${Build.VERSION.SDK_INT}")
          v2AppId?.let       { put("appId", it) }
          v2AppVersion?.let  { put("appVersion", it) }
          v2BuildNumber?.let { put("buildNumber", it) }
          v2UserId?.let      { put("userId", it) }
          v2DeviceId?.let    { put("deviceId", it) }
          v2SessionId?.let   { put("sessionId", it) }
        }
      }
      val jsonStr = json.toString()
      if (logcatEnabled) Log.d(TAG_JSON, jsonStr)
      if (remoteEnabled) remoteSender?.enqueue(jsonStr)
    } catch (_: Exception) {
      // JSON creation errors must not interrupt the trace flow
    }
  }

  // -- Helpers ---------------------------------------------------------------

  private fun isMasked(name: String): Boolean {
    if (maskParams.isEmpty()) return false
    val lower = name.lowercase()
    return maskParams.any { lower.contains(it) }
  }

  private fun buildParamString(names: Array<String>, values: Array<Any?>): String {
    return names.zip(values).joinToString("\n  ") { (name, value) ->
      "$name: ${if (isMasked(name)) "***" else safeToString(value)}"
    }
  }

  private fun buildParamMap(names: Array<String>, values: Array<Any?>): Map<String, String> {
    return names.zip(values).associate { (name, value) ->
      name to if (isMasked(name)) "***" else safeToString(value)
    }
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
