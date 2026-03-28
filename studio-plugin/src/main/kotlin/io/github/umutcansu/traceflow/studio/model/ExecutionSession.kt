package io.github.umutcansu.traceflow.studio.model

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class ExecutionSession {

  private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
  private val _events = CopyOnWriteArrayList<TraceEvent>()

  val events: List<TraceEvent> get() = _events

  fun add(event: TraceEvent) {
    _events.add(event)
  }

  fun clear() {
    _events.clear()
  }

  /** Returns a filtered list of events */
  fun filtered(
    typeFilter: Set<TraceEventType> = TraceEventType.entries.toSet(),
    classFilter: String = "",
    methodFilter: String = "",
  ): List<TraceEvent> {
    val classRegex = classFilter.toSafeRegex()
    val methodRegex = methodFilter.toSafeRegex()
    return _events.filter { event ->
      event.type in typeFilter &&
        (classRegex == null || classRegex.containsMatchIn(event.className)) &&
        (methodRegex == null || methodRegex.containsMatchIn(event.method))
    }
  }

  private fun String.toSafeRegex(): Regex? {
    if (isBlank()) return null
    return try {
      Regex(this, RegexOption.IGNORE_CASE)
    } catch (_: Exception) {
      // Invalid regex -- fallback to literal contains
      Regex(Regex.escape(this), RegexOption.IGNORE_CASE)
    }
  }

  fun exportToFile(file: File) {
    file.writeText(gson.toJson(_events))
  }

  /**
   * Reads a raw logcat text file line by line, parsing lines with the JSON tag.
   * For files captured via `adb logcat > log.txt`.
   */
  fun importFromLogcat(file: File): Int {
    val events = file.readLines()
      .mapNotNull { io.github.umutcansu.traceflow.studio.logcat.TraceLogParser.parseLine(it) }
    _events.clear()
    _events.addAll(events)
    return events.size
  }

  fun importFromFile(file: File): Int {
    val imported = gson.fromJson(file.readText(), Array<TraceEventJson>::class.java)
    val events = imported.mapNotNull { it.toTraceEvent() }
    _events.clear()
    _events.addAll(events)
    return events.size
  }

  // Intermediate model for JSON deserialization
  private data class TraceEventJson(
    val type: String?,
    val `class`: String?,
    val method: String?,
    val file: String?,
    val line: Int?,
    val threadId: Long?,
    val threadName: String?,
    val ts: Long?,
    val params: Map<String, String>?,
    val result: String?,
    val durationMs: Long?,
    val exception: String?,
    val message: String?,
    val tryStartLine: Int?,
    val conditionResult: Boolean?,
  ) {
    fun toTraceEvent(): TraceEvent? {
      val eventType = when (type) {
        "ENTER"  -> TraceEventType.ENTER
        "EXIT"   -> TraceEventType.EXIT
        "CATCH"  -> TraceEventType.CATCH
        "BRANCH" -> TraceEventType.BRANCH
        else     -> return null
      }
      val extra = buildMap {
        params?.forEach { (k, v) -> put(k, v) }
        result?.let { put("result", it) }
        durationMs?.let { put("durationMs", it.toString()) }
        exception?.let { put("exception", it) }
        message?.let { put("message", it) }
        tryStartLine?.let { put("tryStartLine", it.toString()) }
        conditionResult?.let { put("conditionResult", it.toString()) }
      }
      return TraceEvent(
        type        = eventType,
        className   = `class` ?: "",
        method      = method ?: "",
        file        = file ?: "",
        line        = line ?: 0,
        threadId    = threadId ?: 0,
        threadName  = threadName ?: "",
        timestampMs = ts ?: 0,
        extra       = extra,
      )
    }
  }
}
