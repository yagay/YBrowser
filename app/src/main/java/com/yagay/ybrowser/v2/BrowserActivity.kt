package com.yagay.ybrowser.v2

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class BrowserActivity : ComponentActivity() {
    private lateinit var browser: BrowserController
    private data class FileRequest(val owner: Long, val callback: ValueCallback<Array<Uri>>)
    private var pendingFile: FileRequest? = null
    private var pendingSitePermission: PermissionRequest? = null

    private val documentPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pending = pendingFile
        pendingFile = null
        if (pending != null) {
            val uris = linkedSetOf<Uri>()
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uris.add(it) }
                result.data?.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                }
            }
            pending.callback.onReceiveValue(
                uris.toTypedArray().takeIf { it.isNotEmpty() && browser.tabs.any { tab -> tab.id == pending.owner } }
            )
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val pending = pendingSitePermission
        pendingSitePermission = null
        if (pending != null) {
            if (grants.isNotEmpty() && grants.values.all { it }) pending.grant(pending.resources)
            else pending.deny()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        browser = BrowserController(this)
        browser.restore(intent?.data?.takeIf { it.scheme in setOf("http", "https") }?.toString())
        setContent {
            BackHandler {
                if (!browser.back()) {
                    if (browser.tabs.size > 1) browser.closeTab(browser.activeId)
                    else finish()
                }
            }
            BrowserScreen(browser)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.takeIf { it.scheme in setOf("http", "https") }
            ?.let { browser.addTab(it.toString()) }
    }

    fun chooseFile(
        owner: Long,
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean {
        pendingFile?.callback?.onReceiveValue(null)
        pendingFile = FileRequest(owner, callback)
        val accepts = params.acceptTypes.orEmpty().map { it.trim() }
            .filter { '/' in it && it != "*/*" }.distinct()
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (accepts.size == 1) accepts.first() else "*/*"
            if (accepts.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, accepts.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            documentPicker.launch(picker)
            true
        } catch (_: Exception) {
            pendingFile = null
            callback.onReceiveValue(null)
            true
        }
    }

    fun cancelPendingChooser(owner: Long) {
        if (pendingFile?.owner == owner) {
            val pending = pendingFile
            pendingFile = null
            pending?.callback?.onReceiveValue(null)
        }
    }

    fun confirmSitePermission(request: PermissionRequest) {
        runOnUiThread {
            val audio = PermissionRequest.RESOURCE_AUDIO_CAPTURE
            val video = PermissionRequest.RESOURCE_VIDEO_CAPTURE
            val resourceSet = request.resources.toSet()
            if (resourceSet.isEmpty() || !setOf(audio, video).containsAll(resourceSet) ||
                pendingSitePermission != null) {
                request.deny()
                return@runOnUiThread
            }
            val required = resourceSet.map {
                if (it == video) Manifest.permission.CAMERA else Manifest.permission.RECORD_AUDIO
            }
            AlertDialog.Builder(this)
                .setTitle("Website permission")
                .setMessage("${request.origin} wants access to ${if (video in resourceSet) "camera" else ""}${if (audio in resourceSet) " microphone" else ""}.")
                .setPositiveButton("Allow") { _, _ ->
                    val missing = required.filter {
                        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) request.grant(request.resources)
                    else {
                        pendingSitePermission = request
                        permissionLauncher.launch(missing.toTypedArray())
                    }
                }
                .setNegativeButton("Deny") { _, _ -> request.deny() }
                .setOnCancelListener { request.deny() }
                .show()
        }
    }

    override fun onStop() {
        browser.persist()
        super.onStop()
    }
    override fun onDestroy() {
        pendingFile?.callback?.onReceiveValue(null)
        pendingFile = null
        pendingSitePermission?.deny()
        pendingSitePermission = null
        browser.dispose()
        super.onDestroy()
    }
}
