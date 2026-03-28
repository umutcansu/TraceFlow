package io.github.umutcansu.traceflow.studio.editor

import io.github.umutcansu.traceflow.studio.model.TraceEvent
import io.github.umutcansu.traceflow.studio.model.TraceEventType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.awt.Font

/**
 * Opens the source file for a selected TraceEvent and highlights the relevant line.
 */
object TraceHighlighter {

  private val activeHighlighters = mutableListOf<Pair<MarkupModel, RangeHighlighter>>()

  fun navigateTo(project: Project, event: TraceEvent) {
    ApplicationManager.getApplication().invokeLater {
      clearHighlights()

      val file = findSourceFile(project, event.file) ?: return@invokeLater
      val descriptor = OpenFileDescriptor(project, file, event.line - 1, 0)
      val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        ?: return@invokeLater

      val document = editor.document
      if (event.line < 1 || event.line > document.lineCount) return@invokeLater

      val startOffset = document.getLineStartOffset(event.line - 1)
      val endOffset   = document.getLineEndOffset(event.line - 1)

      val attrs = TextAttributes().apply {
        backgroundColor = event.type.color.let {
          java.awt.Color(it.red, it.green, it.blue, 60)  // Semi-transparent
        }
        fontType = Font.BOLD
      }

      val markupModel = editor.markupModel
      val highlighter = markupModel.addRangeHighlighter(
        startOffset, endOffset,
        com.intellij.openapi.editor.markup.HighlighterLayer.SELECTION - 1,
        attrs,
        HighlighterTargetArea.EXACT_RANGE,
      )
      activeHighlighters.add(markupModel to highlighter)
    }
  }

  fun clearHighlights() {
    ApplicationManager.getApplication().invokeLater {
      activeHighlighters.forEach { (model, highlighter) ->
        runCatching { model.removeHighlighter(highlighter) }
      }
      activeHighlighters.clear()
    }
  }

  private fun findSourceFile(project: Project, fileName: String) =
    FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project))
      .firstOrNull()
}
