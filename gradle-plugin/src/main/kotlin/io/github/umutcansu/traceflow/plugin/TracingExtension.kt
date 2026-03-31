package io.github.umutcansu.traceflow.plugin

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class TracingExtension @Inject constructor(objects: ObjectFactory) {

  /** true = bytecode injection active, false = nothing is injected */
  val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

  val entry: EntryConfig = objects.newInstance(EntryConfig::class.java)
  val exit: ExitConfig = objects.newInstance(ExitConfig::class.java)
  val statements: StatementsConfig = objects.newInstance(StatementsConfig::class.java)
  val filter: FilterConfig = objects.newInstance(FilterConfig::class.java)
  val remote: RemoteConfig = objects.newInstance(RemoteConfig::class.java)

  fun entry(action: Action<EntryConfig>) = action.execute(entry)
  fun exit(action: Action<ExitConfig>) = action.execute(exit)
  fun statements(action: Action<StatementsConfig>) = action.execute(statements)
  fun filter(action: Action<FilterConfig>) = action.execute(filter)
  fun remote(action: Action<RemoteConfig>) = action.execute(remote)

  abstract class EntryConfig @Inject constructor(objects: ObjectFactory) {
    /** Log method parameters */
    val logParams: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

    /**
     * Parameters whose names contain any of these strings are displayed as `***`.
     * Example: listOf("password", "token", "pin", "secret")
     */
    val maskParams: ListProperty<String> = objects.listProperty(String::class.java)
      .convention(listOf("password", "token", "pin", "secret", "cvv", "ssn"))
  }

  abstract class ExitConfig @Inject constructor(objects: ObjectFactory) {
    /** Log the method return value */
    val logReturnValue: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    /** Log the method execution duration */
    val logDuration: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
  }

  abstract class StatementsConfig @Inject constructor(objects: ObjectFactory) {
    /**
     * Track try/catch blocks:
     * which line the try started, which line the catch was hit, what the exception was
     */
    val logTryCatch: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

    /**
     * Track if/else branches: which condition at which line, which direction it went.
     * WARNING: Very verbose -- only enable when needed
     */
    val logBranches: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
  }

  abstract class FilterConfig @Inject constructor(objects: ObjectFactory) {
    /**
     * Classes containing any of these package prefixes will not be traced.
     * Automatically excluded: hilt, dagger, generated, databinding, R$, BuildConfig
     */
    val excludePackages: ListProperty<String> = objects.listProperty(String::class.java)
      .convention(emptyList())
  }

  abstract class RemoteConfig @Inject constructor(objects: ObjectFactory) {
    /** Enable remote log streaming */
    val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /** HTTP endpoint URL (e.g. "https://api.example.com/traces") */
    val endpoint: Property<String> = objects.property(String::class.java).convention("")

    /** Custom HTTP headers (e.g. Authorization) */
    val headers: MapProperty<String, String> = objects.mapProperty(String::class.java, String::class.java)
      .convention(emptyMap())

    /** Number of events to batch before sending */
    val batchSize: Property<Int> = objects.property(Int::class.java).convention(10)
  }
}
