# Keep TraceFlow runtime classes — ASM-injected bytecode calls these at runtime.
# Without these rules, R8/ProGuard/DexProtector may strip or rename methods
# that injected bytecode depends on.
-keep class io.github.umutcansu.traceflow.TraceLog { *; }
-keep class io.github.umutcansu.traceflow.TraceFlowInitProvider
-keep @interface io.github.umutcansu.traceflow.NotTrace
-keep class io.github.umutcansu.traceflow.remote.RemoteSender { *; }

# Keep @NotTrace annotation on user classes so the ASM plugin can detect it
-keepattributes RuntimeVisibleAnnotations
