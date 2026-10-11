package dev.agent.app

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Hosts the web UI from assets/web. Everything interesting happens in [AgentBridge]; this
 * class only owns the WebView, system insets, folder picking and the back button.
 */
class MainActivity : ComponentActivity(), AgentBridge.Host {
    private lateinit var web: WebView
    private var pendingPick: ((JsonObject) -> Unit)? = null

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val callback = pendingPick ?: return@registerForActivityResult
        pendingPick = null
        callback(
            when {
                uri == null -> buildJsonObject { put("cancelled", true) }
                else -> treeUriToPath(uri)?.let { path -> buildJsonObject { put("path", path) } }
                    ?: buildJsonObject { put("error", "Pick a folder on phone storage, not a cloud provider.") }
            },
        )
    }

    // The run notification is shown only once the user allows it. Tasks run either way.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setBackgroundDrawable(ColorDrawable(INK))

        web = WebView(this).apply {
            setBackgroundColor(INK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                // The UI never navigates; block links so the page cannot leave the bundle.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            }
        }
        // Pad the page so the status bar, navigation bar and keyboard never cover it.
        ViewCompat.setOnApplyWindowInsetsListener(web) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        val bridge = AgentBridge(
            context = this,
            host = this,
            scope = lifecycleScope,
            runJs = { js -> runOnUiThread { web.evaluateJavascript(js, null) } },
        )
        web.addJavascriptInterface(bridge, "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/web/index.html")
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.handleBack ? window.handleBack() : false") { result ->
                    if (result != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    override fun pickFolder(onResult: (JsonObject) -> Unit) {
        pendingPick = onResult
        runOnUiThread { folderPicker.launch(null) }
    }

    override fun allFilesGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    override fun openAllFilesSettings() {
        runOnUiThread {
            val uri = Uri.parse("package:$packageName")
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
            }
        }
    }

    private companion object {
        val INK: Int = Color.parseColor("#0E0E11")
    }
}
