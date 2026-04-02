package io.github.umutcansu.traceflow.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class GenerateRemoteConfigTask : DefaultTask() {

  @get:Input abstract val remoteEnabled: Property<Boolean>
  @get:Input abstract val endpoint: Property<String>
  @get:Input abstract val tag: Property<String>
  @get:Input abstract val headers: MapProperty<String, String>
  @get:Input abstract val batchSize: Property<Int>
  @get:Input abstract val flushIntervalMs: Property<Long>
  @get:Input abstract val logcatEnabled: Property<Boolean>
  @get:Input abstract val allowInsecure: Property<Boolean>
  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun generate() {
    if (!remoteEnabled.get()) return

    val ep = endpoint.get()
    val isLocal = ep.contains("localhost") || ep.contains("127.0.0.1") || ep.contains("10.0.2.2")
    if (ep.startsWith("http://") && !isLocal && !allowInsecure.get()) {
      throw org.gradle.api.GradleException(
        "TraceFlow: Remote endpoint '$ep' uses insecure HTTP. " +
          "Use HTTPS, localhost, or set allowInsecure = true for development."
      )
    }
    if (ep.startsWith("http://") && allowInsecure.get()) {
      logger.warn("TraceFlow: allowInsecure is enabled. Trace data will be sent unencrypted over HTTP.")
    }

    val headersJson = headers.get().entries.joinToString(",") { (k, v) ->
      "\"${escapeJson(k)}\":\"${escapeJson(v)}\""
    }

    val json = """{
  "enabled": true,
  "endpoint": "${escapeJson(endpoint.get())}",
  "tag": "${escapeJson(tag.get())}",
  "headers": {$headersJson},
  "batchSize": ${batchSize.get()},
  "flushIntervalMs": ${flushIntervalMs.get()},
  "logcatEnabled": ${logcatEnabled.get()},
  "allowInsecure": ${allowInsecure.get()}
}"""

    val file = outputDir.get().asFile.resolve("traceflow_remote.json")
    file.parentFile.mkdirs()
    file.writeText(json)
  }

  private fun escapeJson(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")
}
