package io.github.umutcansu.traceflow.plugin

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import io.github.umutcansu.traceflow.plugin.asm.TraceClassVisitorFactory
import org.gradle.api.Plugin
import org.gradle.api.Project

class TracingPlugin : Plugin<Project> {

  override fun apply(project: Project) {
    val ext = project.extensions.create("traceflow", TracingExtension::class.java)

    project.plugins.withId("com.android.application") { applyTracing(project, ext) }
    project.plugins.withId("com.android.library") { applyTracing(project, ext) }
  }

  private fun applyTracing(project: Project, ext: TracingExtension) {
    val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)

    androidComponents.onVariants { variant ->
      variant.instrumentation.transformClassesWith(
        TraceClassVisitorFactory::class.java,
        InstrumentationScope.PROJECT,
      ) { params ->
        params.enabled.set(ext.enabled)
        params.logParams.set(ext.entry.logParams)
        params.maskParams.set(ext.entry.maskParams)
        params.logReturnValue.set(ext.exit.logReturnValue)
        params.logDuration.set(ext.exit.logDuration)
        params.logTryCatch.set(ext.statements.logTryCatch)
        params.logBranches.set(ext.statements.logBranches)
        params.excludePackages.set(ext.filter.excludePackages)
      }

      variant.instrumentation.setAsmFramesComputationMode(
        FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS,
      )

      // Generate traceflow_remote.json into merged assets when remote is enabled
      val taskProvider = project.tasks.register(
        "generateTraceFlowRemoteConfig${variant.name.replaceFirstChar { it.uppercase() }}",
        GenerateRemoteConfigTask::class.java,
      ) { task ->
        task.enabled = ext.remote.enabled.get()
        task.remoteEnabled.set(ext.remote.enabled)
        task.endpoint.set(ext.remote.endpoint)
        task.tag.set(ext.remote.tag)
        task.headers.set(ext.remote.headers)
        task.batchSize.set(ext.remote.batchSize)
        task.flushIntervalMs.set(ext.remote.flushIntervalMs)
        task.logcatEnabled.set(ext.remote.logcatEnabled)
        task.allowInsecure.set(ext.remote.allowInsecure)
        task.outputDir.set(project.layout.buildDirectory.dir("generated/traceflow/${variant.name}/assets"))
      }

      variant.sources.assets?.addGeneratedSourceDirectory(taskProvider, GenerateRemoteConfigTask::outputDir)
    }
  }
}
