/*
 * Copyright (c) 2025 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

package com.duckduckgo.adclick.impl.metrics

import com.duckduckgo.di.scopes.AppScope
import com.squareup.anvil.annotations.ContributesBinding
import dagger.SingleInstanceIn
import javax.inject.Inject

/**
 * Preserves the ad-click event callback without storing or uploading
 * DuckDuckGo's install-attributed ad-click metrics.
 */
interface AdClickCollector {
    fun onAdClick()
}

@ContributesBinding(AppScope::class, AdClickCollector::class)
@SingleInstanceIn(AppScope::class)
class NoOpAdClickCollector @Inject constructor() : AdClickCollector {
    override fun onAdClick() = Unit
}
