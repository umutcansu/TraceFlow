# TraceFlow

[![Maven Central](https://img.shields.io/maven-central/v/io.github.umutcansu/traceflow-runtime)](https://central.sonatype.com/artifact/io.github.umutcansu/traceflow-runtime)
[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.umutcansu.traceflow)](https://plugins.gradle.org/plugin/io.github.umutcansu.traceflow)
[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/30959)](https://plugins.jetbrains.com/plugin/30959-traceflow)

Zero-code ASM bytecode tracing for Android apps. Automatically instruments all methods with entry/exit/catch/branch logging — no manual log statements needed.

![TraceFlow Flat View](screenshots/flat-view.png)

## Features

- **Zero-code instrumentation** — ASM bytecode injection at compile time, no source changes required
- **Method entry/exit** — Parameters, return values, and execution duration
- **Exception tracking** — Catch blocks with try start line and exception details
- **Branch tracking** — if/else condition evaluation (optional)
- **Sensitive data masking** — Automatically masks parameters named `password`, `token`, `pin`, `secret` etc.
- **`@NotTrace` annotation** — Opt-out specific methods or entire classes
- **Android Studio plugin** — Real-time trace monitoring with filtering, grouping, and source navigation
- **Remote log streaming** — Send traces to any HTTP endpoint, monitor from Android Studio without USB
- **Runtime toggle** — `TraceLog.enabled = false` disables all tracing without recompilation

## Installation

### 1. Add the Runtime Library

```kotlin
// build.gradle.kts (app module)
dependencies {
  implementation("io.github.umutcansu:traceflow-runtime:1.0.4")
}
```

### 2. Apply the Gradle Plugin

```kotlin
// build.gradle.kts (app module)
plugins {
  id("io.github.umutcansu.traceflow") version "1.0.4"
}
```

### 3. Install the Android Studio Plugin

**Option A — JetBrains Marketplace (recommended):**

1. Android Studio > **Settings** > **Plugins** > **Marketplace**
2. Search **"TraceFlow"**
3. Click **Install** and restart

**Option B — Manual install:**

1. Download the latest `.zip` from [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/30959-traceflow)
2. Android Studio > **Settings** > **Plugins** > **Gear icon** > **Install Plugin from Disk**
3. Select the downloaded `.zip` file and restart

## Configuration

```kotlin
// build.gradle.kts (app module)
traceflow {
  enabled = true

  entry {
    logParams = true
    maskParams = listOf("password", "token", "pin", "secret")
  }

  exit {
    logReturnValue = true
    logDuration = true
  }

  statements {
    logTryCatch = true
    logBranches = false
  }

  filter {
    excludePackages = listOf(
      "com.example.generated",
      "com.example.databinding",
    )
  }
}
```

### DSL Reference

| Option | Default | Description |
|--------|---------|-------------|
| `enabled` | `true` | Enable/disable bytecode instrumentation |
| `entry.logParams` | `false` | Log method parameters on entry |
| `entry.maskParams` | `[]` | Parameter names to mask with `***` |
| `exit.logReturnValue` | `false` | Log return values on exit |
| `exit.logDuration` | `false` | Log method execution time |
| `statements.logTryCatch` | `false` | Log catch block entries |
| `statements.logBranches` | `false` | Log if/else branch evaluations |
| `filter.excludePackages` | `[]` | Package prefixes to exclude from instrumentation |
| `remote.enabled` | `false` | Enable remote log streaming (auto-starts via ContentProvider) |
| `remote.endpoint` | `""` | HTTP endpoint URL for trace events |
| `remote.tag` | `""` | Device/session identifier for remote logs |
| `remote.headers` | `{}` | Custom HTTP headers (e.g. Authorization) |
| `remote.batchSize` | `10` | Number of events to batch before sending |
| `remote.flushIntervalMs` | `3000` | Max wait time (ms) before flushing a batch |
| `remote.logcatEnabled` | `true` | Enable logcat output (set `false` for remote-only in release) |
| `remote.allowInsecure` | `false` | Allow insecure HTTP endpoints (only HTTPS and localhost permitted by default) |

## Usage

### Logcat Mode (USB/ADB)

1. Open the **Trace Flow** tool window (bottom panel in Android Studio)
2. Select your device from the dropdown
3. Click **Start**
4. Run your app — trace events appear in real time

### Remote Mode (no USB required)

1. Set up a server with `POST /traces` and `GET /traces?since={ts}` endpoints
2. In your app, start remote streaming:
```kotlin
TraceLog.startRemote(
  endpoint = "https://your-server.com/traces",
  tag = "pixel-7-debug",  // identify this device in the plugin
  headers = mapOf("Authorization" to "Bearer your-token")
)
```
Or configure via Gradle DSL (auto-starts, no code needed):
```kotlin
traceflow {
  remote {
    enabled = true
    endpoint = "https://your-server.com/traces"
    tag = "pixel-7-debug"
    headers = mapOf("Authorization" to "Bearer your-token")
    logcatEnabled = false  // remote-only, no logcat output
  }
}
```
3. In Android Studio, click **Switch to Remote**, enter the same endpoint, and click **Connect**
4. Trace events stream in real time — no USB cable needed

![TraceFlow Source Navigation](screenshots/source-navigation.png)

## Studio Plugin Features

- **Flat view** — Chronological event table with sortable columns
- **Grouped view** — Tree hierarchy: Thread > Activity/Fragment > methods

![TraceFlow Grouped View](screenshots/grouped-view.png)
- **Device filtering** — Filter by device when multiple devices stream logs (remote mode)
- **Regex filters** — Filter by class (`.*Fragment$`) or method (`on(Create|Resume)`)
- **Instant filtering** — Results update on every keystroke
- **Smart auto-scroll** — Follows new events at bottom, stays put when scrolled up
- **Source navigation** — Double-click any event to jump to the source line
- **Session export/import** — Save traces as JSON, load previous sessions
- **Color-coded events** — ENTER (green), EXIT (blue), CATCH (red), BRANCH (amber)

## Log Output

Every traced method produces dual-format output:

**Human-readable (logcat):**
```
D/TraceFlow ENTER: [LoginViewModel] reduce()  src:LoginViewModel.kt:42
                     param0: UsernameChanged(value=test@mail.com)

D/TraceFlow EXIT : [LoginViewModel] reduce  [3ms]  src:LoginViewModel.kt:42

W/TraceFlow CATCH: [AuthRepository] login  src:AuthRepository.kt:67
                     try started: line 58 → catch: line 67
                     SocketTimeoutException: Connect timed out
```

**Structured JSON (for plugin parsing):**
```
D/TraceFlow JSON: {"type":"ENTER","class":"LoginViewModel","method":"reduce",...}
```

## `@NotTrace`

Exclude specific methods or entire classes:

```kotlin
@NotTrace
class GeneratedHiltModule { ... }

class LoginViewModel {
  @NotTrace
  private fun validateEmail(value: String): Boolean { ... }
}
```

## Remote Streaming API

Send trace events to any HTTP endpoint at runtime:

```kotlin
// Start remote streaming
TraceLog.startRemote("https://your-server.com/traces")

// With device tag and authentication
TraceLog.startRemote(
  endpoint = "https://your-server.com/traces",
  tag = "qa-device-1",       // identify this device in the plugin
  headers = mapOf("Authorization" to "Bearer token123"),
  batchSize = 10,             // events per batch
  flushIntervalMs = 3000L,    // max wait before flush
)

// Stop remote streaming
TraceLog.stopRemote()
```

Each remote event includes `deviceModel` (auto-detected) and `tag` (user-defined). The Android Studio plugin shows a **Device** dropdown to filter logs by device when multiple devices are streaming.

Your server must implement two endpoints:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/traces` | `POST` | Receives JSON array of trace events |
| `/traces?since={ts}` | `GET` | Returns events after given timestamp |

Logcat output continues regardless of remote streaming (unless `logcatEnabled = false`).

### Security

By default, only **HTTPS** and **localhost** (`127.0.0.1`, `localhost`, `10.0.2.2`) endpoints are allowed. Insecure HTTP to remote hosts will fail at both build time and runtime.

For local development with HTTP:
```kotlin
// DSL — allow HTTP for development
traceflow {
  remote {
    enabled = true
    endpoint = "http://192.168.1.80:4567/traces"
    allowInsecure = true  // required for non-localhost HTTP
  }
}

// Runtime — allow HTTP programmatically
TraceLog.startRemote(
  endpoint = "http://192.168.1.80:4567/traces",
  allowInsecure = true,
)
```

## Runtime Controls

Control tracing at runtime without recompilation:

```kotlin
// Master switch — disables both logcat and remote
TraceLog.enabled = false

// Independent controls
TraceLog.logcatEnabled = false  // stop logcat, remote continues
TraceLog.remoteEnabled = false  // stop remote, logcat continues

// Change device tag at runtime
TraceLog.deviceTag = "new-session-tag"

// Start/stop remote programmatically
TraceLog.startRemote("https://your-server.com/traces")
TraceLog.stopRemote()
```

All controls work from both Kotlin and Java:
```java
TraceLog.enabled = false;
TraceLog.logcatEnabled = false;
TraceLog.deviceTag = "my-device";
TraceLog.startRemote("https://your-server.com/traces");
```

## Built-in Exclusions

The following are automatically excluded from instrumentation:

- Dagger/Hilt generated classes (`Dagger*`, `Hilt_*`, `*_Factory`, `*_MembersInjector`)
- Data binding classes
- The TraceFlow runtime itself

## Architecture

```
TraceFlow/
├── runtime/        → Android library: TraceLog + @NotTrace (Maven Central)
├── gradle-plugin/  → Gradle plugin: ASM bytecode injection (Gradle Plugin Portal)
├── studio-plugin/  → Android Studio plugin: trace viewer (JetBrains Marketplace)
└── sample-server/  → Ktor sample server for remote log streaming
```

## Requirements

- Android Gradle Plugin 8.0+
- Kotlin 1.9+
- Android Studio Ladybug (2024.2) or newer
- minSdk 21+

## License

Apache License 2.0
