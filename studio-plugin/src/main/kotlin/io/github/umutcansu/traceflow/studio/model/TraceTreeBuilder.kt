package io.github.umutcansu.traceflow.studio.model

enum class GroupingMode(val label: String) {
    THREAD("Thread"),
    CLASS("Class"),
    MANUFACTURER("Manufacturer"),
    DEVICE("Device"),
}

object TraceTreeBuilder {

    fun build(events: List<TraceEvent>, mode: GroupingMode = GroupingMode.THREAD): List<TraceNode> =
        when (mode) {
            GroupingMode.THREAD -> buildByThread(events)
            GroupingMode.CLASS -> buildByClass(events)
            GroupingMode.MANUFACTURER -> buildByManufacturer(events)
            GroupingMode.DEVICE -> buildByDevice(events)
        }

    /** Thread → ComponentGroup → Methods (original behaviour) */
    private fun buildByThread(events: List<TraceEvent>): List<TraceNode> {
        val threadGroups = linkedMapOf<Long, TraceNode.ThreadGroup>()

        val eventsByThread = events.groupBy { it.threadId }

        for ((threadId, threadEvents) in eventsByThread) {
            val first = threadEvents.first()
            val threadGroup = TraceNode.ThreadGroup(threadId, first.threadName, first.timestampMs)
            threadGroups[threadId] = threadGroup
            buildComponentChildren(threadEvents, threadGroup.children)
        }

        return threadGroups.values.toList()
    }

    /** ComponentGroup → Methods (flat by class, no thread grouping) */
    private fun buildByClass(events: List<TraceEvent>): List<TraceNode> {
        val roots = mutableListOf<TraceNode>()
        buildComponentChildren(events, roots)
        return roots
    }

    /** ManufacturerGroup → ComponentGroup → Methods */
    private fun buildByManufacturer(events: List<TraceEvent>): List<TraceNode> {
        val groups = linkedMapOf<String, TraceNode.ManufacturerGroup>()

        val eventsByMfr = events.groupBy { it.deviceManufacturer.ifEmpty { "(unknown)" } }

        for ((mfr, mfrEvents) in eventsByMfr) {
            val first = mfrEvents.first()
            val group = TraceNode.ManufacturerGroup(mfr, first.timestampMs)
            groups[mfr] = group
            buildComponentChildren(mfrEvents, group.children)
        }

        return groups.values.toList()
    }

    /** DeviceGroup → ComponentGroup → Methods */
    private fun buildByDevice(events: List<TraceEvent>): List<TraceNode> {
        val groups = linkedMapOf<String, TraceNode.DeviceGroup>()

        val eventsByDevice = events.groupBy { it.deviceLabel.ifEmpty { "(unknown)" } }

        for ((label, deviceEvents) in eventsByDevice) {
            val first = deviceEvents.first()
            val group = TraceNode.DeviceGroup(label, first.timestampMs)
            groups[label] = group
            buildComponentChildren(deviceEvents, group.children)
        }

        return groups.values.toList()
    }

    /** Shared logic: build ComponentGroup → MethodCall/EventLeaf children and pair ENTER/EXIT */
    private fun buildComponentChildren(events: List<TraceEvent>, target: MutableList<TraceNode>) {
        val componentGroups = linkedMapOf<String, TraceNode.ComponentGroup>()
        val enterStack = mutableListOf<TraceNode.MethodCall>()

        for (event in events) {
            val componentType = classifyComponent(event.className)

            val compGroup = componentGroups.getOrPut(event.className) {
                TraceNode.ComponentGroup(
                    event.className,
                    componentType,
                    event.timestampMs,
                ).also { target.add(it) }
            }

            when (event.type) {
                TraceEventType.ENTER -> {
                    val methodCall = TraceNode.MethodCall(event)
                    enterStack.add(methodCall)
                    compGroup.children.add(methodCall)
                }

                TraceEventType.EXIT -> {
                    val matchIndex = enterStack.indexOfLast {
                        it.event.className == event.className && it.event.method == event.method
                    }
                    if (matchIndex >= 0) {
                        enterStack[matchIndex].exitEvent = event
                        enterStack.removeAt(matchIndex)
                    } else {
                        compGroup.children.add(TraceNode.EventLeaf(event))
                    }
                }

                else -> {
                    compGroup.children.add(TraceNode.EventLeaf(event))
                }
            }
        }
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
