/*
 * Copyright (c) 2026 DuckDuckGo
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

package com.duckduckgo.app.onboarding

import androidx.core.content.edit
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.data.store.api.SharedPreferencesProvider
import com.duckduckgo.di.scopes.AppScope
import com.squareup.anvil.annotations.ContributesBinding
import dagger.SingleInstanceIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

interface CustomAiOnboardingStore {
    /**
     * Returns whether the custom AI onboarding plan is engaged.
     *
     * The decision persisted by [CustomAiOnboardingResolver.resolve] at an onboarding plan build time.
     * Calling [isEnabled] does not re-evaluate.
     */
    suspend fun isEnabled(): Boolean
}

interface CustomAiOnboardingResolver {
    /** Always returns false: YBrowser does not perform vendor AI campaign onboarding. */
    suspend fun resolve(): Boolean
}

@SingleInstanceIn(scope = AppScope::class)
@ContributesBinding(scope = AppScope::class, boundType = CustomAiOnboardingStore::class)
@ContributesBinding(scope = AppScope::class, boundType = CustomAiOnboardingResolver::class)
class CustomAiOnboardingStoreImpl @Inject constructor(
    private val sharedPreferencesProvider: SharedPreferencesProvider,
    private val dispatcherProvider: DispatcherProvider,
) : CustomAiOnboardingStore, CustomAiOnboardingResolver {

    private val preferences by lazy { sharedPreferencesProvider.getSharedPreferences(PREFS_FILENAME) }

    private val resolveMutex = Mutex()

    // Vendor campaign-specific AI onboarding is unsupported in YBrowser.
    // Resolve immediately instead of waiting on an uninitialised Play referrer.
    override suspend fun resolve(): Boolean = resolveMutex.withLock {
        withContext(dispatcherProvider.io()) {
            preferences.edit { putBoolean(PREFS_KEY_ENABLED, false) }
            false
        }
    }

    override suspend fun isEnabled(): Boolean = resolveMutex.withLock {
        withContext(dispatcherProvider.io()) {
            preferences.getBoolean(PREFS_KEY_ENABLED, false)
        }
    }

    companion object {
        private const val PREFS_FILENAME = "com.duckduckgo.app.onboarding.customai"

        private const val PREFS_KEY_ENABLED = "customAiOnboardingEnabled"
    }
}
