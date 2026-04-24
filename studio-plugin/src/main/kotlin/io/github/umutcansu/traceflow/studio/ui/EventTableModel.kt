package io.github.umutcansu.traceflow.studio.ui

import io.github.umutcansu.traceflow.studio.model.TraceEvent
import io.github.umutcansu.traceflow.studio.model.TraceEventType
import javax.swing.table.AbstractTableModel

class EventTableModel : AbstractTableModel() {

  private val columns = listOf("Date", "Time", "Type", "Class", "Method", "File:Line", "Platform", "App", "Manufacturer", "Device", "Tag", "Detail")
  private var rows: List<TraceEvent> = emptyList()

  fun update(events: List<TraceEvent>) {
    rows = events
    fireTableDataChanged()
  }

  fun eventAt(row: Int): TraceEvent? = rows.getOrNull(row)

  override fun getRowCount() = rows.size
  override fun getColumnCount() = columns.size
  override fun getColumnName(column: Int) = columns[column]

  override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
    val event = rows.getOrNull(rowIndex) ?: return ""
    return when (columnIndex) {
      0 -> event.dateFormatted
      1 -> event.timeFormatted
      2 -> event.type.label
      3 -> event.className
      4 -> event.method
      5 -> event.sourceRef
      6 -> event.platformLabel
      7 -> event.appId ?: ""
      8 -> event.deviceManufacturer
      9 -> event.deviceModel
      10 -> event.tag
      11 -> event.detail
      else -> ""
    }
  }
}
