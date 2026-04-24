package io.github.umutcansu.traceflow.studio.remote

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.intellij.util.io.HttpRequests

/**
 * Summary of a single application reported by `GET /apps` on a
 * schema-v2 TraceFlow server. Used to populate the app picker.
 */
data class AppSummary(
  val appId: String,
  val eventCount: Int,
  val lastSeen: Long,
  val platforms: List<String>,
)

/**
 * One-shot fetcher for `GET /apps`. Derives the `/apps` URL from the
 * user-supplied poll endpoint (which typically points at `/traces`),
 * preserving any query string. Safe to call from a background thread.
 *
 * Returns an empty list against servers that don't implement `/apps`
 * yet (404), so older sample-servers don't break the plugin.
 */
class AppsFetcher(
  tracesEndpoint: String,
  private val headers: Map<String, String> = emptyMap(),
) {
  private val appsUrl: String = deriveAppsUrl(tracesEndpoint)
  private val gson = Gson()

  fun fetch(): List<AppSummary> = try {
    val body = HttpRequests.request(appsUrl)
      .connectTimeout(5000)
      .readTimeout(5000)
      .apply { headers.forEach { (k, v) -> tuner { c -> c.setRequestProperty(k, v) } } }
      .readString()
    val arr = gson.fromJson(body, JsonArray::class.java) ?: return emptyList()
    arr.mapNotNull { el ->
      val obj = el.asJsonObject ?: return@mapNotNull null
      AppSummary(
        appId = obj.get("appId")?.asString ?: return@mapNotNull null,
        eventCount = obj.get("eventCount")?.asInt ?: 0,
        lastSeen = obj.get("lastSeen")?.asLong ?: 0L,
        platforms = obj.getAsJsonArray("platforms")?.map { it.asString } ?: emptyList(),
      )
    }
  } catch (_: Exception) {
    emptyList()
  }

  companion object {
    /**
     * Swap the last path segment of the endpoint for `/apps`. If the
     * endpoint has a query string, it's dropped for the `/apps` call
     * (server-side /apps takes no query params today).
     */
    internal fun deriveAppsUrl(tracesEndpoint: String): String {
      val (base, _) = tracesEndpoint.split('?', limit = 2).let {
        it[0] to if (it.size > 1) it[1] else ""
      }
      val slash = base.lastIndexOf('/')
      val root = if (slash > 0) base.substring(0, slash) else base
      return "$root/apps"
    }
  }
}
