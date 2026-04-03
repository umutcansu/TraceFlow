package io.github.umutcansu.traceflow.studio.ui

import io.github.umutcansu.traceflow.studio.editor.TraceHighlighter
import io.github.umutcansu.traceflow.studio.logcat.LogcatMonitor
import io.github.umutcansu.traceflow.studio.remote.RemoteLogPoller
import io.github.umutcansu.traceflow.studio.model.ExecutionSession
import io.github.umutcansu.traceflow.studio.model.TraceEvent
import io.github.umutcansu.traceflow.studio.model.TraceEventType
import io.github.umutcansu.traceflow.studio.model.GroupingMode
import io.github.umutcansu.traceflow.studio.model.TraceNode
import io.github.umutcansu.traceflow.studio.model.TraceTreeBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.io.File
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class TraceFlowWindowFactory : ToolWindowFactory, com.intellij.openapi.project.DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = TraceFlowPanel(project)
    val content = toolWindow.contentManager.factory.createContent(panel, "", false)
    toolWindow.contentManager.addContent(content)
  }
}

class TraceFlowPanel(private val project: Project) : JPanel(BorderLayout()) {

  private val session = ExecutionSession()
  private val tableModel = EventTableModel()
  private val table = JBTable(tableModel)
  private val monitor = LogcatMonitor(project) { event -> addEvent(event) }

  // Filter fields
  private val classFilter  = JTextField(15).apply { toolTipText = "Regex filter (e.g. .*Fragment$, Login.*)" }
  private val methodFilter = JTextField(15).apply { toolTipText = "Regex filter (e.g. on(Create|Resume), reduce)" }
  private val tagFilter    = JTextField(10).apply { toolTipText = "Filter by tag (e.g. qa-team-1)" }
  private val manufacturerComboFilter = JComboBox<String>().apply {
    addItem("All Manufacturers")
    addActionListener { refreshTable() }
    toolTipText = "Filter by manufacturer"
  }
  private val deviceComboFilter = JComboBox<String>().apply {
    addItem("All Devices")
    addActionListener { refreshTable() }
    toolTipText = "Filter by device (model + tag)"
  }
  private val typeFilters  = TraceEventType.entries.associateWith { JCheckBox(it.label, true) }

  // Date range filter
  private val fromTimeField = JTextField(16).apply { toolTipText = "From (yyyy-MM-dd HH:mm:ss or HH:mm:ss)" }
  private val toTimeField   = JTextField(16).apply { toolTipText = "To (yyyy-MM-dd HH:mm:ss or HH:mm:ss). Leave empty for no upper bound." }

  // Column visibility
  private val columnNames = listOf("Date", "Time", "Type", "Class", "Method", "File:Line", "Manufacturer", "Device", "Tag", "Detail")
  private val columnVisible = columnNames.associateWith { JCheckBox(it, it !in listOf("Manufacturer", "Device", "Tag")) }

  // Grouped view column visibility
  private val groupedColumnNames = listOf("Label", "Time", "Type", "File:Line", "Manufacturer", "Device", "Tag", "Detail")
  private val groupedColumnVisible = groupedColumnNames.associateWith { JCheckBox(it, it !in listOf("Manufacturer", "Device", "Tag")) }

  // Grouped view model
  private val groupedModel = GroupedTableModel()
  private val groupedTable = JBTable(groupedModel)

  // Grouping mode selector
  private val groupingCombo = JComboBox(GroupingMode.entries.map { it.label }.toTypedArray()).apply {
    selectedIndex = 0
    addActionListener { refreshTable() }
    isVisible = false
  }

  // Remote poller
  private var remotePoller: RemoteLogPoller? = null

  // Device selector
  private val deviceCombo = JComboBox<String>()

  // Remote UI
  private val endpointField = JTextField(20).apply { toolTipText = "Remote endpoint URL (e.g. https://api.example.com/traces)" }
  private val headerField = JTextField(10).apply { toolTipText = "Authorization header value (optional)" }

  // Column width persistence
  private val savedColumnWidths = mutableMapOf<String, Int>()
  private val savedGroupedColumnWidths = mutableMapOf<String, Int>()

  // State
  private val statusIcon = JLabel("\u26AA")  // grey circle default
  private val statusLabel = JLabel("Not connected")
  private var isMonitoring = false
  private var isGroupedMode = false
  private var isRemoteMode = false
  private lateinit var startBtn: JButton
  private lateinit var stopBtn: JButton
  private lateinit var toggleBtn: JButton
  private lateinit var scrollPane: JBScrollPane
  private lateinit var logcatToolbar: JPanel
  private lateinit var remoteToolbar: JPanel

  init {
    setupTable()
    setupGroupedTable()
    initFilterListeners()

    val topPanel = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    setupToolbar().also { topPanel.add(it) }
    add(topPanel, BorderLayout.NORTH)

    scrollPane = JBScrollPane(table)
    add(scrollPane, BorderLayout.CENTER)

    // Apply initial column visibility (Device/Tag hidden by default)
    applyColumnVisibility()
    applyGroupedColumnVisibility()

    // Auto-load devices on panel creation
    refreshDevices()
  }

  // -- Flat table setup -------------------------------------------------------

  private fun setupTable() {
    table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
    table.autoCreateRowSorter = true
    table.columnModel.getColumn(0).preferredWidth = 80   // Date
    table.columnModel.getColumn(1).preferredWidth = 90   // Time
    table.columnModel.getColumn(2).preferredWidth = 60   // Type
    table.columnModel.getColumn(3).preferredWidth = 180  // Class
    table.columnModel.getColumn(4).preferredWidth = 140  // Method
    table.columnModel.getColumn(5).preferredWidth = 140  // File:Line
    table.columnModel.getColumn(6).preferredWidth = 90   // Manufacturer
    table.columnModel.getColumn(7).preferredWidth = 100  // Device
    table.columnModel.getColumn(8).preferredWidth = 80   // Tag
    table.columnModel.getColumn(9).preferredWidth = 400  // Detail

    // Color the Type column
    table.columnModel.getColumn(2).cellRenderer = TypeColorRenderer()

    // Double-click to navigate
    table.addMouseListener(object : java.awt.event.MouseAdapter() {
      override fun mouseClicked(e: java.awt.event.MouseEvent) {
        if (e.clickCount == 2) {
          val viewRow = table.selectedRow
          if (viewRow < 0) return
          val modelRow = table.convertRowIndexToModel(viewRow)
          tableModel.eventAt(modelRow)?.takeIf { it.line > 0 }?.let { TraceHighlighter.navigateTo(project, it) }
        }
      }
    })
  }

  // -- Grouped table setup ----------------------------------------------------

  private fun setupGroupedTable() {
    groupedTable.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
    groupedTable.columnModel.getColumn(0).preferredWidth = 500  // Label
    groupedTable.columnModel.getColumn(1).preferredWidth = 90   // Time
    groupedTable.columnModel.getColumn(2).preferredWidth = 60   // Type
    groupedTable.columnModel.getColumn(3).preferredWidth = 140  // File:Line
    groupedTable.columnModel.getColumn(4).preferredWidth = 90   // Manufacturer
    groupedTable.columnModel.getColumn(5).preferredWidth = 100  // Device
    groupedTable.columnModel.getColumn(6).preferredWidth = 80   // Tag
    groupedTable.columnModel.getColumn(7).preferredWidth = 400  // Detail

    groupedTable.columnModel.getColumn(0).cellRenderer = IndentRenderer()

    // Double-click to navigate (for MethodCall and EventLeaf)
    groupedTable.addMouseListener(object : java.awt.event.MouseAdapter() {
      override fun mouseClicked(e: java.awt.event.MouseEvent) {
        if (e.clickCount == 2) {
          val row = groupedTable.selectedRow
          if (row < 0) return
          val flatRow = groupedModel.rowAt(row) ?: return
          val event = when (val node = flatRow.node) {
            is TraceNode.MethodCall -> node.exitEvent ?: node.event
            is TraceNode.EventLeaf -> node.event
            else -> null
          }
          event?.takeIf { it.line > 0 }?.let { TraceHighlighter.navigateTo(project, it) }
        } else if (e.clickCount == 1) {
          // Single click on group nodes toggles expand/collapse
          val row = groupedTable.selectedRow
          if (row < 0) return
          val flatRow = groupedModel.rowAt(row) ?: return
          val node = flatRow.node
          if (node.children.isNotEmpty()) {
            node.expanded = !node.expanded
            refreshTable()
          }
        }
      }
    })
  }

  // -- Toolbar ----------------------------------------------------------------

  private fun setupToolbar(): JPanel {
    val wrapper = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    // -- Actions row (always visible)
    val actionsRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
    val clearBtn = JButton("Clear").apply { addActionListener { clearSession() } }
    val saveBtn = JButton("Save").apply { addActionListener { saveSession() } }
    val loadBtn = JButton("Load").apply { addActionListener { loadSession() } }
    toggleBtn = JButton("Grouped").apply { addActionListener { toggleViewMode() } }
    val filtersBtn = JButton("Filters").apply { addActionListener { showFiltersPopup(this) } }
    val columnsBtn = JButton("Columns").apply { addActionListener { showColumnsPopup(this) } }
    actionsRow.add(clearBtn)
    actionsRow.add(saveBtn)
    actionsRow.add(loadBtn)
    actionsRow.add(JSeparator(SwingConstants.VERTICAL))
    actionsRow.add(toggleBtn)
    actionsRow.add(groupingCombo)
    actionsRow.add(filtersBtn)
    actionsRow.add(columnsBtn)
    actionsRow.add(JSeparator(SwingConstants.VERTICAL))
    actionsRow.add(statusIcon)
    actionsRow.add(statusLabel)
    wrapper.add(actionsRow)

    // -- Source tabs (Logcat / Remote)
    val sourceTabs = JTabbedPane(JTabbedPane.TOP).apply {
      tabLayoutPolicy = JTabbedPane.WRAP_TAB_LAYOUT
    }

    // Logcat tab content
    logcatToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
    startBtn = JButton("Start").apply {
      isEnabled = false
      addActionListener { startMonitoring() }
    }
    stopBtn = JButton("Stop").apply {
      isEnabled = false
      addActionListener { stopMonitoring() }
    }
    val refreshDevicesBtn = JButton("Refresh").apply { addActionListener { refreshDevices() } }
    logcatToolbar.add(JLabel("Device:"))
    logcatToolbar.add(deviceCombo)
    logcatToolbar.add(refreshDevicesBtn)
    logcatToolbar.add(JSeparator(SwingConstants.VERTICAL))
    logcatToolbar.add(startBtn)
    logcatToolbar.add(stopBtn)
    sourceTabs.addTab("Logcat", logcatToolbar)

    // Remote tab content
    remoteToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
    val connectBtn = JButton("Connect").apply { addActionListener { startRemote() } }
    val disconnectBtn = JButton("Disconnect").apply { addActionListener { stopRemote() } }
    remoteToolbar.add(JLabel("Endpoint:"))
    remoteToolbar.add(endpointField)
    remoteToolbar.add(JLabel("Auth:"))
    remoteToolbar.add(headerField)
    remoteToolbar.add(connectBtn)
    remoteToolbar.add(disconnectBtn)
    sourceTabs.addTab("Remote", remoteToolbar)

    sourceTabs.addChangeListener {
      val selected = sourceTabs.selectedIndex
      if (selected == 0 && isRemoteMode) {
        stopRemote()
        isRemoteMode = false
        statusLabel.text = "Logcat mode"
      } else if (selected == 1 && !isRemoteMode) {
        if (isMonitoring) stopMonitoring()
        isRemoteMode = true
        statusLabel.text = "Remote mode"
      }
    }

    wrapper.add(sourceTabs)
    return wrapper
  }

  private fun startRemote() {
    val endpoint = endpointField.text.trim()
    if (endpoint.isEmpty()) {
      statusLabel.text = "Enter an endpoint URL"
      return
    }
    val headers = mutableMapOf<String, String>()
    val auth = headerField.text.trim()
    if (auth.isNotEmpty()) {
      headers["Authorization"] = auth
    }
    remotePoller?.stop()
    remotePoller = RemoteLogPoller(
      endpoint = endpoint,
      headers = headers,
      onEvent = { event -> addEvent(event) },
      onError = { msg -> ApplicationManager.getApplication().invokeLater { setStatus(msg, false) } },
      onConnected = { ApplicationManager.getApplication().invokeLater { setStatus("Remote connected", true) } },
    )
    remotePoller?.start()
    setStatus("Connecting...", null)
  }

  private fun stopRemote() {
    remotePoller?.stop()
    remotePoller = null
    if (isRemoteMode) setStatus("Remote disconnected", false)
  }

  // -- Filter bar -------------------------------------------------------------

  private fun initFilterListeners() {
    val filterListener = object : DocumentListener {
      override fun insertUpdate(e: DocumentEvent) = refreshTable()
      override fun removeUpdate(e: DocumentEvent) = refreshTable()
      override fun changedUpdate(e: DocumentEvent) = refreshTable()
    }
    classFilter.document.addDocumentListener(filterListener)
    methodFilter.document.addDocumentListener(filterListener)
    tagFilter.document.addDocumentListener(filterListener)
    fromTimeField.document.addDocumentListener(filterListener)
    toTimeField.document.addDocumentListener(filterListener)
    typeFilters.values.forEach { cb -> cb.addActionListener { refreshTable() } }
    columnVisible.values.forEach { cb -> cb.addActionListener { applyColumnVisibility() } }
    groupedColumnVisible.values.forEach { cb -> cb.addActionListener { applyGroupedColumnVisibility() } }
  }

  private var filtersDialog: JDialog? = null

  private fun showFiltersPopup(anchor: JButton) {
    // Toggle — close if already open
    filtersDialog?.let { it.dispose(); filtersDialog = null; return }

    val panel = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    }

    // Manufacturer + Device
    val deviceRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
    deviceRow.add(JLabel("Manufacturer:"))
    deviceRow.add(manufacturerComboFilter)
    deviceRow.add(JLabel("Device:"))
    deviceRow.add(deviceComboFilter)
    panel.add(deviceRow)

    // Class + Method + Tag
    val textRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
    textRow.add(JLabel("Class:"))
    textRow.add(classFilter)
    textRow.add(JLabel("Method:"))
    textRow.add(methodFilter)
    textRow.add(JLabel("Tag:"))
    textRow.add(tagFilter)
    panel.add(textRow)

    // Date range
    val dateRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
    dateRow.add(JLabel("From:"))
    dateRow.add(fromTimeField)
    dateRow.add(JLabel("To:"))
    dateRow.add(toTimeField)
    panel.add(dateRow)

    // Event types
    val typeRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
    typeFilters.values.forEach { typeRow.add(it) }
    panel.add(typeRow)

    val loc = anchor.locationOnScreen
    val dialog = JDialog(SwingUtilities.getWindowAncestor(this), "Filters")
    dialog.isUndecorated = true
    dialog.contentPane = panel
    dialog.pack()
    dialog.setLocation(loc.x, loc.y + anchor.height)
    dialog.isVisible = true
    dialog.addWindowListener(object : java.awt.event.WindowAdapter() {
      override fun windowDeactivated(e: java.awt.event.WindowEvent) {
        dialog.dispose()
        filtersDialog = null
      }
    })
    filtersDialog = dialog
  }

  private var columnsDialog: JDialog? = null

  private fun showColumnsPopup(anchor: JButton) {
    columnsDialog?.let { it.dispose(); columnsDialog = null; return }

    val panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
    val visibleMap = if (isGroupedMode) groupedColumnVisible else columnVisible
    visibleMap.forEach { (_, cb) -> panel.add(cb) }

    val loc = anchor.locationOnScreen
    val dialog = JDialog(SwingUtilities.getWindowAncestor(this), "Columns")
    dialog.isUndecorated = true
    dialog.contentPane = panel
    dialog.pack()
    dialog.setLocation(loc.x, loc.y + anchor.height)
    dialog.isVisible = true
    dialog.addWindowListener(object : java.awt.event.WindowAdapter() {
      override fun windowDeactivated(e: java.awt.event.WindowEvent) {
        dialog.dispose()
        columnsDialog = null
      }
    })
    columnsDialog = dialog
  }

  private fun applyColumnVisibility() {
    val cm = table.columnModel
    for (i in columnNames.indices) {
      val col = cm.getColumn(i)
      val name = columnNames[i]
      val visible = columnVisible[name]?.isSelected ?: true
      if (visible) {
        val saved = savedColumnWidths[name] ?: col.preferredWidth.coerceAtLeast(60)
        col.minWidth = 15
        col.maxWidth = Int.MAX_VALUE
        col.preferredWidth = saved
        col.width = saved
      } else {
        if (col.width > 0) savedColumnWidths[name] = col.width
        col.minWidth = 0
        col.maxWidth = 0
        col.preferredWidth = 0
        col.width = 0
      }
    }
    table.revalidate()
    table.repaint()
  }

  private fun applyGroupedColumnVisibility() {
    val cm = groupedTable.columnModel
    for (i in groupedColumnNames.indices) {
      val col = cm.getColumn(i)
      val name = groupedColumnNames[i]
      val visible = groupedColumnVisible[name]?.isSelected ?: true
      if (visible) {
        val saved = savedGroupedColumnWidths[name] ?: col.preferredWidth.coerceAtLeast(60)
        col.minWidth = 15
        col.maxWidth = Int.MAX_VALUE
        col.preferredWidth = saved
        col.width = saved
      } else {
        if (col.width > 0) savedGroupedColumnWidths[name] = col.width
        col.minWidth = 0
        col.maxWidth = 0
        col.preferredWidth = 0
        col.width = 0
      }
    }
    groupedTable.revalidate()
    groupedTable.repaint()
  }

  private fun setStatus(text: String, connected: Boolean? = null) {
    statusLabel.text = text
    statusIcon.text = when (connected) {
      true  -> "\uD83D\uDFE2"  // green circle
      false -> "\uD83D\uDD34"  // red circle
      null  -> "\u26AA"         // grey circle
    }
  }

  private fun parseTimeToMs(text: String): Long? {
    if (text.isBlank()) return null
    return try {
      val trimmed = text.trim()
      val ldt = if (trimmed.contains("-")) {
        // yyyy-MM-dd HH:mm:ss
        java.time.LocalDateTime.parse(trimmed, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
      } else {
        // HH:mm:ss — assume today
        val parts = trimmed.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val s = parts.getOrNull(2)?.toIntOrNull() ?: 0
        java.time.LocalDateTime.of(java.time.LocalDate.now(), java.time.LocalTime.of(h, m, s))
      }
      ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (_: Exception) {
      null
    }
  }

  // -- View mode toggle -------------------------------------------------------

  private fun toggleViewMode() {
    isGroupedMode = !isGroupedMode
    toggleBtn.text = if (isGroupedMode) "Flat" else "Grouped"
    groupingCombo.isVisible = isGroupedMode
    scrollPane.setViewportView(if (isGroupedMode) groupedTable else table)
    refreshTable()
  }

  // -- Operations -------------------------------------------------------------

  private fun addEvent(event: TraceEvent) {
    session.add(event)
    ApplicationManager.getApplication().invokeLater {
      // Update device combo if new device seen
      val label = event.deviceLabel
      if (label.isNotEmpty()) {
        val existing = (0 until deviceComboFilter.itemCount).map { deviceComboFilter.getItemAt(it) }
        if (label !in existing) {
          deviceComboFilter.addItem(label)
        }
      }
      // Update manufacturer combo if new manufacturer seen
      val manufacturer = event.deviceManufacturer
      if (manufacturer.isNotEmpty()) {
        val existing = (0 until manufacturerComboFilter.itemCount).map { manufacturerComboFilter.getItemAt(it) }
        if (manufacturer !in existing) {
          manufacturerComboFilter.addItem(manufacturer)
        }
      }
      refreshTable()
    }
  }

  private fun refreshTable() {
    val activeTypes = typeFilters.entries
      .filter { it.value.isSelected }
      .map { it.key }
      .toSet()
    val selectedDevice = deviceComboFilter.selectedItem as? String ?: "All Devices"
    val selectedManufacturer = manufacturerComboFilter.selectedItem as? String ?: "All Manufacturers"
    val filtered = session.filtered(
      typeFilter          = activeTypes,
      classFilter         = classFilter.text,
      methodFilter        = methodFilter.text,
      deviceFilter        = if (selectedDevice == "All Devices") "" else selectedDevice,
      manufacturerFilter  = if (selectedManufacturer == "All Manufacturers") "" else selectedManufacturer,
      tagFilter           = tagFilter.text,
      fromMs              = parseTimeToMs(fromTimeField.text) ?: 0L,
      toMs                = parseTimeToMs(toTimeField.text) ?: Long.MAX_VALUE,
    )

    if (isGroupedMode) {
      val wasAtBottom = isScrolledToBottom(groupedTable)
      val mode = GroupingMode.entries[groupingCombo.selectedIndex]
      val roots = TraceTreeBuilder.build(filtered, mode)
      val flatRows = TraceTreeBuilder.flatten(roots)
      groupedModel.update(flatRows)
      if (wasAtBottom && groupedModel.rowCount > 0) {
        groupedTable.scrollRectToVisible(groupedTable.getCellRect(groupedModel.rowCount - 1, 0, true))
      }
    } else {
      val wasAtBottom = isScrolledToBottom(table)
      tableModel.update(filtered)
      if (wasAtBottom && tableModel.rowCount > 0) {
        table.scrollRectToVisible(table.getCellRect(tableModel.rowCount - 1, 0, true))
      }
    }
  }

  private fun refreshDevices() {
    val adb = monitor.resolvedAdbPath() ?: run {
      statusLabel.text = "ADB not found"
      return
    }
    try {
      val process = ProcessBuilder(adb, "devices").start()
      val lines = process.inputStream.bufferedReader().readLines()
      process.waitFor()
      val devices = lines
        .drop(1) // skip "List of devices attached"
        .map { it.trim() }
        .filter { it.endsWith("device") }
        .map { it.split("\\s+".toRegex()).first() }
      deviceCombo.removeAllItems()
      devices.forEach { deviceCombo.addItem(it) }
      if (devices.isEmpty()) {
        statusLabel.text = "No devices found"
        startBtn.isEnabled = false
      } else {
        statusLabel.text = "${devices.size} device(s) found"
        if (!isMonitoring) startBtn.isEnabled = true
      }
    } catch (e: Exception) {
      statusLabel.text = "Error listing devices: ${e.message}"
    }
  }

  private fun startMonitoring() {
    if (isMonitoring) return
    val serial = deviceCombo.selectedItem as? String
    monitor.start(deviceSerial = serial)
    if (monitor.lastError != null) {
      statusLabel.text = "Error: ${monitor.lastError}"
      return
    }
    isMonitoring = true
    startBtn.isEnabled = false
    stopBtn.isEnabled = true
    val deviceInfo = serial ?: "default"
    setStatus("Monitoring ($deviceInfo)...", true)
  }

  private fun stopMonitoring() {
    isMonitoring = false
    startBtn.isEnabled = true
    stopBtn.isEnabled = false
    setStatus("Stopped", false)
    monitor.stop()
  }

  private fun isScrolledToBottom(t: JTable): Boolean {
    val viewport = scrollPane.viewport
    val viewRect = viewport.viewRect
    val viewHeight = t.preferredSize.height
    return viewRect.y + viewRect.height >= viewHeight - 50
  }

  private fun clearSession() {
    session.clear()
    TraceHighlighter.clearHighlights()
    // Reset filter combos
    deviceComboFilter.removeAllItems()
    deviceComboFilter.addItem("All Devices")
    manufacturerComboFilter.removeAllItems()
    manufacturerComboFilter.addItem("All Manufacturers")
    refreshTable()
  }

  private fun loadSession() {
    val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptor(
      /* chooseFiles */ true, /* chooseFolders */ false, /* chooseJars */ false,
      /* chooseJarsAsFiles */ false, /* chooseJarContents */ false, /* chooseMultiple */ false,
    ).withTitle("Select Log File")
      .withDescription("A .json file saved by the plugin or a raw logcat .txt file")
      .withFileFilter { it.extension in listOf("json", "txt", "log") }
    val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
    val file = java.io.File(chosen.path)
    val count = try {
      if (file.readText().trimStart().startsWith("[")) {
        session.importFromFile(file)
      } else {
        session.importFromLogcat(file)
      }
    } catch (e: Exception) {
      JOptionPane.showMessageDialog(this, "Could not read file:\n${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
      return
    }
    refreshDeviceCombo()
    refreshTable()
    statusLabel.text = "$count events loaded"
  }

  private fun refreshDeviceCombo() {
    val devices = session.devices()
    val existingDevices = (0 until deviceComboFilter.itemCount).map { deviceComboFilter.getItemAt(it) }
    for (label in devices) {
      if (label !in existingDevices) {
        deviceComboFilter.addItem(label)
      }
    }
    val manufacturers = session.manufacturers()
    val existingMfr = (0 until manufacturerComboFilter.itemCount).map { manufacturerComboFilter.getItemAt(it) }
    for (mfr in manufacturers) {
      if (mfr !in existingMfr) {
        manufacturerComboFilter.addItem(mfr)
      }
    }
  }

  private fun saveSession() {
    val descriptor = try {
      // 2025.1+ has (String, String) constructor
      FileSaverDescriptor::class.java
        .getConstructor(String::class.java, String::class.java)
        .newInstance("Save Session", "Save as JSON file")
    } catch (_: NoSuchMethodException) {
      // 2024.x has (String, String, String[]) varargs constructor
      FileSaverDescriptor::class.java
        .getConstructor(String::class.java, String::class.java, Array<String>::class.java)
        .newInstance("Save Session", "Save as JSON file", arrayOf("json"))
    }
    val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
    val result = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, "trace_session.json") ?: return
    val file = result.file
    session.exportToFile(file)
    JOptionPane.showMessageDialog(this, "Saved: ${file.absolutePath}")
  }

  // -- Renderers --------------------------------------------------------------

  inner class TypeColorRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
      table: JTable, value: Any?, isSelected: Boolean,
      hasFocus: Boolean, row: Int, column: Int,
    ): Component {
      super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
      val modelRow = table.convertRowIndexToModel(row)
      val event = tableModel.eventAt(modelRow)
      if (!isSelected && event != null) {
        foreground = event.type.color
        font = font.deriveFont(java.awt.Font.BOLD)
      } else {
        foreground = null
      }
      return this
    }
  }

  inner class IndentRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
      table: JTable, value: Any?, isSelected: Boolean,
      hasFocus: Boolean, row: Int, column: Int,
    ): Component {
      super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
      val flatRow = groupedModel.rowAt(row)
      if (flatRow != null) {
        val indent = "    ".repeat(flatRow.depth)
        val arrow = if (flatRow.node.children.isNotEmpty()) {
          if (flatRow.node.expanded) "v " else "> "
        } else "  "
        text = "$indent$arrow${flatRow.node.label}"

        if (!isSelected) {
          foreground = when (flatRow.node) {
            is TraceNode.ThreadGroup -> Color(0x6A1B9A)
            is TraceNode.ComponentGroup -> Color(0x00695C)
            is TraceNode.ManufacturerGroup -> Color(0x4527A0)
            is TraceNode.DeviceGroup -> Color(0x01579B)
            is TraceNode.MethodCall -> null
            is TraceNode.EventLeaf -> (flatRow.node as TraceNode.EventLeaf).event.type.color
          }
          if (flatRow.node is TraceNode.ThreadGroup || flatRow.node is TraceNode.ComponentGroup
            || flatRow.node is TraceNode.ManufacturerGroup || flatRow.node is TraceNode.DeviceGroup) {
            font = font.deriveFont(java.awt.Font.BOLD)
          }
        }
      }
      return this
    }
  }

  // -- Grouped table model ----------------------------------------------------

  inner class GroupedTableModel : AbstractTableModel() {
    private val columns = listOf("Label", "Time", "Type", "File:Line", "Manufacturer", "Device", "Tag", "Detail")
    private var flatRows: List<TraceTreeBuilder.FlatRow> = emptyList()

    fun update(rows: List<TraceTreeBuilder.FlatRow>) {
      flatRows = rows
      fireTableDataChanged()
    }

    fun rowAt(index: Int): TraceTreeBuilder.FlatRow? = flatRows.getOrNull(index)

    override fun getRowCount() = flatRows.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(column: Int) = columns[column]

    private fun eventOf(node: TraceNode): TraceEvent? = when (node) {
      is TraceNode.MethodCall -> node.event
      is TraceNode.EventLeaf -> node.event
      else -> null
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
      val flatRow = flatRows.getOrNull(rowIndex) ?: return ""
      val node = flatRow.node
      val event = eventOf(node)
      return when (columnIndex) {
        0 -> node.label
        1 -> event?.timeFormatted ?: ""
        2 -> event?.type?.label ?: ""
        3 -> event?.sourceRef ?: ""
        4 -> event?.deviceManufacturer ?: ""
        5 -> event?.deviceModel ?: ""
        6 -> event?.tag ?: ""
        7 -> event?.detail ?: ""
        else -> ""
      }
    }
  }
}

// -- Actions ------------------------------------------------------------------

class ClearSessionAction : com.intellij.openapi.actionSystem.AnAction() {
  override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
    // Tool window panel access via event bus
  }
}

class SaveSessionAction : com.intellij.openapi.actionSystem.AnAction() {
  override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {}
}
