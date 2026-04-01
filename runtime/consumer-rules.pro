# Keep TraceFlow runtime classes — ASM-injected bytecode calls these at runtime.
# Without these rules, R8 may strip the methods that injected code depends on.
-keep class io.github.umutcansu.traceflow.TraceLog { *; }
-keep class io.github.umutcansu.traceflow.NotTrace
-keep class io.github.umutcansu.traceflow.remote.RemoteSender { *; }
