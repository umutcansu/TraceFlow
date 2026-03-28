package io.github.umutcansu.traceflow.studio.ui

import io.github.umutcansu.traceflow.studio.model.TraceEvent
import io.github.umutcansu.traceflow.studio.model.TraceEventType
import javax.swing.table.AbstractTableModel

class EventTableModel : AbstractTableModel() {

  private val columns = listOf("Time", "Type", "Class", "Method", "File:Line", "Detail")
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
      0 -> event.timeFormatted
      1 -> event.type.label
      2 -> event.className
      3 -> event.method
      4 -> event.sourceRef
      5 -> event.detail
      else -> ""
    }
  }
}
