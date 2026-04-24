package io.github.umutcansu.traceflow.studio.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class TraceEventType(val label: String, val color: java.awt.Color) {
  ENTER( "ENTER",  java.awt.Color(0x2E7D32)),  // Dark green
  EXIT(  "EXIT",   java.awt.Color(0x1565C0)),  // Dark blue
  CATCH( "CATCH",  java.awt.Color(0xC62828)),  // Dark red
  BRANCH("BRANCH", java.awt.Color(0xF57F17)),  // Amber
}

data class TraceEvent(
  val type: TraceEventType,
  val className: String,
  val method: String,
  val file: String,
  val line: Int,
  val threadId: Long,
  val threadName: String,
  val timestampMs: Long,
  /** ENTER -> parameter map, EXIT -> result, CATCH -> exception info, BRANCH -> condition */
  val extra: Map<String, String> = emptyMap(),
  val deviceManufacturer: String = "",
  val deviceModel: String = "",
  val tag: String = "",
  // -- Schema v2 optional fields (all null-safe; absent for v1 events) ---------
  val platform: String? = null,     // android-jvm | react-native | ios-swift | web-js
  val appId: String? = null,
  val appVersion: String? = null,
  val buildNumber: String? = null,
  val userId: String? = null,
  val deviceId: String? = null,
  val sessionId: String? = null,
  val runtime: String? = null,
) {
  /** Short label for the Platform column: compact badges, blank when unknown. */
  val platformLabel: String get() = when (platform) {
    "android-jvm"  -> "Android"
    "react-native" -> "RN"
    "ios-swift"    -> "iOS"
    "web-js"       -> "Web"
    null, ""       -> ""
    else           -> platform  // forward-compat: unknown platform printed as-is
  }
  val dateFormatted: String
    get() = DateTimeFormatter.ofPattern("yyyy-MM-dd")
      .withZone(ZoneId.systemDefault())
      .format(Instant.ofEpochMilli(timestampMs))

  val timeFormatted: String
    get() = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
      .withZone(ZoneId.systemDefault())
      .format(Instant.ofEpochMilli(timestampMs))

  val sourceRef: String get() = "$file:$line"

  val deviceLabel: String get() = when {
    tag.isNotEmpty() && deviceModel.isNotEmpty() -> "$deviceModel ($tag)"
    tag.isNotEmpty() -> tag
    deviceModel.isNotEmpty() -> deviceModel
    else -> ""
  }

  val detail: String
    get() = when (type) {
      TraceEventType.ENTER  -> extra.entries.joinToString("  ") { "${it.key}: ${it.value}" }
      TraceEventType.EXIT   -> buildString {
        extra["result"]?.let { append("result: $it  ") }
        extra["durationMs"]?.let { append("[${formatMs(it.toLongOrNull() ?: 0)}]") }
      }
      TraceEventType.CATCH  -> buildString {
        extra["exception"]?.let { append(it) }
        extra["message"]?.let { append(": $it") }
        extra["tryStartLine"]?.let { append("  (try: line $it -> catch: line $line)") }
      }
      TraceEventType.BRANCH -> if (extra["conditionResult"] == "true")
        "TRUE -- entered if block" else "FALSE -- entered else block"
    }

  private fun formatMs(ms: Long) = when {
    ms >= 60_000 -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
    ms >= 1_000  -> "${ms / 1000}s ${ms % 1000}ms"
    else         -> "${ms}ms"
  }
}
