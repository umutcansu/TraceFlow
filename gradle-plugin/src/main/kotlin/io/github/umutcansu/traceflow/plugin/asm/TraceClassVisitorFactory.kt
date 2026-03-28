package io.github.umutcansu.traceflow.plugin.asm

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import io.github.umutcansu.traceflow.plugin.TracingParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

abstract class TraceClassVisitorFactory : AsmClassVisitorFactory<TracingParameters> {

  override fun createClassVisitor(
    classContext: ClassContext,
    nextClassVisitor: ClassVisitor,
  ): ClassVisitor {
    // AGP injects parameters directly -- accessible via `parameters` property
    val p = parameters.get()
    val config = TraceConfig(
      logParams      = p.logParams.get(),
      maskParams     = p.maskParams.get(),
      logReturnValue = p.logReturnValue.get(),
      logDuration    = p.logDuration.get(),
      logTryCatch    = p.logTryCatch.get(),
      logBranches    = p.logBranches.get(),
    )
    return TraceClassVisitor(
      api       = Opcodes.ASM9,
      next      = nextClassVisitor,
      className = classContext.currentClassData.className,
      config    = config,
    )
  }

  override fun isInstrumentable(classData: ClassData): Boolean {
    val p = parameters.get()
    if (!p.enabled.get()) return false

    val name = classData.className

    val alwaysExcluded = listOf(
      "HiltComponents", "Hilt_", "_HiltModules", "hilt_aggregated_deps",
      "Dagger", "dagger.",
      "_MembersInjector", "_Factory", "_Impl",
      "_GeneratedInjector",
      "databinding.", "BR.", "io.github.umutcansu.traceflow",
    )
    if (alwaysExcluded.any { exclusion -> name.contains(exclusion) }) return false

    val userExclusions = p.excludePackages.get()
    if (userExclusions.any { exclusion -> name.contains(exclusion) }) return false

    return true
  }
}

data class TraceConfig(
  val logParams: Boolean,
  val maskParams: List<String>,
  val logReturnValue: Boolean,
  val logDuration: Boolean,
  val logTryCatch: Boolean,
  val logBranches: Boolean,
)
