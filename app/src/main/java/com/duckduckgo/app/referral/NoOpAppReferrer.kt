/*
 * Copyright (c) 2026 YBrowser
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.duckduckgo.app.referral

import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.referral.api.AppReferrer
import com.squareup.anvil.annotations.ContributesBinding
import dagger.SingleInstanceIn
import javax.inject.Inject

/**
 * YBrowser does not perform DuckDuckGo install campaign attribution.
 *
 * Keep the API contract required by shared browsing/privacy features without
 * writing referral state or installing the Play Store referral SDK implementation.
 */
@ContributesBinding(scope = AppScope::class, boundType = AppReferrer::class)
@SingleInstanceIn(AppScope::class)
class NoOpAppReferrer @Inject constructor() : AppReferrer {
    override fun setOriginAttributeCampaign(origin: String?) = Unit
    override fun getOriginAttributeCampaign(): String? = null
    override fun isInstalledFromEuAuction(): Boolean = false
}
