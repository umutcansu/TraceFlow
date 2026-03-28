package io.github.umutcansu.traceflow.studio.logcat

import io.github.umutcansu.traceflow.studio.model.TraceEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts an ADB logcat process, parses lines with the JSON tag,
 * and invokes the [onEvent] callback for each parsed TraceEvent.
 */
class LogcatMonitor(
  private val project: Project,
  private val onEvent: (TraceEvent) -> Unit,
) : Disposable {

  private val running = AtomicBoolean(false)
  private var process: Process? = null
  private var readerThread: Thread? = null
  var lastError: String? = null
    private set

  fun start(deviceSerial: String? = null) {
    if (running.getAndSet(true)) return
    lastError = null

    val adb = findAdb()
    if (adb == null) {
      lastError = "ADB not found"
      running.set(false)
      return
    }

    val cmd = buildList {
      add(adb)
      if (deviceSerial != null) { add("-s"); add(deviceSerial) }
      add("logcat")
      add("-v"); add("threadtime")
    }

    try {
      process = ProcessBuilder(cmd)
        .redirectErrorStream(true)
        .start()
    } catch (e: Exception) {
      lastError = "Failed to start ADB: ${e.message}"
      running.set(false)
      return
    }

    readerThread = Thread({
      try {
        BufferedReader(InputStreamReader(process!!.inputStream)).use { reader ->
          var line: String? = null
          while (running.get() && reader.readLine().also { line = it } != null) {
            line?.let { TraceLogParser.parseLine(it) }?.let { onEvent(it) }
          }
        }
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      } catch (e: Exception) {
        lastError = "Logcat read error: ${e.message}"
      }
    }, "TraceFlow-LogcatMonitor").also {
      it.isDaemon = true
      it.start()
    }
  }

  fun stop() {
    running.set(false)
    process?.destroyForcibly()
    readerThread?.interrupt()
    readerThread = null
  }

  override fun dispose() = stop()

  fun resolvedAdbPath(): String? = findAdb()

  private fun findAdb(): String? {
    // 1. SDK path from local.properties or IdeSdks
    val projectSdk = findSdkFromLocalProperties()
    if (projectSdk != null) {
      val adb = File(projectSdk, "platform-tools/adb")
      if (adb.exists() && adb.canExecute()) return adb.absolutePath
    }

    // 2. ANDROID_HOME / ANDROID_SDK_ROOT environment variables
    val envHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (envHome != null) {
      val adb = File(envHome, "platform-tools/adb")
      if (adb.exists() && adb.canExecute()) return adb.absolutePath
    }

    // 3. Known macOS default path
    val knownPath = File(System.getProperty("user.home"), "Library/Android/sdk/platform-tools/adb")
    if (knownPath.exists() && knownPath.canExecute()) return knownPath.absolutePath

    // 4. ADB on PATH
    return try {
      ProcessBuilder("which", "adb").start()
        .inputStream.bufferedReader().readLine()?.trim()
        ?.takeIf { it.isNotEmpty() && File(it).exists() }
    } catch (_: Exception) {
      null
    }
  }

  private fun findSdkFromLocalProperties(): String? {
    return try {
      val basePath = project.basePath ?: return null
      val localProps = File(basePath, "local.properties")
      if (!localProps.exists()) return null
      localProps.readLines()
        .firstOrNull { it.startsWith("sdk.dir") }
        ?.substringAfter('=')
        ?.trim()
    } catch (_: Exception) {
      null
    }
  }
}
