/*
 * Copyright (c) 2018 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.launch

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckduckgo.anvil.annotations.ContributesViewModel
import com.duckduckgo.app.onboarding.orchestrator.NewUserOnboardingPlanBootstrapper
import com.duckduckgo.app.onboarding.store.UserStageStore
import com.duckduckgo.app.onboarding.store.isNewUser
import com.duckduckgo.common.utils.SingleLiveEvent
import com.duckduckgo.di.scopes.ActivityScope
import com.duckduckgo.onboarding.api.LinearOnboardingHost
import com.duckduckgo.testseeder.api.TestScenarioSeeder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@ContributesViewModel(ActivityScope::class)
class LaunchViewModel @Inject constructor(
    private val userStageStore: UserStageStore,
    private val testScenarioSeeder: TestScenarioSeeder,
    private val newUserOnboardingPlanBootstrapper: NewUserOnboardingPlanBootstrapper,
) : ViewModel() {

    val command: SingleLiveEvent<Command> = SingleLiveEvent()

    sealed class Command {
        data object Onboarding : Command()
        data class Home(val replaceExistingSearch: Boolean = false) : Command()
    }

    fun start(intent: Intent) {
        viewModelScope.launch {
            seedTestScenario(intent)
            // No DDG install-attribution wait: proceed directly to browser startup.
            showOnboardingOrHome()
        }
    }

    private suspend fun seedTestScenario(intent: Intent) {
        runCatching {
            testScenarioSeeder.seedIfNeeded(intent.extras.toStringMap())
        }
        // runCatching swallows CancellationException; re-check so a cancelled viewModelScope
        // (activity finished mid-launch) stops the rest of start() instead of routing into a dead UI.
        currentCoroutineContext().ensureActive()
    }

    private fun Bundle?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return keySet().mapNotNull { key -> getString(key)?.let { key to it } }.toMap()
    }

    suspend fun showOnboardingOrHome() {
        if (userStageStore.isNewUser()) {
            val startState = newUserOnboardingPlanBootstrapper.startNewUserOnboardingPlan()
            when (startState.currentStep.host) {
                LinearOnboardingHost.OnboardingActivity -> {
                    command.value = Command.Onboarding
                }
                LinearOnboardingHost.BrowserActivity -> {
                    command.value = Command.Home()
                }
                else -> {
                    // extend to support initial hosts in the future
                    throw IllegalArgumentException("unsupported initial onboarding host transition")
                }
            }
        } else {
            command.value = Command.Home()
        }
    }

}
