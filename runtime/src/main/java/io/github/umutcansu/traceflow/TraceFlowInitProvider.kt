package io.github.umutcansu.traceflow

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import org.json.JSONObject

/**
 * Auto-initializes TraceFlow remote sending when the Gradle plugin
 * generates a `traceflow_remote.json` asset with `enabled: true`.
 *
 * This ContentProvider runs before Application.onCreate() so trace
 * events are captured from the very start of the app lifecycle.
 */
class TraceFlowInitProvider : ContentProvider() {

  override fun onCreate(): Boolean {
    val ctx = context ?: return true
    try {
      val json = ctx.assets.open("traceflow_remote.json")
        .bufferedReader()
        .use { it.readText() }
      val config = JSONObject(json)
      if (!config.optBoolean("enabled", false)) return true

      val endpoint = config.optString("endpoint", "")
      if (endpoint.isEmpty()) return true

      val tag = config.optString("tag", "")
      val headers = mutableMapOf<String, String>()
      config.optJSONObject("headers")?.let { obj ->
        obj.keys().forEach { key -> headers[key] = obj.getString(key) }
      }
      val batchSize = config.optInt("batchSize", 10)
      val flushIntervalMs = config.optLong("flushIntervalMs", 3000L)

      val logcatEnabled = config.optBoolean("logcatEnabled", true)
      TraceLog.logcatEnabled = logcatEnabled

      TraceLog.startRemote(
        endpoint = endpoint,
        tag = tag,
        headers = headers,
        batchSize = batchSize,
        flushIntervalMs = flushIntervalMs,
      )
      Log.d("TraceFlow", "Remote auto-started: $endpoint (logcat=${logcatEnabled})")
    } catch (_: java.io.FileNotFoundException) {
      // No config file = remote not configured, silently skip
    } catch (e: Exception) {
      Log.w("TraceFlow", "Failed to auto-start remote: ${e.message}")
    }
    return true
  }

  override fun query(uri: Uri, p: Array<String>?, s: String?, sa: Array<String>?, so: String?): Cursor? = null
  override fun getType(uri: Uri): String? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun delete(uri: Uri, s: String?, sa: Array<String>?): Int = 0
  override fun update(uri: Uri, values: ContentValues?, s: String?, sa: Array<String>?): Int = 0
}
