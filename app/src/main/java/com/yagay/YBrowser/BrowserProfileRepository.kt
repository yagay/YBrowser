package com.yagay.YBrowser

import android.content.Context
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

const val DEFAULT_BROWSER_PROFILE_ID = "default"

data class BrowserProfile(
    val id: String,
    val name: String,
    val createdAt: Long,
)

object BrowserProfileRepository {
    private const val PREFS = "ybrowser_profiles"
    private const val KEY_ITEMS = "items"

    fun list(context: Context): List<BrowserProfile> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null)
        val array = runCatching { JSONArray(raw ?: "[]") }.getOrElse { JSONArray() }
        val custom = buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val id = obj.optString("id")
                val name = obj.optString("name").trim()
                if (
                    id.isBlank() ||
                    id == DEFAULT_BROWSER_PROFILE_ID ||
                    name.isBlank()
                ) {
                    continue
                }
                add(
                    BrowserProfile(
                        id = id,
                        name = name,
                        createdAt = obj.optLong("createdAt", 0L),
                    ),
                )
            }
        }.distinctBy { it.id }

        return listOf(
            BrowserProfile(
                id = DEFAULT_BROWSER_PROFILE_ID,
                name = "默认",
                createdAt = 0L,
            ),
        ) + custom.sortedBy { it.createdAt }
    }

    fun create(context: Context, nameRaw: String): BrowserProfile? {
        val name = nameRaw.trim().take(40)
        if (name.isBlank()) return null
        val existing = list(context)
        if (existing.any { it.name.equals(name, ignoreCase = true) }) return null

        val id = "p_" + System.currentTimeMillis().toString(36)
        val profile = BrowserProfile(
            id = id,
            name = name,
            createdAt = System.currentTimeMillis(),
        )
        persist(context, existing.filterNot { it.id == DEFAULT_BROWSER_PROFILE_ID } + profile)
        return profile
    }

    fun rename(context: Context, id: String, nameRaw: String): Boolean {
        if (id == DEFAULT_BROWSER_PROFILE_ID) return false
        val name = nameRaw.trim().take(40)
        if (name.isBlank()) return false
        val profiles = list(context)
        if (
            profiles.any {
                it.id != id && it.name.equals(name, ignoreCase = true)
            }
        ) {
            return false
        }
        val updated = profiles
            .filterNot { it.id == DEFAULT_BROWSER_PROFILE_ID }
            .map { if (it.id == id) it.copy(name = name) else it }
        persist(context, updated)
        return updated.any { it.id == id }
    }

    fun remove(context: Context, id: String): Boolean {
        if (id == DEFAULT_BROWSER_PROFILE_ID) return false
        val custom = list(context).filterNot {
            it.id == DEFAULT_BROWSER_PROFILE_ID || it.id == id
        }
        persist(context, custom)
        return true
    }

    fun name(context: Context, id: String): String =
        list(context).firstOrNull { it.id == id }?.name
            ?: if (id == DEFAULT_BROWSER_PROFILE_ID) "默认" else id

    private fun persist(context: Context, profiles: List<BrowserProfile>) {
        val array = JSONArray()
        profiles.filterNot { it.id == DEFAULT_BROWSER_PROFILE_ID }.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("createdAt", profile.createdAt),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }
}

object BrowserProfileStorage {
    fun geckoContextId(profileId: String): String? =
        profileId.takeIf { it != DEFAULT_BROWSER_PROFILE_ID }
            ?.let { "ybrowser-profile-" + sanitize(it) }

    fun webViewProfileName(profileId: String): String? =
        profileId.takeIf { it != DEFAULT_BROWSER_PROFILE_ID }
            ?.let { "ybrowser_" + sanitize(it) }

    fun clear(context: Context, profileId: String) {
        if (profileId == DEFAULT_BROWSER_PROFILE_ID) return
        geckoContextId(profileId)?.let { contextId ->
            runCatching {
                GeckoRuntimeHolder.get(context)
                    .storageController
                    .clearDataForSessionContext(contextId)
            }
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            webViewProfileName(profileId)?.let { name ->
                runCatching {
                    ProfileStore.getInstance().deleteProfile(name)
                }
            }
        }
    }

    fun webViewMultiProfileSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(64)
}
