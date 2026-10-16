/*
 * Copyright (c) 2024 DuckDuckGo
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

package com.duckduckgo.app.survey.rmf

import com.duckduckgo.remote.messaging.api.JsonMessageAction
import org.junit.Assert.assertNull
import org.junit.Test

class SurveyActionMapperTest {
    private val mapper = SurveyActionMapper()

    @Test
    fun vendorSurveyActionsAreIgnored() {
        val action = JsonMessageAction(
            type = "survey",
            value = "https://survey.example.org",
            additionalParameters = mapOf("queryParams" to "atb;var;delta"),
        )
        assertNull(mapper.evaluate(action))
    }

    @Test
    fun otherMessagesRemainUnchanged() {
        val action = JsonMessageAction(
            type = "url",
            value = "https://example.org",
            additionalParameters = null,
        )
        assertNull(mapper.evaluate(action))
    }
}
