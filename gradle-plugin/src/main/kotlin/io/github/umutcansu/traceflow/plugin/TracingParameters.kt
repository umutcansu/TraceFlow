package io.github.umutcansu.traceflow.plugin

import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input

interface TracingParameters : InstrumentationParameters {
  @get:Input val enabled: Property<Boolean>
  @get:Input val logParams: Property<Boolean>
  @get:Input val maskParams: ListProperty<String>
  @get:Input val logReturnValue: Property<Boolean>
  @get:Input val logDuration: Property<Boolean>
  @get:Input val logTryCatch: Property<Boolean>
  @get:Input val logBranches: Property<Boolean>
  @get:Input val excludePackages: ListProperty<String>
}
