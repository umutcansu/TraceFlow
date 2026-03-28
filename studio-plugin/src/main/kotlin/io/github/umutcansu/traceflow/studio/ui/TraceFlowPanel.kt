package io.github.umutcansu.traceflow.studio.ui

import io.github.umutcansu.traceflow.studio.editor.TraceHighlighter
import io.github.umutcansu.traceflow.studio.logcat.LogcatMonitor
import io.github.umutcansu.traceflow.studio.model.ExecutionSession
import io.github.umutcansu.traceflow.studio.model.TraceEvent
import io.github.umutcansu.traceflow.studio.model.TraceEventType
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

class TraceFlowWindowFactory : ToolWindowFactory {
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
  private val typeFilters  = TraceEventType.entries.associateWith { JCheckBox(it.label, true) }

  // Grouped view model
  private val groupedModel = GroupedTableModel()
  private val groupedTable = JBTable(groupedModel)

  // Device selector
  private val deviceCombo = JComboBox<String>()

  // State
  private val statusLabel = JLabel("Not connected")
  private var isMonitoring = false
  private var isGroupedMode = false
  private lateinit var startBtn: JButton
  private lateinit var stopBtn: JButton
  private lateinit var toggleBtn: JButton
  private lateinit var scrollPane: JBScrollPane

  init {
    setupTable()
    setupGroupedTable()

    val topPanel = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    setupToolbar().also { topPanel.add(it) }
    setupFilterBar().also { topPanel.add(it) }
    add(topPanel, BorderLayout.NORTH)

    scrollPane = JBScrollPane(table)
    add(scrollPane, BorderLayout.CENTER)

    // Auto-load devices on panel creation
    refreshDevices()
  }

  // -- Flat table setup -------------------------------------------------------

  private fun setupTable() {
    table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
    table.autoCreateRowSorter = true
    table.columnModel.getColumn(0).preferredWidth = 90   // Time
    table.columnModel.getColumn(1).preferredWidth = 60   // Type
    table.columnModel.getColumn(2).preferredWidth = 180  // Class
    table.columnModel.getColumn(3).preferredWidth = 140  // Method
    table.columnModel.getColumn(4).preferredWidth = 140  // File:Line
    table.columnModel.getColumn(5).preferredWidth = 400  // Detail

    // Color the Type column
    table.columnModel.getColumn(1).cellRenderer = TypeColorRenderer()

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
    val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))

    startBtn = JButton("Start").apply {
      isEnabled = false
      addActionListener { startMonitoring() }
    }
    stopBtn = JButton("Stop").apply {
      isEnabled = false
      addActionListener { stopMonitoring() }
    }
    val clearBtn = JButton("Clear").apply {
      addActionListener { clearSession() }
    }
    val saveBtn = JButton("Save").apply {
      addActionListener { saveSession() }
    }
    val loadBtn = JButton("Load").apply {
      addActionListener { loadSession() }
    }
    toggleBtn = JButton("Grouped").apply {
      addActionListener { toggleViewMode() }
    }

    val refreshDevicesBtn = JButton("Refresh").apply {
      addActionListener { refreshDevices() }
    }

    toolbar.add(JLabel("Device:"))
    toolbar.add(deviceCombo)
    toolbar.add(refreshDevicesBtn)
    toolbar.add(JSeparator(SwingConstants.VERTICAL))
    toolbar.add(startBtn)
    toolbar.add(stopBtn)
    toolbar.add(clearBtn)
    toolbar.add(saveBtn)
    toolbar.add(loadBtn)
    toolbar.add(JSeparator(SwingConstants.VERTICAL))
    toolbar.add(toggleBtn)
    toolbar.add(JSeparator(SwingConstants.VERTICAL))
    toolbar.add(statusLabel)

    return toolbar
  }

  // -- Filter bar -------------------------------------------------------------

  private fun setupFilterBar(): JPanel {
    val filterBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))

    filterBar.add(JLabel("Class:"))
    filterBar.add(classFilter)
    filterBar.add(JLabel("Method:"))
    filterBar.add(methodFilter)
    filterBar.add(JSeparator(SwingConstants.VERTICAL))

    typeFilters.values.forEach { cb ->
      filterBar.add(cb)
      cb.addActionListener { refreshTable() }
    }

    // Instant filtering via DocumentListener
    val filterListener = object : DocumentListener {
      override fun insertUpdate(e: DocumentEvent) = refreshTable()
      override fun removeUpdate(e: DocumentEvent) = refreshTable()
      override fun changedUpdate(e: DocumentEvent) = refreshTable()
    }
    classFilter.document.addDocumentListener(filterListener)
    methodFilter.document.addDocumentListener(filterListener)

    return filterBar
  }

  // -- View mode toggle -------------------------------------------------------

  private fun toggleViewMode() {
    isGroupedMode = !isGroupedMode
    toggleBtn.text = if (isGroupedMode) "Flat" else "Grouped"
    scrollPane.setViewportView(if (isGroupedMode) groupedTable else table)
    refreshTable()
  }

  // -- Operations -------------------------------------------------------------

  private fun addEvent(event: TraceEvent) {
    session.add(event)
    ApplicationManager.getApplication().invokeLater { refreshTable() }
  }

  private fun refreshTable() {
    val activeTypes = typeFilters.entries
      .filter { it.value.isSelected }
      .map { it.key }
      .toSet()
    val filtered = session.filtered(
      typeFilter    = activeTypes,
      classFilter   = classFilter.text,
      methodFilter  = methodFilter.text,
    )

    if (isGroupedMode) {
      val roots = TraceTreeBuilder.build(filtered)
      val flatRows = TraceTreeBuilder.flatten(roots)
      groupedModel.update(flatRows)
      if (groupedModel.rowCount > 0) {
        groupedTable.scrollRectToVisible(groupedTable.getCellRect(groupedModel.rowCount - 1, 0, true))
      }
    } else {
      tableModel.update(filtered)
      if (tableModel.rowCount > 0) {
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
    statusLabel.text = "Monitoring ($deviceInfo)..."
  }

  private fun stopMonitoring() {
    isMonitoring = false
    startBtn.isEnabled = true
    stopBtn.isEnabled = false
    statusLabel.text = "Stopped"
    monitor.stop()
  }

  private fun clearSession() {
    session.clear()
    TraceHighlighter.clearHighlights()
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
    refreshTable()
    statusLabel.text = "$count events loaded"
  }

  private fun saveSession() {
    val descriptor = FileSaverDescriptor("Save Session", "Save as JSON file", "json")
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
            is TraceNode.MethodCall -> null
            is TraceNode.EventLeaf -> (flatRow.node as TraceNode.EventLeaf).event.type.color
          }
          if (flatRow.node is TraceNode.ThreadGroup || flatRow.node is TraceNode.ComponentGroup) {
            font = font.deriveFont(java.awt.Font.BOLD)
          }
        }
      }
      return this
    }
  }

  // -- Grouped table model ----------------------------------------------------

  inner class GroupedTableModel : AbstractTableModel() {
    private val columns = listOf("Label", "Time", "Type", "File:Line")
    private var flatRows: List<TraceTreeBuilder.FlatRow> = emptyList()

    fun update(rows: List<TraceTreeBuilder.FlatRow>) {
      flatRows = rows
      fireTableDataChanged()
    }

    fun rowAt(index: Int): TraceTreeBuilder.FlatRow? = flatRows.getOrNull(index)

    override fun getRowCount() = flatRows.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(column: Int) = columns[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
      val flatRow = flatRows.getOrNull(rowIndex) ?: return ""
      val node = flatRow.node
      return when (columnIndex) {
        0 -> node.label
        1 -> {
          val event = when (node) {
            is TraceNode.MethodCall -> node.event
            is TraceNode.EventLeaf -> node.event
            else -> null
          }
          event?.timeFormatted ?: ""
        }
        2 -> {
          when (node) {
            is TraceNode.MethodCall -> node.event.type.label
            is TraceNode.EventLeaf -> node.event.type.label
            else -> ""
          }
        }
        3 -> {
          when (node) {
            is TraceNode.MethodCall -> node.event.sourceRef
            is TraceNode.EventLeaf -> node.event.sourceRef
            else -> ""
          }
        }
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
