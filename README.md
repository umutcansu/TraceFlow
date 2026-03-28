# TraceFlow

Zero-code ASM bytecode tracing for Android apps. Automatically instruments all methods with entry/exit/catch/branch logging — no manual log statements needed.

## Features

- **Zero-code instrumentation** — ASM bytecode injection at compile time, no source changes required
- **Method entry/exit** — Parameters, return values, and execution duration
- **Exception tracking** — Catch blocks with try start line and exception details
- **Branch tracking** — if/else condition evaluation (optional)
- **Sensitive data masking** — Automatically masks parameters named `password`, `token`, `pin`, `secret` etc.
- **`@NotTrace` annotation** — Opt-out specific methods or entire classes
- **Android Studio plugin** — Real-time trace monitoring with filtering, grouping, and source navigation
- **Runtime toggle** — `TraceLog.enabled = false` disables all tracing without recompilation

## Setup

### 1. Add the Gradle Plugin

```kotlin
// build.gradle.kts (app module)
plugins {
  id("io.github.umutcansu.traceflow") version "1.0.0"
}

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

### 2. Install the Android Studio Plugin

Download **TraceFlow** from JetBrains Marketplace, or install manually:

**Settings → Plugins → Install Plugin from Disk** → select the `.zip` file.

### 3. Use the Plugin

1. Open the **Trace Flow** tool window (bottom panel)
2. Select your device from the dropdown
3. Click **Start**
4. Run your app — trace events appear in real time

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
// Class-level — all methods excluded
@NotTrace
class GeneratedHiltModule { ... }

// Method-level — only this method excluded
class LoginViewModel {
  @NotTrace
  private fun validateEmail(value: String): Boolean { ... }
}
```

## DSL Reference

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

## Built-in Exclusions

The following are automatically excluded from instrumentation:

- Dagger/Hilt generated classes (`Dagger*`, `Hilt_*`, `*_Factory`, `*_MembersInjector`)
- Data binding classes
- The TraceFlow runtime itself

## Studio Plugin Features

- **Flat view** — Chronological event table with sortable columns
- **Grouped view** — Tree hierarchy: Thread → Activity/Fragment → methods
- **Regex filters** — Filter by class (`.*Fragment$`) or method (`on(Create|Resume)`)
- **Instant filtering** — Results update on every keystroke
- **Source navigation** — Double-click any event to jump to the source line
- **Session export/import** — Save traces as JSON, load previous sessions

## Architecture

```
TraceFlow/
├── runtime/        → Android library: TraceLog + @NotTrace (Maven Central)
├── gradle-plugin/  → Gradle plugin: ASM bytecode injection (Gradle Plugin Portal)
└── studio-plugin/  → Android Studio plugin: trace viewer (JetBrains Marketplace)
```

## Requirements

- Android Gradle Plugin 8.0+
- Kotlin 1.9+
- Android Studio Ladybug (2024.2) or newer
- minSdk 21+

## License

Apache License 2.0
