package io.github.umutcansu.traceflow.studio.model

sealed class TraceNode {
    abstract val label: String
    abstract val children: MutableList<TraceNode>
    abstract val startTimeMs: Long
    var expanded: Boolean = true

    class ThreadGroup(
        val threadId: Long,
        val threadName: String,
        override val startTimeMs: Long,
    ) : TraceNode() {
        override val label: String get() = "Thread: $threadName [$threadId]"
        override val children: MutableList<TraceNode> = mutableListOf()
    }

    class ComponentGroup(
        val className: String,
        val componentType: ComponentType,
        override val startTimeMs: Long,
    ) : TraceNode() {
        override val label: String
            get() = "${componentType.name}: $className"
        override val children: MutableList<TraceNode> = mutableListOf()
    }

    class MethodCall(
        val event: TraceEvent,
        var exitEvent: TraceEvent? = null,
    ) : TraceNode() {
        override val label: String
            get() {
                val duration = exitEvent?.extra?.get("durationMs")?.let { " [${it}ms]" } ?: ""
                return "${event.className}.${event.method}$duration"
            }
        override val children: MutableList<TraceNode> = mutableListOf()
        override val startTimeMs: Long get() = event.timestampMs
    }

    class EventLeaf(val event: TraceEvent) : TraceNode() {
        override val label: String get() = "${event.type.label}: ${event.className}.${event.method}"
        override val children: MutableList<TraceNode> = mutableListOf()
        override val startTimeMs: Long get() = event.timestampMs
    }
}

enum class ComponentType { ACTIVITY, FRAGMENT, OTHER }
