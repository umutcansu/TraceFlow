package io.github.umutcansu.traceflow

/**
 * Classes or methods annotated with this are excluded from automatic tracing.
 *
 * Class level -- disables tracing for all methods:
 * ```kotlin
 * @NotTrace
 * class HeavyUtilityClass { ... }
 * ```
 *
 * Method level -- disables tracing for this method only:
 * ```kotlin
 * class LoginViewModel {
 *   @NotTrace
 *   private fun validateEmail(email: String): Boolean { ... }
 * }
 * ```
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class NotTrace
