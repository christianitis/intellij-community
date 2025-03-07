// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.execution;

import com.intellij.execution.actions.*;
import com.intellij.execution.compound.CompoundRunConfiguration;
import com.intellij.execution.compound.SettingsAndEffectiveTarget;
import com.intellij.execution.configurations.LocatableConfiguration;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.executors.ExecutorGroup;
import com.intellij.execution.impl.ExecutionManagerImpl;
import com.intellij.execution.impl.ExecutionManagerImplKt;
import com.intellij.execution.impl.RunDialog;
import com.intellij.execution.impl.RunnerAndConfigurationSettingsImpl;
import com.intellij.execution.runToolbar.*;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.icons.AllIcons;
import com.intellij.ide.macro.MacroManager;
import com.intellij.ide.ui.ToolbarSettings;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.ActionConfigurationCustomizer;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.extensions.ExtensionPointListener;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.IPopupChooserBuilder;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.ui.ExperimentalUI;
import com.intellij.util.ArrayUtil;
import com.intellij.util.IconUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.text.UniqueNameGenerator;
import org.jetbrains.annotations.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

public final class ExecutorRegistryImpl extends ExecutorRegistry {
  private static final Logger LOG = Logger.getInstance(ExecutorRegistryImpl.class);

  public static final String RUNNERS_GROUP = "RunnerActions";
  public static final String RUN_CONTEXT_GROUP = "RunContextGroupInner";
  public static final String RUN_CONTEXT_GROUP_MORE = "RunContextGroupMore";

  private final Set<String> myContextActionIdSet = new HashSet<>();
  private final Map<String, AnAction> myIdToAction = new HashMap<>();
  private final Map<String, AnAction> myContextActionIdToAction = new HashMap<>();

  private final Map<String, AnAction> myRunWidgetIdToAction = new HashMap<>();

  public ExecutorRegistryImpl() {
    Executor.EXECUTOR_EXTENSION_NAME.addExtensionPointListener(new ExtensionPointListener<>() {
      @Override
      public void extensionAdded(@NotNull Executor extension, @NotNull PluginDescriptor pluginDescriptor) {
        //noinspection TestOnlyProblems
        initExecutorActions(extension, ActionManager.getInstance());
      }

      @Override
      public void extensionRemoved(@NotNull Executor extension, @NotNull PluginDescriptor pluginDescriptor) {
        deinitExecutor(extension);
      }
    }, null);
  }

  static final class ExecutorRegistryActionConfigurationTuner implements ActionConfigurationCustomizer {
    @Override
    public void customize(@NotNull ActionManager manager) {
      if (Executor.EXECUTOR_EXTENSION_NAME.hasAnyExtensions()) {
        ((ExecutorRegistryImpl)getInstance()).init(manager);
      }
    }
  }

  @TestOnly
  public synchronized void initExecutorActions(@NotNull Executor executor, @NotNull ActionManager actionManager) {
    if (myContextActionIdSet.contains(executor.getContextActionId())) {
      LOG.error("Executor with context action id: \"" + executor.getContextActionId() + "\" was already registered!");
    }

    AnAction toolbarAction;
    AnAction runContextAction;
    AnAction runNonExistingContextAction;
    if (executor instanceof ExecutorGroup) {
      ExecutorGroup<?> executorGroup = (ExecutorGroup<?>)executor;
      ActionGroup toolbarActionGroup = new SplitButtonAction(new ExecutorGroupActionGroup(executorGroup, ExecutorAction::new));
      Presentation presentation = toolbarActionGroup.getTemplatePresentation();
      presentation.setIcon(executor.getIcon());
      presentation.setText(executor.getStartActionText());
      presentation.setDescription(executor.getDescription());
      toolbarAction = toolbarActionGroup;
      runContextAction = new ExecutorGroupActionGroup(executorGroup, RunContextAction::new);
      runNonExistingContextAction = new ExecutorGroupActionGroup(executorGroup, RunNewConfigurationContextAction::new);
    }
    else {
      toolbarAction = new ExecutorAction(executor);
      runContextAction = new RunContextAction(executor);
      runNonExistingContextAction = new RunNewConfigurationContextAction(executor);
    }

    Executor.ActionWrapper customizer = executor.runnerActionsGroupExecutorActionCustomizer();
    registerActionInGroup(actionManager, executor.getId(), customizer == null ? toolbarAction : customizer.wrap(toolbarAction), RUNNERS_GROUP, myIdToAction);

    AnAction action = registerAction(actionManager, executor.getContextActionId(), runContextAction, myContextActionIdToAction);
    if (isExecutorInMainGroup(executor)) {
      Objects.requireNonNull((DefaultActionGroup)actionManager.getActionOrStub(RUN_CONTEXT_GROUP))
        .add(action, new Constraints(Anchor.BEFORE, RUN_CONTEXT_GROUP_MORE), actionManager);
    }
    else {
      Objects.requireNonNull((DefaultActionGroup)actionManager.getActionOrStub(RUN_CONTEXT_GROUP_MORE))
        .add(action, new Constraints(Anchor.BEFORE, "CreateRunConfiguration"), actionManager);
    }

    AnAction nonExistingAction = registerAction(actionManager, newConfigurationContextActionId(executor), runNonExistingContextAction, myContextActionIdToAction);
    Objects.requireNonNull((DefaultActionGroup)actionManager.getActionOrStub(RUN_CONTEXT_GROUP_MORE))
      .add(nonExistingAction, new Constraints(Anchor.BEFORE, "CreateNewRunConfiguration"), actionManager);

    initRunToolbarExecutorActions(executor, actionManager);

    myContextActionIdSet.add(executor.getContextActionId());
  }

  private synchronized void initRunToolbarExecutorActions(@NotNull Executor executor, @NotNull ActionManager actionManager) {
    if (ToolbarSettings.getInstance().isAvailable()) {
      RunToolbarProcess.getProcessesByExecutorId(executor.getId()).forEach(process -> {
        if (executor instanceof ExecutorGroup) {

          ExecutorGroup<?> executorGroup = (ExecutorGroup<?>)executor;
          if (process.getShowInBar()) {
            ActionGroup wrappedAction = new RunToolbarExecutorGroupAction(
              new RunToolbarExecutorGroup(executorGroup, (ex) -> new RunToolbarGroupProcessAction(process, ex), process));
            Presentation presentation = wrappedAction.getTemplatePresentation();
            presentation.setIcon(executor.getIcon());
            presentation.setText(process.getName());
            presentation.setDescription(executor.getDescription());

            registerActionInGroup(actionManager, process.getActionId(), wrappedAction, RunToolbarProcess.RUN_WIDGET_GROUP,
                                  myRunWidgetIdToAction);
          }
          else {
            RunToolbarAdditionActionsHolder holder = new RunToolbarAdditionActionsHolder(executorGroup, process);

            registerActionInGroup(actionManager, RunToolbarAdditionActionsHolder.getAdditionActionId(process), holder.getAdditionAction(),
                                  process.getMoreActionSubGroupName(),
                                  myRunWidgetIdToAction);
            registerActionInGroup(actionManager, RunToolbarAdditionActionsHolder.getAdditionActionChooserGroupId(process),
                                  holder.getMoreActionChooserGroup(), process.getMoreActionSubGroupName(),
                                  myRunWidgetIdToAction);
          }
        }
        else {
          if (!process.isTemporaryProcess() && process.getShowInBar()) {
            ExecutorAction wrappedAction = new RunToolbarProcessAction(process, executor);
            ExecutorAction wrappedMainAction = new RunToolbarProcessMainAction(process, executor);

            registerActionInGroup(actionManager, process.getActionId(), wrappedAction, RunToolbarProcess.RUN_WIDGET_GROUP,
                                  myRunWidgetIdToAction);

            registerActionInGroup(actionManager, process.getMainActionId(), wrappedMainAction, RunToolbarProcess.RUN_WIDGET_MAIN_GROUP,
                                  myRunWidgetIdToAction);
          }
        }
      });
    }
  }

  private static @NonNls String newConfigurationContextActionId(@NotNull Executor executor) {
    return "newConfiguration" + executor.getContextActionId();
  }

  private static boolean isExecutorInMainGroup(@NotNull Executor executor) {
    String id = executor.getId();
    return id.equals(ToolWindowId.RUN) || id.equals(ToolWindowId.DEBUG) || !Registry.is("executor.actions.submenu", true);
  }

  private static void registerActionInGroup(@NotNull ActionManager actionManager, @NotNull String actionId, @NotNull AnAction anAction, @NotNull String groupId, @NotNull Map<String, AnAction> map) {
    AnAction action = registerAction(actionManager, actionId, anAction, map);
    ((DefaultActionGroup)actionManager.getAction(groupId)).add(action, actionManager);
  }

  private static @NotNull AnAction registerAction(@NotNull ActionManager actionManager,
                                                  @NotNull String actionId,
                                                  @NotNull AnAction anAction,
                                                  @NotNull Map<String, AnAction> map) {
    AnAction action = actionManager.getAction(actionId);
    if (action == null) {
      actionManager.registerAction(actionId, anAction);
      map.put(actionId, anAction);
      action = anAction;
    }
    return action;
  }

  synchronized void deinitExecutor(@NotNull Executor executor) {
    myContextActionIdSet.remove(executor.getContextActionId());

    unregisterAction(executor.getId(), RUNNERS_GROUP, myIdToAction);
    if (isExecutorInMainGroup(executor)) {
      unregisterAction(executor.getContextActionId(), RUN_CONTEXT_GROUP, myContextActionIdToAction);
    }
    else {
      unregisterAction(executor.getContextActionId(), RUN_CONTEXT_GROUP_MORE, myContextActionIdToAction);
    }
    unregisterAction(newConfigurationContextActionId(executor), RUN_CONTEXT_GROUP_MORE, myContextActionIdToAction);

    RunToolbarProcess.getProcessesByExecutorId(executor.getId()).forEach(process -> {
      unregisterAction(process.getActionId(), RunToolbarProcess.RUN_WIDGET_GROUP, myRunWidgetIdToAction);
      unregisterAction(process.getMainActionId(), RunToolbarProcess.RUN_WIDGET_MAIN_GROUP, myRunWidgetIdToAction);

      if (executor instanceof ExecutorGroup) {
        unregisterAction(RunToolbarAdditionActionsHolder.getAdditionActionId(process), process.getMoreActionSubGroupName(),
                         myRunWidgetIdToAction);
        unregisterAction(RunToolbarAdditionActionsHolder.getAdditionActionChooserGroupId(process), process.getMoreActionSubGroupName(),
                         myRunWidgetIdToAction);
      }
    });
  }

  private static void unregisterAction(@NotNull String actionId, @NotNull String groupId, @NotNull Map<String, AnAction> map) {
    ActionManager actionManager = ActionManager.getInstance();
    DefaultActionGroup group = (DefaultActionGroup)actionManager.getAction(groupId);
    if (group == null) {
      return;
    }

    AnAction action = map.get(actionId);
    if (action != null) {
      group.remove(action, actionManager);
      actionManager.unregisterAction(actionId);
      map.remove(actionId);
    }
    else {
      action = ActionManager.getInstance().getAction(actionId);
      if (action != null) {
        group.remove(action, actionManager);
      }
    }
  }

  @Override
  public Executor getExecutorById(@NotNull String executorId) {
    // even IJ Ultimate with all plugins has ~7 executors - linear search is ok here
    for (Executor executor : Executor.EXECUTOR_EXTENSION_NAME.getExtensionList()) {
      if (executorId.equals(executor.getId())) {
        return executor;
      }
    }
    return null;
  }

  private void init(@NotNull ActionManager actionManager) {
    for (Executor executor : Executor.EXECUTOR_EXTENSION_NAME.getExtensionList()) {
      try {
        //noinspection TestOnlyProblems
        initExecutorActions(executor, actionManager);
      }
      catch (Throwable t) {
        LOG.error("executor initialization failed: " + executor.getClass().getName(), t);
      }
    }
  }

  public static class ExecutorAction extends AnAction implements DumbAware {
    private static final Key<RunCurrentFileInfo> CURRENT_FILE_RUN_CONFIGS_KEY = Key.create("CURRENT_FILE_RUN_CONFIGS");

    protected final Executor myExecutor;

    protected ExecutorAction(@NotNull Executor executor) {
      super(executor.getStartActionText(), executor.getDescription(), IconLoader.createLazy(() -> executor.getIcon()));
      myExecutor = executor;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.BGT;
    }

    private boolean canRun(@NotNull Project project, @NotNull List<SettingsAndEffectiveTarget> pairs) {
      return RunnerHelper.canRun(project, pairs, myExecutor);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      Presentation presentation = e.getPresentation();
      Project project = e.getProject();
      if (project == null || !project.isInitialized() || project.isDisposed()) {
        presentation.setEnabled(false);
        return;
      }

      RunnerAndConfigurationSettings selectedSettings = getSelectedConfiguration(e);
      boolean enabled = false;
      boolean runConfigAsksToHideDisabledExecutorButtons = false;
      String text;
      if (selectedSettings != null) {
        if (DumbService.isDumb(project) && !selectedSettings.getType().isDumbAware()) {
          presentation.setEnabled(false);
          return;
        }

        presentation.setIcon(getInformativeIcon(project, selectedSettings));
        RunConfiguration configuration = selectedSettings.getConfiguration();
        if (!isSuppressed(project)) {
          if (configuration instanceof CompoundRunConfiguration) {
            enabled = canRun(project, ((CompoundRunConfiguration)configuration).getConfigurationsWithEffectiveRunTargets());
          }
          else {
            ExecutionTarget target = ExecutionTargetManager.getActiveTarget(project);
            enabled = canRun(project, Collections.singletonList(new SettingsAndEffectiveTarget(configuration, target)));
          }
        }
        if (!(configuration instanceof CompoundRunConfiguration)) {
          runConfigAsksToHideDisabledExecutorButtons = configuration.hideDisabledExecutorButtons();
        }
        if (enabled) {
          presentation.setDescription(myExecutor.getDescription());
        }
        text = myExecutor.getStartActionText(configuration.getName());
      }
      else {
        if (RunConfigurationsComboBoxAction.hasRunCurrentFileItem(project)) {
          RunCurrentFileActionStatus status = getRunCurrentFileActionStatus(e, false);
          enabled = status.myEnabled;
          text = status.myTooltip;
          presentation.setIcon(status.myIcon);
        }
        else {
          text = getTemplatePresentation().getTextWithMnemonic();
        }
      }

      if (runConfigAsksToHideDisabledExecutorButtons || hideDisabledExecutorButtons()) {
        presentation.setEnabledAndVisible(enabled);
      }
      else {
        presentation.setEnabled(enabled);
      }

      if (presentation.isVisible()) {
        presentation.setVisible(myExecutor.isApplicable(project));
      }
      presentation.setText(text);
    }

    protected boolean hideDisabledExecutorButtons() {
      return false;
    }

    private @NotNull RunCurrentFileActionStatus getRunCurrentFileActionStatus(@NotNull AnActionEvent e, boolean resetCache) {
      Project project = Objects.requireNonNull(e.getProject());
      if (DumbService.isDumb(project)) {
        return RunCurrentFileActionStatus.createDisabled(myExecutor.getStartActionText(), myExecutor.getIcon());
      }

      VirtualFile[] files = FileEditorManager.getInstance(project).getSelectedFiles();
      if (files.length == 1) {
        // There's only one visible editor, let's use the file from this editor, even if the editor is not in focus.
        PsiFile psiFile = PsiManager.getInstance(project).findFile(files[0]);
        if (psiFile == null) {
          String tooltip = ExecutionBundle.message("run.button.on.toolbar.tooltip.current.file.not.runnable");
          return RunCurrentFileActionStatus.createDisabled(tooltip, myExecutor.getIcon());
        }

        return getRunCurrentFileActionStatus(psiFile, resetCache);
      }

      Editor editor = e.getData(CommonDataKeys.EDITOR);
      if (editor == null) {
        String tooltip = ExecutionBundle.message("run.button.on.toolbar.tooltip.current.file.no.focused.editor");
        return RunCurrentFileActionStatus.createDisabled(tooltip, myExecutor.getIcon());
      }

      PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
      VirtualFile vFile = psiFile != null ? psiFile.getVirtualFile() : null;
      if (psiFile == null || vFile == null || !ArrayUtil.contains(vFile, files)) {
        // This is probably a special editor, like Python Console, which we don't want to use for the 'Run Current File' feature.
        String tooltip = ExecutionBundle.message("run.button.on.toolbar.tooltip.current.file.no.focused.editor");
        return RunCurrentFileActionStatus.createDisabled(tooltip, myExecutor.getIcon());
      }

      return getRunCurrentFileActionStatus(psiFile, resetCache);
    }

    private @NotNull RunCurrentFileActionStatus getRunCurrentFileActionStatus(@NotNull PsiFile psiFile, boolean resetCache) {
      List<RunnerAndConfigurationSettings> runConfigs = getRunConfigsForCurrentFile(psiFile, resetCache);
      if (runConfigs.isEmpty()) {
        String tooltip = ExecutionBundle.message("run.button.on.toolbar.tooltip.current.file.not.runnable");
        return RunCurrentFileActionStatus.createDisabled(tooltip, myExecutor.getIcon());
      }

      List<RunnerAndConfigurationSettings> runnableConfigs = filterConfigsThatHaveRunner(runConfigs);
      if (runnableConfigs.isEmpty()) {
        return RunCurrentFileActionStatus.createDisabled(myExecutor.getStartActionText(psiFile.getName()), myExecutor.getIcon());
      }

      Icon icon = myExecutor.getIcon();
      if (runnableConfigs.size() == 1) {
        icon = getInformativeIcon(psiFile.getProject(), runnableConfigs.get(0));
      }
      else {
        // myExecutor.getIcon() is the least preferred icon
        // AllIcons.Actions.Restart is more preferred
        // Other icons are the most preferred ones (like ExecutionUtil.getLiveIndicator())
        for (RunnerAndConfigurationSettings config : runnableConfigs) {
          Icon anotherIcon = getInformativeIcon(psiFile.getProject(), config);
          if (icon == myExecutor.getIcon() || (anotherIcon != myExecutor.getIcon() && anotherIcon != AllIcons.Actions.Restart)) {
            icon = anotherIcon;
          }
        }
      }

      return RunCurrentFileActionStatus.createEnabled(myExecutor.getStartActionText(psiFile.getName()), icon, runnableConfigs);
    }

    private static List<RunnerAndConfigurationSettings> getRunConfigsForCurrentFile(@NotNull PsiFile psiFile, boolean resetCache) {
      if (resetCache) {
        psiFile.putUserData(CURRENT_FILE_RUN_CONFIGS_KEY, null);
      }

      // Without this cache, an expensive method `ConfigurationContext.getConfigurationsFromContext()` is called too often for 2 reasons:
      // - there are several buttons on the toolbar (Run, Debug, Profile, etc.), each runs ExecutorAction.update() during each action update session
      // - the state of the buttons on the toolbar is updated several times a second, even if no files are being edited

      // The following few lines do pretty much the same as CachedValuesManager.getCachedValue(), but it's implemented without calling that
      // method because it appeared to be too hard to satisfy both IdempotenceChecker.checkEquivalence() and CachedValueStabilityChecker.checkProvidersEquivalent().
      // The reason is that RunnerAndConfigurationSettings class doesn't implement equals(), and that CachedValueProvider would need to capture
      // ConfigurationContext, which doesn't implement equals() either.
      // Effectively, we need only one boolean value: whether the action is enabled or not, so it shouldn't be a problem that
      // RunnerAndConfigurationSettings and ConfigurationContext don't implement equals() and this code doesn't pass CachedValuesManager checks.

      long psiModCount = PsiModificationTracker.getInstance(psiFile.getProject()).getModificationCount();
      RunCurrentFileInfo cache = psiFile.getUserData(CURRENT_FILE_RUN_CONFIGS_KEY);

      if (cache == null || cache.myPsiModCount != psiModCount) {
        // The 'Run current file' feature doesn't depend on the caret position in the file, that's why ConfigurationContext is created like this.
        ConfigurationContext configurationContext = new ConfigurationContext(psiFile);

        // The 'Run current file' feature doesn't reuse existing run configurations (by design).
        List<ConfigurationFromContext> configurationsFromContext = configurationContext.createConfigurationsFromContext();

        List<RunnerAndConfigurationSettings> runConfigs =
          configurationsFromContext != null
          ? ContainerUtil.map(configurationsFromContext, ConfigurationFromContext::getConfigurationSettings)
          : Collections.emptyList();

        VirtualFile vFile = psiFile.getVirtualFile();
        String filePath = vFile != null ? vFile.getPath() : null;
        for (RunnerAndConfigurationSettings config : runConfigs) {
          ((RunnerAndConfigurationSettingsImpl)config).setFilePathIfRunningCurrentFile(filePath);
        }

        cache = new RunCurrentFileInfo(psiModCount, runConfigs);
        psiFile.putUserData(CURRENT_FILE_RUN_CONFIGS_KEY, cache);
      }

      return cache.myRunConfigs;
    }

    private @NotNull List<RunnerAndConfigurationSettings> filterConfigsThatHaveRunner(@NotNull List<RunnerAndConfigurationSettings> runConfigs) {
      return ContainerUtil.filter(runConfigs, config -> ProgramRunner.getRunner(myExecutor.getId(), config.getConfiguration()) != null);
    }

    private static boolean isSuppressed(Project project) {
      for (ExecutionActionSuppressor suppressor : ExecutionActionSuppressor.EP_NAME.getExtensionList()) {
        if (suppressor.isSuppressed(project)) return true;
      }
      return false;
    }

    protected Icon getInformativeIcon(@NotNull Project project, @NotNull RunnerAndConfigurationSettings selectedConfiguration) {
      ExecutionManagerImpl executionManager = ExecutionManagerImpl.getInstance(project);
      RunConfiguration configuration = selectedConfiguration.getConfiguration();
      if (configuration instanceof RunnerIconProvider) {
        RunnerIconProvider provider = (RunnerIconProvider)configuration;
        Icon icon = provider.getExecutorIcon(configuration, myExecutor);
        if (icon != null) {
          return icon;
        }
      }

      List<RunContentDescriptor> runningDescriptors =
        executionManager.getRunningDescriptors(s -> ExecutionManagerImplKt.isOfSameType(s, selectedConfiguration));
      runningDescriptors = ContainerUtil.filter(runningDescriptors, descriptor -> executionManager.getExecutors(descriptor).contains(myExecutor));

      if (!configuration.isAllowRunningInParallel() && !runningDescriptors.isEmpty()) {
        if (ExperimentalUI.isNewUI() && myExecutor.getIcon() != myExecutor.getRerunIcon()) {
          return myExecutor.getRerunIcon();
        }
        if (DefaultRunExecutor.EXECUTOR_ID.equals(myExecutor.getId())) {
          return AllIcons.Actions.Restart;
        }
      }
      if (runningDescriptors.isEmpty()) {
        return myExecutor.getIcon();
      }

      if (runningDescriptors.size() == 1) {
        return ExecutionUtil.getLiveIndicator(myExecutor.getIcon());
      }
      else {
        return IconUtil.addText(myExecutor.getIcon(), Integer.toString(runningDescriptors.size()));
      }
    }

    protected @Nullable RunnerAndConfigurationSettings getSelectedConfiguration(@NotNull AnActionEvent e) {
      Project project = e.getProject();
      RunManager runManager = project == null ? null : RunManager.getInstanceIfCreated(project);
      return runManager == null ? null : runManager.getSelectedConfiguration();
    }

    protected void run(@NotNull Project project, @NotNull RunnerAndConfigurationSettings settings, @NotNull DataContext dataContext) {
      RunnerHelper.run(project, settings.getConfiguration(), settings, dataContext, myExecutor);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      final Project project = e.getProject();
      if (project == null || project.isDisposed()) {
        return;
      }

      MacroManager.getInstance().cacheMacrosPreview(e.getDataContext());
      RunnerAndConfigurationSettings selectedConfiguration = getSelectedConfiguration(e);
      if (selectedConfiguration != null) {
        run(project, selectedConfiguration, e.getDataContext());
      }
      else if (RunConfigurationsComboBoxAction.hasRunCurrentFileItem(project)) {
        runCurrentFile(e);
      }
    }

    private void runCurrentFile(@NotNull AnActionEvent e) {
      Project project = Objects.requireNonNull(e.getProject());
      List<RunnerAndConfigurationSettings> runConfigs = getRunCurrentFileActionStatus(e, true).myRunConfigs;
      if (runConfigs.isEmpty()) {
        return;
      }

      if (runConfigs.size() == 1) {
        doRunCurrentFile(project, runConfigs.get(0), e.getDataContext());
        return;
      }

      IPopupChooserBuilder<RunnerAndConfigurationSettings> builder = JBPopupFactory.getInstance()
        .createPopupChooserBuilder(runConfigs)
        .setRenderer(new DefaultListCellRenderer() {
          @Override
          public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            RunnerAndConfigurationSettings runConfig = (RunnerAndConfigurationSettings)value;
            JLabel result = (JLabel)super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            result.setIcon(runConfig.getConfiguration().getIcon());
            result.setText(runConfig.getName());
            return result;
          }
        })
        .setItemChosenCallback(runConfig -> doRunCurrentFile(project, runConfig, e.getDataContext()));

      InputEvent inputEvent = e.getInputEvent();
      if (inputEvent instanceof MouseEvent) {
        builder.createPopup().showUnderneathOf(inputEvent.getComponent());
      }
      else {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
          // Not expected to happen because we are running a file from the current editor.
          LOG.warn("Run Current File (" + runConfigs + "): getSelectedTextEditor() == null");
          return;
        }

        builder
          .setTitle(myExecutor.getActionName())
          .createPopup()
          .showInBestPositionFor(editor);
      }
    }

    protected void doRunCurrentFile(@NotNull Project project,
                                    @NotNull RunnerAndConfigurationSettings runConfig,
                                    @NotNull DataContext dataContext) {
      ExecutionUtil.doRunConfiguration(runConfig, myExecutor, null, null, dataContext);
    }
  }

  public static class RunSpecifiedConfigExecutorAction extends ExecutorAction {
    private final RunnerAndConfigurationSettings myRunConfig;
    private final boolean myEditConfigBeforeRun;

    public RunSpecifiedConfigExecutorAction(@NotNull Executor executor,
                                            @NotNull RunnerAndConfigurationSettings runConfig,
                                            boolean editConfigBeforeRun) {
      super(executor);
      myRunConfig = runConfig;
      myEditConfigBeforeRun = editConfigBeforeRun;
    }

    @Override
    protected @NotNull RunnerAndConfigurationSettings getSelectedConfiguration(@NotNull AnActionEvent e) {
      return myRunConfig;
    }

    @Override
    protected boolean hideDisabledExecutorButtons() {
      // no need in a list of disabled actions in the secondary menu of the Run Configuration item in the combo box drop-down menu.
      return true;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      super.update(e);

      if (myEditConfigBeforeRun) {
        Presentation presentation = e.getPresentation();
        presentation.setText(ExecutionBundle.message("choose.run.popup.edit"));
        presentation.setDescription(ExecutionBundle.message("choose.run.popup.edit.description"));
        presentation.setIcon(AllIcons.Actions.EditSource);
      }
    }

    @Override
    protected void run(@NotNull Project project, @NotNull RunnerAndConfigurationSettings settings, @NotNull DataContext dataContext) {
      LOG.assertTrue(myRunConfig == settings);

      if (myEditConfigBeforeRun) {
        String dialogTitle = ExecutionBundle.message("dialog.title.edit.configuration.settings");
        if (!RunDialog.editConfiguration(project, myRunConfig, dialogTitle, myExecutor)) {
          return;
        }
      }

      super.run(project, myRunConfig, dataContext);

      RunManager.getInstance(project).setSelectedConfiguration(myRunConfig);
    }
  }

  public static class RunCurrentFileExecutorAction extends ExecutorAction {
    public RunCurrentFileExecutorAction(@NotNull Executor executor) {
      super(executor);
    }

    @Override
    protected @Nullable RunnerAndConfigurationSettings getSelectedConfiguration(@NotNull AnActionEvent e) {
      return null; // null means 'run current file, not the selected run configuration'
    }

    @Override
    protected boolean hideDisabledExecutorButtons() {
      // no need in a list of disabled actions in the secondary menu of the 'Current File' item in the combo box drop-down menu.
      return true;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      Presentation presentation = e.getPresentation();
      if (e.getProject() == null || !RunConfigurationsComboBoxAction.hasRunCurrentFileItem(e.getProject())) {
        presentation.setEnabledAndVisible(false);
        return;
      }

      super.update(e);
    }
  }

  public static class EditRunConfigAndRunCurrentFileExecutorAction extends RunCurrentFileExecutorAction {
    public EditRunConfigAndRunCurrentFileExecutorAction(@NotNull Executor executor) {
      super(executor);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      super.update(e);

      Presentation presentation = e.getPresentation();
      presentation.setText(ExecutionBundle.message("run.configurations.popup.edit.run.config.and.run.current.file"));
      presentation.setDescription(ExecutionBundle.message("run.configurations.popup.edit.run.config.and.run.current.file.description"));
      presentation.setIcon(AllIcons.Actions.EditSource);
    }

    @Override
    protected void doRunCurrentFile(@NotNull Project project,
                                    @NotNull RunnerAndConfigurationSettings runConfig,
                                    @NotNull DataContext dataContext) {
      String suggestedName = StringUtil.notNullize(((LocatableConfiguration)runConfig.getConfiguration()).suggestedName(),
                                                   runConfig.getName());
      List<String> usedNames = ContainerUtil.map(RunManager.getInstance(project).getAllSettings(), RunnerAndConfigurationSettings::getName);
      String uniqueName = UniqueNameGenerator.generateUniqueName(suggestedName, "", "", " (", ")", s -> !usedNames.contains(s));
      runConfig.setName(uniqueName);

      String dialogTitle = ExecutionBundle.message("dialog.title.edit.configuration.settings");
      if (RunDialog.editConfiguration(project, runConfig, dialogTitle, myExecutor)) {
        RunManager.getInstance(project).setTemporaryConfiguration(runConfig);
        super.doRunCurrentFile(project, runConfig, dataContext);
      }
    }
  }

  private static class RunCurrentFileInfo {
    private final long myPsiModCount;
    private final @NotNull List<RunnerAndConfigurationSettings> myRunConfigs;

    private RunCurrentFileInfo(long psiModCount, @NotNull List<RunnerAndConfigurationSettings> runConfigs) {
      myPsiModCount = psiModCount;
      myRunConfigs = runConfigs;
    }
  }

  @ApiStatus.Internal
  public static class ExecutorGroupActionGroup extends ActionGroup implements DumbAware {
    protected final ExecutorGroup<?> myExecutorGroup;
    private final Function<? super Executor, ? extends AnAction> myChildConverter;

    protected ExecutorGroupActionGroup(@NotNull ExecutorGroup<?> executorGroup,
                                       @NotNull Function<? super Executor, ? extends AnAction> childConverter) {
      myExecutorGroup = executorGroup;
      myChildConverter = childConverter;
    }

    @Override
    public AnAction @NotNull [] getChildren(@Nullable AnActionEvent e) {
      // RunExecutorSettings configurations can be modified, so we request current childExecutors on each call
      List<Executor> childExecutors = myExecutorGroup.childExecutors();
      AnAction[] result = new AnAction[childExecutors.size()];
      for (int i = 0; i < childExecutors.size(); i++) {
        result[i] = myChildConverter.apply(childExecutors.get(i));
      }
      return result;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      final Project project = e.getProject();
      if (project == null || !project.isInitialized() || project.isDisposed()) {
        e.getPresentation().setEnabled(false);
        return;
      }
      e.getPresentation().setEnabledAndVisible(myExecutorGroup.isApplicable(project));
    }
  }

  public static final class RunnerHelper {
    public static void run(@NotNull Project project,
                           @Nullable RunConfiguration configuration,
                           @Nullable RunnerAndConfigurationSettings settings,
                           @NotNull DataContext dataContext,
                           @NotNull Executor executor) {

      runSubProcess(project, configuration, settings, dataContext, executor, RunToolbarProcessData.prepareBaseSettingCustomization(settings, null));
    }

    public static void runSubProcess(@NotNull Project project,
                                     @Nullable RunConfiguration configuration,
                                     @Nullable RunnerAndConfigurationSettings settings,
                                     @NotNull DataContext dataContext,
                                     @NotNull Executor executor,
                                     @Nullable Consumer<? super ExecutionEnvironment> environmentCustomization) {

      if (configuration instanceof CompoundRunConfiguration) {
        RunManager runManager = RunManager.getInstance(project);
        for (SettingsAndEffectiveTarget settingsAndEffectiveTarget : ((CompoundRunConfiguration)configuration)
          .getConfigurationsWithEffectiveRunTargets()) {
          RunConfiguration subConfiguration = settingsAndEffectiveTarget.getConfiguration();
          runSubProcess(project, subConfiguration, runManager.findSettings(subConfiguration), dataContext, executor, environmentCustomization);
        }
      }
      else {
        ExecutionEnvironmentBuilder builder = settings == null ? null : ExecutionEnvironmentBuilder.createOrNull(executor, settings);
        if (builder == null) {
          return;
        }

        RunToolbarData rtData = dataContext.getData(RunToolbarData.RUN_TOOLBAR_DATA_KEY);
        if(rtData != null) {
          ExecutionTarget target = rtData.getExecutionTarget();
          builder = target == null ?  builder.activeTarget() : builder.target(target);
        }
        else {
          builder = builder.activeTarget();
        }

        ExecutionEnvironment environment = builder.dataContext(dataContext).build();
        if(environmentCustomization != null) environmentCustomization.accept(environment);
        ExecutionManager.getInstance(project).restartRunProfile(environment);
      }
    }

    public static boolean canRun(@NotNull Project project, @NotNull List<SettingsAndEffectiveTarget> pairs, @NotNull Executor executor) {
      if (pairs.isEmpty()) {
        return false;
      }

      for (SettingsAndEffectiveTarget pair : pairs) {
        RunConfiguration configuration = pair.getConfiguration();
        if (configuration instanceof CompoundRunConfiguration) {
          if (!canRun(project, ((CompoundRunConfiguration)configuration).getConfigurationsWithEffectiveRunTargets(), executor)) {
            return false;
          }
          continue;
        }

        ProgramRunner<?> runner = ProgramRunner.getRunner(executor.getId(), configuration);
        if (runner == null
            || !ExecutionTargetManager.canRun(configuration, pair.getTarget())
            || ExecutionManager.getInstance(project).isStarting(executor.getId(), runner.getRunnerId())) {
          return false;
        }
      }
      return true;
    }
  }

  private static class RunCurrentFileActionStatus {
    private final boolean myEnabled;
    private final @Nls @NotNull String myTooltip;
    private final @NotNull Icon myIcon;

    private final @NotNull List<RunnerAndConfigurationSettings> myRunConfigs;

    private static RunCurrentFileActionStatus createDisabled(@Nls @NotNull String tooltip, @NotNull Icon icon) {
      return new RunCurrentFileActionStatus(false, tooltip, icon, Collections.emptyList());
    }

    private static RunCurrentFileActionStatus createEnabled(@Nls @NotNull String tooltip,
                                                            @NotNull Icon icon,
                                                            @NotNull List<RunnerAndConfigurationSettings> runConfigs) {
      return new RunCurrentFileActionStatus(true, tooltip, icon, runConfigs);
    }

    private RunCurrentFileActionStatus(boolean enabled,
                                       @Nls @NotNull String tooltip,
                                       @NotNull Icon icon,
                                       @NotNull List<RunnerAndConfigurationSettings> runConfigs) {
      myEnabled = enabled;
      myTooltip = tooltip;
      myIcon = icon;
      myRunConfigs = runConfigs;
    }
  }
}
