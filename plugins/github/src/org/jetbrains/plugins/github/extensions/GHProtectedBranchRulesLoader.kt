// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.github.extensions

import git4idea.remote.hosting.findKnownRepositories
import com.intellij.concurrency.SensitiveProgressWrapper
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.util.PatternUtil
import git4idea.config.GitSharedSettings
import git4idea.fetch.GitFetchHandler
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import org.jetbrains.plugins.github.api.GHGQLRequests
import org.jetbrains.plugins.github.api.GithubApiRequestExecutorManager
import org.jetbrains.plugins.github.api.util.SimpleGHGQLPagesLoader
import org.jetbrains.plugins.github.authentication.GithubAuthenticationManager
import org.jetbrains.plugins.github.i18n.GithubBundle
import org.jetbrains.plugins.github.util.GHHostedRepositoriesManager
import org.jetbrains.plugins.github.util.GithubProjectSettings

private val LOG = logger<GHProtectedBranchRulesLoader>()

internal class GHProtectedBranchRulesLoader : GitFetchHandler {

  override fun doAfterSuccessfulFetch(project: Project, fetches: Map<GitRepository, List<GitRemote>>, indicator: ProgressIndicator) {
    try {
      loadProtectionRules(indicator, fetches, project)
    }
    catch (e: Exception) {
      if (e is ProcessCanceledException) {
        throw e
      }
      LOG.info("Error occurred while trying to load branch protection rules", e)
    }
  }

  private fun loadProtectionRules(indicator: ProgressIndicator,
                                  fetches: Map<GitRepository, List<GitRemote>>,
                                  project: Project) {
    val authenticationManager = GithubAuthenticationManager.getInstance()
    if (!GitSharedSettings.getInstance(project).isSynchronizeBranchProtectionRules || !authenticationManager.hasAccounts()) {
      runInEdt {
        project.service<GithubProjectSettings>().branchProtectionPatterns = arrayListOf()
      }
      return
    }

    indicator.text = GithubBundle.message("progress.text.loading.protected.branches")

    val branchProtectionPatterns = mutableSetOf<String>()
    for ((repository, remotes) in fetches) {
      indicator.checkCanceled()

      for (remote in remotes) {
        indicator.checkCanceled()

        val repositoryMapping =
          project.service<GHHostedRepositoriesManager>().findKnownRepositories(repository)
            .find { it.remote.remote == remote }
          ?: continue

        val serverPath = repositoryMapping.repository.serverPath
        val defaultAccount = authenticationManager.getDefaultAccount(repository.project)

        val account =
          if (defaultAccount != null
              && defaultAccount.server.equals(serverPath, true)) {
            defaultAccount
          }
          else {
            authenticationManager.getAccounts().find {
              it.server.equals(serverPath, true)
            }
          } ?: continue

        val requestExecutor = GithubApiRequestExecutorManager.getInstance().getExecutor(account)
        SimpleGHGQLPagesLoader(requestExecutor, { GHGQLRequests.Repo.getProtectionRules(repositoryMapping.repository) })
          .loadAll(SensitiveProgressWrapper((indicator)))
          .forEach { rule -> branchProtectionPatterns.add(PatternUtil.convertToRegex(rule.pattern)) }
      }
    }

    runInEdt {
      project.service<GithubProjectSettings>().branchProtectionPatterns = branchProtectionPatterns.toMutableList()
    }
  }

}
