package org.jetbrains.completion.full.line.settings.ui.components

import com.intellij.ui.SimpleListCellRenderer
import org.jetbrains.completion.full.line.language.RedCodePolicy
import org.jetbrains.completion.full.line.settings.MLServerCompletionBundle
import javax.swing.JList

class RedCodePolicyRenderer : SimpleListCellRenderer<RedCodePolicy>() {
  override fun customize(
    list: JList<out RedCodePolicy>,
    value: RedCodePolicy,
    index: Int,
    selected: Boolean,
    hasFocus: Boolean
  ) {
    text = when (value) {
      RedCodePolicy.SHOW -> MLServerCompletionBundle.message("fl.server.completion.ref.check.show")
      RedCodePolicy.DECORATE -> MLServerCompletionBundle.message("fl.server.completion.ref.check.decorate")
      RedCodePolicy.FILTER -> MLServerCompletionBundle.message("fl.server.completion.ref.check.filter")
    }
  }
}
