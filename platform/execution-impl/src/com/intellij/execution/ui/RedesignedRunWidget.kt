// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.execution.ui

import com.intellij.execution.Executor
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.RunConfigurationsComboBoxAction
import com.intellij.execution.impl.ExecutionManagerImpl
import com.intellij.icons.AllIcons
import com.intellij.ide.IdeBundle
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.actionSystem.impl.IdeaActionButtonLook
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.NlsActions
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.ui.JBColor
import com.intellij.ui.components.panels.Wrapper
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.JBValue
import java.awt.*
import java.awt.event.InputEvent
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.SwingConstants

private const val TOOLBAR_HEIGHT = 30

private fun createRunActionToolbar(isCurrentConfigurationRunning: () -> Boolean): ActionToolbar {
  return ActionManager.getInstance().createActionToolbar(
    ActionPlaces.MAIN_TOOLBAR,
    ActionManager.getInstance().getAction("RunToolbarMainActionGroup") as ActionGroup,
    true
  ).apply {
    targetComponent = null
    setReservePlaceAutoPopupIcon(false)
    layoutPolicy = ActionToolbar.NOWRAP_LAYOUT_POLICY
    if (this is ActionToolbarImpl) {
      isOpaque = false
      setMinimumButtonSize(JBUI.size(36, TOOLBAR_HEIGHT))
      setActionButtonBorder(JBUI.Borders.empty())
      setSeparatorCreator { RunToolbarSeparator(isCurrentConfigurationRunning) }
      setCustomButtonLook(RunWidgetButtonLook(isCurrentConfigurationRunning))
      border = null
    }
  }
}

private data class RunToolbarData(val project: Project,
                                  val chosenRunConfiguration: RunnerAndConfigurationSettings?,
                                  val isRunning: Boolean)

private val runToolbarDataKey = Key.create<RunToolbarData>("run-toolbar-data")

private class RedesignedRunToolbarWrapper : AnAction(), CustomComponentAction {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun actionPerformed(e: AnActionEvent): Unit = error("Should not be invoked")

  override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
    return createRunActionToolbar {
      presentation.getClientProperty(runToolbarDataKey)?.isRunning ?: false
    }.component.let {
      Wrapper(it).apply { border = JBUI.Borders.empty(5, 2) }
    }
  }

  override fun update(e: AnActionEvent) {
    if (!Registry.`is`("ide.experimental.ui.redesigned.run.widget")) {
      e.presentation.isEnabledAndVisible = false
      return
    }
    e.presentation.isEnabled = false
    val project = e.project ?: return
    val selectedConfiguration: RunnerAndConfigurationSettings? = RunManager.getInstanceIfCreated(project)?.selectedConfiguration
    val runningDescriptors = ExecutionManagerImpl.getInstance(project).getRunningDescriptors { it === selectedConfiguration }
    val someRunning = !runningDescriptors.isEmpty()
    e.presentation.putClientProperty(runToolbarDataKey, RunToolbarData(project, selectedConfiguration, someRunning))
  }

  override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
    val data = presentation.getClientProperty(runToolbarDataKey) ?: return
    val dataPropertyName = "old-run-toolbar-data"
    val oldData = component.getClientProperty(dataPropertyName) as? RunToolbarData
    if (oldData == null) {
      component.putClientProperty(dataPropertyName, data)
    }
    else if (data != oldData) {
      component.repaint()
      component.putClientProperty(dataPropertyName, data)
    }
  }
}

class RunToolbarTopLevelExecutorActionGroup : ActionGroup() {
  override fun isPopup() = false

  override fun getChildren(e: AnActionEvent?): Array<AnAction> {
    val topLevelRunActions = listOf(IdeActions.ACTION_DEFAULT_RUNNER, IdeActions.ACTION_DEFAULT_DEBUGGER).mapNotNull {
      ActionManager.getInstance().getAction(it)
    }
    return topLevelRunActions.toTypedArray()
  }
}

private class RunWidgetButtonLook(private val isCurrentConfigurationRunning: () -> Boolean) : IdeaActionButtonLook() {
  override fun getStateBackground(component: JComponent, state: Int): Color {

    val color = getRunWidgetBackgroundColor(isCurrentConfigurationRunning())

    return when (state) {
      ActionButtonComponent.NORMAL -> color
      ActionButtonComponent.PUSHED -> color.addAlpha(0.9)
      else -> color.addAlpha(0.9)
    }
  }

  override fun paintBackground(g: Graphics, component: JComponent, @ActionButtonComponent.ButtonState state: Int) {
    val rect = Rectangle(component.size)
    val color = getStateBackground(component, state)

    val g2 = g.create() as Graphics2D

    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE)
      g2.color = color
      val arc = buttonArc.float
      val width = rect.width
      val height = rect.height

      val shape = when (component) {
        component.parent?.components?.lastOrNull() -> {
          val shape1 = RoundRectangle2D.Float(rect.x.toFloat(), rect.y.toFloat(), width.toFloat(), height.toFloat(), arc, arc)
          val shape2 = Rectangle2D.Float(rect.x.toFloat() - 1, rect.y.toFloat(), arc, height.toFloat())
          Area(shape1).also { it.add(Area(shape2)) }
        }
        component.parent?.components?.get(0) -> {
          val shape1 = RoundRectangle2D.Float(rect.x.toFloat(), rect.y.toFloat(), width.toFloat(), height.toFloat(), arc, arc)
          val shape2 = Rectangle2D.Float((rect.x + width).toFloat() - arc, rect.y.toFloat(), arc, height.toFloat())
          Area(shape1).also { it.add(Area(shape2)) }
        }
        else -> {
          Rectangle2D.Float(rect.x.toFloat() - 1, rect.y.toFloat(), width.toFloat() + 2, height.toFloat())
        }
      }

      g2.fill(shape)
    }
    finally {
      g2.dispose()
    }
  }


  override fun paintIcon(g: Graphics, actionButton: ActionButtonComponent, icon: Icon, x: Int, y: Int) {
    if (icon.iconWidth == 0 || icon.iconHeight == 0) {
      return
    }
    super.paintIcon(g, actionButton, IconUtil.toStrokeIcon(icon, Color.WHITE), x, y)
  }

  override fun paintLookBorder(g: Graphics, rect: Rectangle, color: Color) {}
  override fun getButtonArc(): JBValue = JBValue.Float(8f)
}


private abstract class TogglePopupAction : ToggleAction {

  constructor()

  constructor(@NlsActions.ActionText text: String?,
              @NlsActions.ActionDescription description: String?,
              icon: Icon?) : super(text, description, icon)

  override fun isSelected(e: AnActionEvent): Boolean {
    return Toggleable.isSelected(e.presentation)
  }

  override fun setSelected(e: AnActionEvent, state: Boolean) {
    val presentation = e.presentation
    Toggleable.setSelected(presentation, state)
    if (!state) return
    val component = e.inputEvent?.component as? JComponent ?: return
    val actionGroup = getActionGroup(e) ?: return
    val disposeCallback = { Toggleable.setSelected(presentation, false) }
    val popup = createPopup(actionGroup, e, disposeCallback)
    popup.showUnderneathOf(component)
  }

  open fun createPopup(actionGroup: ActionGroup,
                          e: AnActionEvent,
                          disposeCallback: () -> Unit) = JBPopupFactory.getInstance().createActionGroupPopup(
    null, actionGroup, e.dataContext, false, false, false, disposeCallback, 30, null)

  abstract fun getActionGroup(e: AnActionEvent): ActionGroup?
}

private class MoreRunToolbarActions : TogglePopupAction(
  IdeBundle.message("show.options.menu"), IdeBundle.message("show.options.menu"), AllIcons.Actions.More
), DumbAware {
  override fun getActionGroup(e: AnActionEvent): ActionGroup? {
    val project = e.project ?: return null
    val selectedConfiguration = RunManager.getInstance(project).selectedConfiguration
    return createOtherRunnersSubgroup(selectedConfiguration, project)
  }

  override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

internal val excludeRunAndDebug: (Executor) -> Boolean = {
  // Cannot use DefaultDebugExecutor.EXECUTOR_ID because of module dependencies
  it.id != ToolWindowId.RUN && it.id != ToolWindowId.DEBUG
}

private fun createOtherRunnersSubgroup(runConfiguration: RunnerAndConfigurationSettings?, project: Project): ActionGroup? {
  if (runConfiguration != null) {
    return RunConfigurationsComboBoxAction.SelectConfigAction(runConfiguration, project, excludeRunAndDebug)
  }
  if (RunConfigurationsComboBoxAction.hasRunCurrentFileItem(project)) {
    return RunConfigurationsComboBoxAction.RunCurrentFileAction(excludeRunAndDebug)
  }
  return ActionGroup.EMPTY_GROUP
}

private class RedesignedRunConfigurationSelector : TogglePopupAction(), CustomComponentAction, DumbAware {
  override fun setSelected(e: AnActionEvent, state: Boolean) {
    if (e.inputEvent.modifiersEx and InputEvent.SHIFT_DOWN_MASK != 0) {
      ActionManager.getInstance().getAction("editRunConfigurations").actionPerformed(e)
      return
    }
    super.setSelected(e, state)
  }

  override fun getActionGroup(e: AnActionEvent): ActionGroup? {
    val project = e.project ?: return null
    return createRunConfigurationsActionGroup(project, extendableAllConfigurations = true, addHeader = false)
  }

  override fun createPopup(actionGroup: ActionGroup, e: AnActionEvent, disposeCallback: () -> Unit): ListPopup =
    RunConfigurationsActionGroupPopup(actionGroup, e.dataContext, disposeCallback)

  override fun update(e: AnActionEvent) {
    super.update(e)
    val action = ActionManager.getInstance().getAction("RunConfiguration")
    val runConfigAction = action as? RunConfigurationsComboBoxAction ?: return
    runConfigAction.update(e)
  }

  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun displayTextInToolbar(): Boolean {
    return true
  }

  override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
    return object : ActionButtonWithText(this, presentation, place, JBUI.size(90, TOOLBAR_HEIGHT)){
      override fun getMargins(): Insets = JBInsets.create(0, 10)
      override fun iconTextSpace(): Int = JBUI.scale(6)
    }.also {
      it.foreground = Color.WHITE
      it.setHorizontalTextAlignment(SwingConstants.LEFT)
    }
  }
}


private class RunToolbarSeparator(private val isCurrentConfigurationRunning: () -> Boolean) : JComponent() {
  override fun paint(g: Graphics) {
    super.paint(g)
    val g2 = g.create() as Graphics2D
    g2.color = getRunWidgetBackgroundColor(isCurrentConfigurationRunning())
    g2.fill(Rectangle(size))
    g2.color = Color.WHITE.addAlpha(0.4)
    g2.stroke = BasicStroke(JBUIScale.scale(1f))
    g2.drawLine(0, JBUI.scale(5), 0, JBUI.scale(25))
  }

  override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(1), JBUI.scale(TOOLBAR_HEIGHT))
}

private fun Color.addAlpha(alpha: Double): Color {
  return JBColor.lazy { Color(red, green, blue, (255 * alpha).toInt()) }
}

private fun getRunWidgetBackgroundColor(isRunning: Boolean): JBColor = if (isRunning)
  JBColor.namedColor("Green5", 0x599E5E)
else
  JBColor.namedColor("Blue5", 0x3369D6)
