/**
 * Event types accepted by the TraceFlow server. Mirrors the Kotlin
 * TraceEventType enum on the Android runtime and the server data class.
 */
export type TraceEventType = "ENTER" | "EXIT" | "CATCH" | "BRANCH";

/**
 * Platform identifier. Matches the server's `platform` column.
 * The JS runtime only emits "react-native" or "web-js"; the other two
 * values exist in the union for type compatibility with downstream
 * consumers that read mixed event streams.
 */
export type Platform = "react-native" | "web-js" | "android-jvm" | "ios-swift";

/**
 * Wire-format of a single trace event. Must stay aligned with
 * sample-server/.../Main.kt#TraceEvent and runtime/.../TraceLog#emitJson.
 *
 * All v2 fields are filled in by the runtime on every emit when the client
 * has been initialised via initTraceFlow — unlike the Android runtime,
 * there is no "v1 mode" to preserve here.
 */
export interface TraceEvent {
  type: TraceEventType;
  class?: string;
  method?: string;
  file?: string;
  line?: number;
  threadId?: number;
  threadName?: string;
  ts: number;
  tag?: string;
  // Legacy / Android-compat optional fields, rarely populated from JS.
  deviceManufacturer?: string;
  deviceModel?: string;
  params?: Record<string, string>;
  result?: string;
  durationMs?: number;
  exception?: string;
  message?: string;
  // Schema v2 fields.
  schemaVersion: 2;
  platform: Platform;
  runtime?: string;
  appId: string;
  appVersion?: string;
  buildNumber?: string;
  userId?: string;
  deviceId: string;
  sessionId: string;
  stack?: string[];
  sourceMapId?: string;
  isMinified?: boolean;
}
