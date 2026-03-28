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
    }
  }
}
