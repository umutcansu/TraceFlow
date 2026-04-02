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
- **Branch tracking** — if/else condition evaluation (optional, verbose)
- **Sensitive data masking** — Runtime masking of parameters named `password`, `token`, `pin`, `secret`, `cvv`, `ssn`
- **`@NotTrace` annotation** — Opt-out specific methods or entire classes
- **Android Studio plugin** — Real-time trace monitoring with manufacturer/device/tag filtering, grouping, and source navigation
- **Remote log streaming** — Send traces to any HTTP endpoint, monitor from Android Studio without USB
- **DSL auto-start** — Configure remote in `build.gradle`, auto-starts via ContentProvider — no code needed
- **Independent controls** — Toggle logcat and remote output separately at runtime
- **HTTPS enforced** — Insecure HTTP blocked by default, `allowInsecure` opt-in for development
- **Release tracing** — Optional `releaseEnabled` flag to inject tracing in release builds for field debugging
- **Multi-device support** — Manufacturer, device model, and tag columns with live filtering

## Installation

### 1. Add the Runtime Library

<details>
<summary><b>Kotlin DSL</b> (build.gradle.kts)</summary>

```kotlin
dependencies {
  implementation("io.github.umutcansu:traceflow-runtime:1.0.4")
}
```
</details>

<details>
<summary><b>Groovy DSL</b> (build.gradle)</summary>

```groovy
dependencies {
  implementation 'io.github.umutcansu:traceflow-runtime:1.0.4'
}
```
</details>

### 2. Apply the Gradle Plugin

<details>
<summary><b>Kotlin DSL</b> (build.gradle.kts)</summary>

```kotlin
plugins {
  id("io.github.umutcansu.traceflow") version "1.0.4"
}
```
</details>

<details>
<summary><b>Groovy DSL</b> (build.gradle)</summary>

```groovy
plugins {
  id 'io.github.umutcansu.traceflow' version '1.0.4'
}
```
</details>

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

<details open>
<summary><b>Kotlin DSL</b> (build.gradle.kts)</summary>

```kotlin
traceflow {
  enabled = true           // master switch for bytecode injection
  releaseEnabled = false   // inject tracing in release builds too (default false)

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
    logBranches = false    // WARNING: very verbose
  }

  filter {
    excludePackages = listOf(
      "com.example.generated",
      "com.example.databinding",
    )
  }

  remote {
    enabled = true
    endpoint = "https://your-server.com/traces"
    tag = "my-device"
    headers = mapOf("Authorization" to "Bearer token123")
    batchSize = 10
    flushIntervalMs = 3000
    logcatEnabled = true
    allowInsecure = false
  }
}
```
</details>

<details>
<summary><b>Groovy DSL</b> (build.gradle)</summary>

```groovy
traceflow {
  enabled = true
  releaseEnabled = false

  entry {
    logParams = true
    maskParams = ["password", "token", "pin", "secret"]
    // or: maskParams "password", "token", "pin", "secret"
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
    excludePackages = ["com.example.generated", "com.example.databinding"]
    // or: excludePackages "com.example.generated", "com.example.databinding"
  }

  remote {
    enabled = true
    endpoint = "https://your-server.com/traces"
    tag = "my-device"
    headers = ["Authorization": "Bearer token123"]
    batchSize = 10
    flushIntervalMs = 3000
    logcatEnabled = true
    allowInsecure = false
  }
}
```
</details>

### DSL Reference

| Option | Default | Description |
|--------|---------|-------------|
| `enabled` | `true` | Enable/disable bytecode instrumentation |
| `releaseEnabled` | `false` | Inject tracing in release builds (for field debugging) |
| `entry.logParams` | `true` | Log method parameters on entry |
| `entry.maskParams` | `["password","token","pin","secret","cvv","ssn"]` | Parameter names to mask with `***` at runtime |
| `exit.logReturnValue` | `true` | Log return values on exit |
| `exit.logDuration` | `true` | Log method execution time |
| `statements.logTryCatch` | `true` | Log catch block entries with try start line |
| `statements.logBranches` | `false` | Log if/else branch evaluations (verbose) |
| `filter.excludePackages` | `[]` | Package prefixes to exclude from instrumentation |
| `remote.enabled` | `false` | Enable remote log streaming (auto-starts via ContentProvider) |
| `remote.endpoint` | `""` | HTTP endpoint URL for trace events |
| `remote.tag` | `""` | Device/session identifier for remote logs |
| `remote.headers` | `{}` | Custom HTTP headers (e.g. Authorization) |
| `remote.batchSize` | `10` | Number of events to batch before sending |
| `remote.flushIntervalMs` | `3000` | Max wait time (ms) before flushing a batch |
| `remote.logcatEnabled` | `true` | Enable logcat output (set `false` for remote-only) |
| `remote.allowInsecure` | `false` | Allow insecure HTTP endpoints (only HTTPS and localhost permitted by default) |

## Usage

### Logcat Mode (USB/ADB)

1. Open the **Trace Flow** tool window (bottom panel in Android Studio)
2. Select your device from the dropdown
3. Click **Start**
4. Run your app — trace events appear in real time

### Remote Mode (no USB required)

**Option A — DSL (auto-starts, no code needed):**
```kotlin
traceflow {
  remote {
    enabled = true
    endpoint = "https://your-server.com/traces"
    tag = "pixel-7-debug"
    headers = mapOf("Authorization" to "Bearer your-token")
  }
}
```
App starts, ContentProvider reads the config and calls `TraceLog.startRemote()` automatically.

**Option B — Programmatic:**
```kotlin
TraceLog.startRemote(
  endpoint = "https://your-server.com/traces",
  tag = "pixel-7-debug",
  headers = mapOf("Authorization" to "Bearer your-token"),
)
```

**In Android Studio:**
1. Switch to the **Remote** tab
2. Enter the same endpoint URL
3. Click **Connect**
4. Trace events stream in real time — no USB cable needed

![TraceFlow Source Navigation](screenshots/source-navigation.png)

## Runtime Controls

All controls are thread-safe and take effect immediately.

### Switches

```kotlin
// Master switch — disables both logcat and remote
TraceLog.enabled = false

// Independent controls
TraceLog.logcatEnabled = false  // stop logcat output, remote continues
TraceLog.remoteEnabled = false  // stop remote sending, logcat continues
```

### Remote Management

```kotlin
// Start/stop remote programmatically
TraceLog.startRemote("https://your-server.com/traces")
TraceLog.stopRemote()

// Check remote status
TraceLog.isRemoteActive()
```

### Device Identity

```kotlin
// Change device tag at runtime (does not restart the connection)
TraceLog.deviceTag = "new-session-tag"
```

Each event automatically includes `deviceManufacturer` (e.g. "samsung") and `deviceModel` (e.g. "SM-G980F"). The `tag` is user-defined and optional.

### Parameter Masking

```kotlin
// Override mask list at runtime
TraceLog.maskParams = listOf("password", "creditCard", "ssn")

// Disable masking entirely
TraceLog.maskParams = emptyList()
```

Masking is applied at runtime — parameter names containing any mask keyword will have their values replaced with `***` in both logcat and remote output.

### Java Compatibility

All controls work from Java with identical syntax:
```java
TraceLog.enabled = false;
TraceLog.logcatEnabled = false;
TraceLog.remoteEnabled = false;
TraceLog.deviceTag = "my-device";
TraceLog.maskParams = Arrays.asList("password", "token");
TraceLog.startRemote("https://your-server.com/traces");
TraceLog.stopRemote();
```

> **Note:** Calling `startRemote()` at runtime will **override all DSL values** (endpoint, tag, headers, etc.). To change only specific fields without restarting the connection, use the individual properties (`deviceTag`, `logcatEnabled`, `remoteEnabled`).

## Release Tracing (Field Debugging)

By default, tracing is only injected into debug builds. To enable tracing in release builds for field debugging:

```kotlin
traceflow {
  releaseEnabled = true
}
```

| `enabled` | `releaseEnabled` | Debug Build | Release Build |
|-----------|-------------------|-------------|---------------|
| `true` | `false` (default) | Injection active | No injection, zero overhead |
| `true` | `true` | Injection active | Injection active, controlled at runtime |
| `false` | `*` | No injection | No injection |

When `releaseEnabled = true`, bytecode injection is present in release builds but you control activation at runtime. Typical pattern with Firebase Remote Config or your own backend:

```kotlin
class MyApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    if (!BuildConfig.DEBUG) {
      TraceLog.enabled = false  // silent until activated
      // Activate from backend when needed
      fetchRemoteFlag { shouldTrace ->
        if (shouldTrace) {
          TraceLog.enabled = true
          TraceLog.startRemote("https://your-server.com/traces")
        }
      }
    }
  }
}
```

## Studio Plugin Features

- **Flat view** — Chronological event table with sortable columns
- **Grouped view** — Tree hierarchy: Thread > Activity/Fragment > methods

![TraceFlow Grouped View](screenshots/grouped-view.png)

- **Manufacturer filter** — Filter by device manufacturer (samsung, google, etc.)
- **Device filter** — Filter by device model
- **Tag filter** — Filter by user-defined session tag
- **Regex filters** — Filter by class (`.*Fragment$`) or method (`on(Create|Resume)`)
- **Date range filter** — Filter events by time window
- **Column visibility** — Show/hide columns including Manufacturer, Device, Tag (hidden by default)
- **Instant filtering** — Results update on every keystroke
- **Smart auto-scroll** — Follows new events at bottom, stays put when scrolled up
- **Source navigation** — Double-click any event to jump to the source line
- **Session export/import** — Save traces as JSON, load previous sessions (preserves device info)
- **Color-coded events** — ENTER (green), EXIT (blue), CATCH (red), BRANCH (amber)

## Log Output

Every traced method produces dual-format output:

**Human-readable (logcat):**
```
D/TraceFlow ENTER: [LoginViewModel] reduce()  src:LoginViewModel.kt:42
                     password: ***
                     username: test@mail.com

D/TraceFlow EXIT : [LoginViewModel] reduce  [3ms]  src:LoginViewModel.kt:42

W/TraceFlow CATCH: [AuthRepository] login  src:AuthRepository.kt:67
                     try started: line 58 -> catch: line 67
                     SocketTimeoutException: Connect timed out
```

**Structured JSON (for plugin and remote):**
```json
{
  "type": "ENTER",
  "class": "LoginViewModel",
  "method": "reduce",
  "file": "LoginViewModel.kt",
  "line": 42,
  "threadId": 2,
  "threadName": "main",
  "ts": 1712000000000,
  "deviceManufacturer": "samsung",
  "deviceModel": "SM-G980F",
  "tag": "qa-team-1",
  "params": { "username": "test@mail.com", "password": "***" }
}
```

## `@NotTrace`

Exclude specific methods or entire classes from tracing:

```kotlin
@NotTrace
class GeneratedHiltModule { ... }

class LoginViewModel {
  @NotTrace
  private fun validateEmail(value: String): Boolean { ... }
}
```

## Remote Streaming API

### Server Requirements

Your server must implement two endpoints:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/traces` | `POST` | Receives JSON array of trace events |
| `/traces?since={ts}` | `GET` | Returns events after given timestamp |

### Sample Server

A ready-to-use Ktor server is included in `sample-server/`:

```bash
cd sample-server
./gradlew run
```

The server starts on port **4567** by default (override with `PORT` env variable) and provides:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/` | `GET` | Health check |
| `/traces` | `POST` | Receive trace events |
| `/traces?since={ts}` | `GET` | Poll events after timestamp |
| `/traces` | `DELETE` | Clear all events |
| `/stats` | `GET` | Event counts by device and type |

### Security

By default, only **HTTPS** and **localhost** (`127.0.0.1`, `localhost`, `10.0.2.2`) endpoints are allowed.

- **Build time:** HTTP with a non-localhost endpoint causes a `GradleException` (build fails)
- **Runtime:** `startRemote()` with insecure HTTP throws `IllegalArgumentException`

For local development with HTTP:
```kotlin
// DSL
traceflow {
  remote {
    enabled = true
    endpoint = "http://192.168.1.80:4567/traces"
    allowInsecure = true
  }
}

// Runtime
TraceLog.startRemote(
  endpoint = "http://192.168.1.80:4567/traces",
  allowInsecure = true,
)
```

## Built-in Exclusions

The following are automatically excluded from instrumentation:

- Dagger/Hilt generated classes (`Dagger*`, `Hilt_*`, `*_Factory`, `*_MembersInjector`)
- Data binding classes
- Kotlin synthetic accessors and default stubs
- Property getters/setters
- The TraceFlow runtime itself

## Architecture

```
TraceFlow/
├── runtime/        -> Android library: TraceLog + @NotTrace (Maven Central)
├── gradle-plugin/  -> Gradle plugin: ASM bytecode injection (Gradle Plugin Portal)
├── studio-plugin/  -> Android Studio plugin: trace viewer (JetBrains Marketplace)
└── sample-server/  -> Ktor sample server for remote log streaming
```

## Requirements

- Android Gradle Plugin 8.0+
- Kotlin 1.9+
- Android Studio Ladybug (2024.2) or newer
- minSdk 21+

## License

Apache License 2.0
