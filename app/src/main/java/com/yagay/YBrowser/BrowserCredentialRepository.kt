package com.yagay.YBrowser

import android.content.Context
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime

data class BrowserCredential(
    val guid: String,
    val origin: String,
    val formActionOrigin: String?,
    val httpRealm: String?,
    val username: String,
    val password: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long,
)

object BrowserCredentialRepository {
    private const val VAULT_KEY = "credential_vault_v1"
    private const val MAX_CREDENTIALS = 500

    @Synchronized
    fun list(context: Context): List<BrowserCredential> =
        decode(BrowserSecretStore.get(context, VAULT_KEY))
            .sortedWith(
                compareByDescending<BrowserCredential> { it.lastUsedAt }
                    .thenByDescending { it.updatedAt }
            )

    @Synchronized
    fun findForDomain(
        context: Context,
        domain: String,
    ): List<BrowserCredential> {
        val needle = domain
            .trim()
            .lowercase()
            .removePrefix("www.")
        if (needle.isBlank()) return emptyList()
        return list(context).filter { credential ->
            val host = runCatching {
                android.net.Uri.parse(credential.origin).host
                    .orEmpty()
                    .lowercase()
                    .removePrefix("www.")
            }.getOrDefault("")
            host == needle ||
                host.endsWith("." + needle) ||
                needle.endsWith("." + host).takeIf { host.isNotBlank() } == true
        }
    }

    @Synchronized
    fun save(
        context: Context,
        login: Autocomplete.LoginEntry,
    ): BrowserCredential {
        val now = System.currentTimeMillis()
        val current = list(context).toMutableList()
        val incomingGuid = login.guid?.takeIf { it.isNotBlank() }
        val index = current.indexOfFirst { saved ->
            (incomingGuid != null && saved.guid == incomingGuid) ||
                (
                    saved.origin == login.origin &&
                        saved.username == login.username &&
                        saved.httpRealm == login.httpRealm
                    )
        }
        val old = current.getOrNull(index)
        val saved = BrowserCredential(
            guid = incomingGuid ?: old?.guid ?: UUID.randomUUID().toString(),
            origin = login.origin,
            formActionOrigin = login.formActionOrigin,
            httpRealm = login.httpRealm,
            username = login.username,
            password = login.password,
            createdAt = old?.createdAt ?: now,
            updatedAt = now,
            lastUsedAt = old?.lastUsedAt ?: 0L,
        )
        if (index >= 0) current[index] = saved else current += saved
        persist(context, current)
        return saved
    }

    @Synchronized
    fun markUsed(
        context: Context,
        login: Autocomplete.LoginEntry,
    ) {
        val now = System.currentTimeMillis()
        val updated = list(context).map { saved ->
            if (
                (!login.guid.isNullOrBlank() && saved.guid == login.guid) ||
                (
                    saved.origin == login.origin &&
                        saved.username == login.username
                    )
            ) {
                saved.copy(lastUsedAt = now)
            } else {
                saved
            }
        }
        persist(context, updated)
    }

    @Synchronized
    fun delete(
        context: Context,
        guid: String,
    ) {
        persist(context, list(context).filterNot { it.guid == guid })
    }

    @Synchronized
    fun clear(context: Context) {
        BrowserSecretStore.remove(context, VAULT_KEY)
    }

    fun toGecko(entry: BrowserCredential): Autocomplete.LoginEntry =
        Autocomplete.LoginEntry.Builder()
            .guid(entry.guid)
            .origin(entry.origin)
            .formActionOrigin(entry.formActionOrigin)
            .httpRealm(entry.httpRealm)
            .username(entry.username)
            .password(entry.password)
            .build()

    private fun persist(
        context: Context,
        credentials: List<BrowserCredential>,
    ) {
        val array = JSONArray()
        credentials
            .sortedByDescending { maxOf(it.updatedAt, it.lastUsedAt) }
            .take(MAX_CREDENTIALS)
            .forEach { entry ->
                array.put(
                    JSONObject()
                        .put("guid", entry.guid)
                        .put("origin", entry.origin)
                        .put("formActionOrigin", entry.formActionOrigin ?: JSONObject.NULL)
                        .put("httpRealm", entry.httpRealm ?: JSONObject.NULL)
                        .put("username", entry.username)
                        .put("password", entry.password)
                        .put("createdAt", entry.createdAt)
                        .put("updatedAt", entry.updatedAt)
                        .put("lastUsedAt", entry.lastUsedAt)
                )
            }
        BrowserSecretStore.put(context, VAULT_KEY, array.toString())
    }

    private fun decode(raw: String?): List<BrowserCredential> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val origin = item.optString("origin")
                if (origin.isBlank()) continue
                val password = item.optString("password")
                val username = item.optString("username")
                add(
                    BrowserCredential(
                        guid = item.optString("guid")
                            .ifBlank { UUID.randomUUID().toString() },
                        origin = origin,
                        formActionOrigin =
                            item.optString("formActionOrigin")
                                .takeIf { it.isNotBlank() && it != "null" },
                        httpRealm =
                            item.optString("httpRealm")
                                .takeIf { it.isNotBlank() && it != "null" },
                        username = username,
                        password = password,
                        createdAt = item.optLong("createdAt", 0L),
                        updatedAt = item.optLong("updatedAt", 0L),
                        lastUsedAt = item.optLong("lastUsedAt", 0L),
                    )
                )
            }
        }
    }
}

object GeckoCredentialBridge {
    @Volatile
    private var installedRuntime: GeckoRuntime? = null

    fun ensureInstalled(
        context: Context,
        runtime: GeckoRuntime,
    ) {
        if (installedRuntime === runtime) return
        synchronized(this) {
            if (installedRuntime === runtime) return
            val appContext = context.applicationContext
            runtime.setAutocompleteStorageDelegate(
                object : Autocomplete.StorageDelegate {
                    override fun onLoginFetch(
                        domain: String,
                    ): GeckoResult<Array<Autocomplete.LoginEntry>> =
                        GeckoResult.fromValue(
                            BrowserCredentialRepository
                                .findForDomain(appContext, domain)
                                .map(BrowserCredentialRepository::toGecko)
                                .toTypedArray()
                        )

                    override fun onLoginFetch():
                        GeckoResult<Array<Autocomplete.LoginEntry>> =
                        GeckoResult.fromValue(
                            BrowserCredentialRepository
                                .list(appContext)
                                .map(BrowserCredentialRepository::toGecko)
                                .toTypedArray()
                        )

                    override fun onLoginSave(
                        login: Autocomplete.LoginEntry,
                    ) {
                        BrowserCredentialRepository.save(appContext, login)
                    }

                    override fun onLoginUsed(
                        login: Autocomplete.LoginEntry,
                        usedFields: Int,
                    ) {
                        BrowserCredentialRepository.markUsed(appContext, login)
                    }
                }
            )
            installedRuntime = runtime
        }
    }
}
