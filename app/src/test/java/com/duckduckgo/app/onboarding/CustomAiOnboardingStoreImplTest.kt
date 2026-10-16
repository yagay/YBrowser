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
import com.duckduckgo.common.test.CoroutineTestRule
import com.duckduckgo.common.test.api.InMemorySharedPreferences
import com.duckduckgo.data.store.api.SharedPreferencesProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class CustomAiOnboardingStoreImplTest {
    @get:Rule val coroutineRule = CoroutineTestRule()

    private val preferences = InMemorySharedPreferences()
    private val provider: SharedPreferencesProvider = mock {
        on { getSharedPreferences(any(), any(), any()) } doReturn preferences
    }

    private fun store() = CustomAiOnboardingStoreImpl(
        sharedPreferencesProvider = provider,
        dispatcherProvider = coroutineRule.testDispatcherProvider,
    )

    @Test
    fun vendorCampaignOnboardingIsAlwaysDisabled() = runTest {
        val instance = store()
        assertFalse(instance.resolve())
        assertFalse(instance.isEnabled())
    }

    @Test
    fun previouslyPersistedAiCampaignIsDisabled() = runTest {
        preferences.edit { putBoolean("customAiOnboardingEnabled", true) }
        val instance = store()
        assertFalse(instance.resolve())
        assertFalse(instance.isEnabled())
        assertFalse(store().isEnabled())
    }
}
