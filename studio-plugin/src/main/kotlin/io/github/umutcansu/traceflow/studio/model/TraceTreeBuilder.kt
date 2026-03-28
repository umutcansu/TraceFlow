package io.github.umutcansu.traceflow.studio.model

object TraceTreeBuilder {

    fun build(events: List<TraceEvent>): List<TraceNode> {
        val threadGroups = linkedMapOf<Long, TraceNode.ThreadGroup>()

        // Group events by thread
        val eventsByThread = events.groupBy { it.threadId }

        for ((threadId, threadEvents) in eventsByThread) {
            val first = threadEvents.first()
            val threadGroup = TraceNode.ThreadGroup(threadId, first.threadName, first.timestampMs)
            threadGroups[threadId] = threadGroup

            // Build component groups and pair ENTER/EXIT
            val componentGroups = linkedMapOf<String, TraceNode.ComponentGroup>()
            val enterStack = mutableListOf<TraceNode.MethodCall>()

            for (event in threadEvents) {
                val componentType = classifyComponent(event.className)

                // Ensure a ComponentGroup exists for this class
                val compGroup = componentGroups.getOrPut(event.className) {
                    TraceNode.ComponentGroup(
                        event.className,
                        componentType,
                        event.timestampMs,
                    ).also { threadGroup.children.add(it) }
                }

                when (event.type) {
                    TraceEventType.ENTER -> {
                        val methodCall = TraceNode.MethodCall(event)
                        enterStack.add(methodCall)
                        compGroup.children.add(methodCall)
                    }

                    TraceEventType.EXIT -> {
                        // Find matching ENTER on the stack
                        val matchIndex = enterStack.indexOfLast {
                            it.event.className == event.className && it.event.method == event.method
                        }
                        if (matchIndex >= 0) {
                            enterStack[matchIndex].exitEvent = event
                            enterStack.removeAt(matchIndex)
                        } else {
                            // No matching ENTER, add as standalone leaf
                            compGroup.children.add(TraceNode.EventLeaf(event))
                        }
                    }

                    else -> {
                        compGroup.children.add(TraceNode.EventLeaf(event))
                    }
                }
            }
        }

        return threadGroups.values.toList()
    }

    private fun classifyComponent(className: String): ComponentType = when {
        className.endsWith("Activity") -> ComponentType.ACTIVITY
        className.endsWith("Fragment") -> ComponentType.FRAGMENT
        else -> ComponentType.OTHER
    }

    /**
     * Flatten the tree into a list of (indentLevel, node) pairs for rendering
     * in a table with indentation.
     */
    fun flatten(roots: List<TraceNode>): List<FlatRow> {
        val result = mutableListOf<FlatRow>()
        fun walk(node: TraceNode, depth: Int) {
            result.add(FlatRow(depth, node))
            if (node.expanded) {
                for (child in node.children) {
                    walk(child, depth + 1)
                }
            }
        }
        for (root in roots) {
            walk(root, 0)
        }
        return result
    }

    data class FlatRow(val depth: Int, val node: TraceNode)
}
