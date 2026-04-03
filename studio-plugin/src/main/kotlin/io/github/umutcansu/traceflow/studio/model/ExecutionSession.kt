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
  /** All unique device identifiers (model + tag) seen so far. */
  fun devices(): List<String> {
    return _events.map { it.deviceLabel }.filter { it.isNotEmpty() }.distinct()
  }

  /** All unique manufacturers seen so far. */
  fun manufacturers(): List<String> {
    return _events.map { it.deviceManufacturer }.filter { it.isNotEmpty() }.distinct()
  }

  fun filtered(
    typeFilter: Set<TraceEventType> = TraceEventType.entries.toSet(),
    classFilter: String = "",
    methodFilter: String = "",
    deviceFilter: String = "",
    manufacturerFilter: String = "",
    tagFilter: String = "",
    fromMs: Long = 0L,
    toMs: Long = Long.MAX_VALUE,
  ): List<TraceEvent> {
    val classRegex = classFilter.toSafeRegex()
    val methodRegex = methodFilter.toSafeRegex()
    return _events.filter { event ->
      event.type in typeFilter &&
        (classRegex == null || classRegex.containsMatchIn(event.className)) &&
        (methodRegex == null || methodRegex.containsMatchIn(event.method)) &&
        (deviceFilter.isEmpty() || event.deviceLabel == deviceFilter) &&
        (manufacturerFilter.isEmpty() || event.deviceManufacturer == manufacturerFilter) &&
        (tagFilter.isBlank() || event.tag.contains(tagFilter, ignoreCase = true)) &&
        event.timestampMs in fromMs..toMs
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
    val jsonList = _events.map { e ->
      TraceEventJson(
        type = e.type.name,
        `class` = e.className,
        method = e.method,
        file = e.file,
        line = e.line,
        threadId = e.threadId,
        threadName = e.threadName,
        ts = e.timestampMs,
        params = e.extra.filterKeys { it.startsWith("param") }.ifEmpty { null },
        result = e.extra["result"],
        durationMs = e.extra["durationMs"]?.toLongOrNull(),
        exception = e.extra["exception"],
        message = e.extra["message"],
        tryStartLine = e.extra["tryStartLine"]?.toIntOrNull(),
        conditionResult = e.extra["conditionResult"]?.toBooleanStrictOrNull(),
        deviceManufacturer = e.deviceManufacturer.ifEmpty { null },
        deviceModel = e.deviceModel.ifEmpty { null },
        tag = e.tag.ifEmpty { null },
      )
    }
    file.writeText(gson.toJson(jsonList))
  }

  /**
   * Reads a raw logcat text file line by line, parsing lines with the JSON tag.
   * For files captured via `adb logcat > log.txt`.
   */
  /** Returns Pair(loaded, skipped) */
  fun importFromLogcat(file: File): Pair<Int, Int> {
    val lines = file.readLines()
    val events = lines.mapNotNull { io.github.umutcansu.traceflow.studio.logcat.TraceLogParser.parseLine(it) }
    _events.clear()
    _events.addAll(events)
    val traceLines = lines.count { it.contains("TraceFlow") }
    return Pair(events.size, (traceLines - events.size).coerceAtLeast(0))
  }

  /** Returns Pair(loaded, skipped) */
  fun importFromFile(file: File): Pair<Int, Int> {
    val imported = gson.fromJson(file.readText(), Array<TraceEventJson>::class.java)
    val events = imported.mapNotNull { it.toTraceEvent() }
    _events.clear()
    _events.addAll(events)
    return Pair(events.size, imported.size - events.size)
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
    val deviceManufacturer: String?,
    val deviceModel: String?,
    val tag: String?,
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
        deviceManufacturer = deviceManufacturer ?: "",
        deviceModel = deviceModel ?: "",
        tag         = tag ?: "",
      )
    }
  }
}
