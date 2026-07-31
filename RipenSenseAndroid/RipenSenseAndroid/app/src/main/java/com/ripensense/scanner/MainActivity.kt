package com.ripensense.scanner

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Thin native shell around the RipenSense AI Flask scanner app.
 *
 * The actual scanning logic (camera capture UI, fruit detection, ML
 * classification, etc.) all lives in the Flask app's HTML/JS — this
 * Activity's only job is to:
 *   1. Host a WebView pointed at wherever that Flask server is running.
 *   2. Grant the WebView's camera permission requests (getUserMedia), which
 *      Android does NOT do automatically — this is the #1 thing that trips
 *      people up wrapping a camera-using web app in a WebView.
 *   3. Support the "Upload Photo" tab's <input type="file"> picker.
 *   4. Let the person type in the server's address, since the Flask app
 *      isn't bundled into this APK — it still runs on a computer.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var emptyState: LinearLayout

    private var pendingPermissionRequest: PermissionRequest? = null
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private val prefs by lazy { getSharedPreferences("ripensense_prefs", Context.MODE_PRIVATE) }

    // --- Runtime CAMERA permission (Android system-level, separate from the
    // WebView-level PermissionRequest below) -------------------------------
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingPermissionRequest?.let { request ->
                if (granted) request.grant(request.resources) else request.deny()
            }
            pendingPermissionRequest = null
            if (!granted) {
                Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    // --- File chooser result, for the "Upload Photo" tab's <input type=file> ---
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK && data != null) {
                val clipData = data.clipData
                if (clipData != null) {
                    Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                } else {
                    data.data?.let { arrayOf(it) }
                }
            } else null
            fileUploadCallback?.onReceiveValue(uris)
            fileUploadCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        toolbar.setOnMenuItemClickListener { onOptionsItemSelected(it) }

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        emptyState = findViewById(R.id.emptyState)

        findViewById<MaterialButton>(R.id.setServerButton).setOnClickListener { showServerDialog() }

        setupWebView()
        swipeRefresh.setOnRefreshListener {
            webView.reload()
            swipeRefresh.isRefreshing = false
        }

        loadServerOrPrompt()
    }

    private fun setupWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    showEmptyState(getString(R.string.load_error))
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            // *** The key camera-in-WebView fix ***
            // A page calling navigator.mediaDevices.getUserMedia({video:true})
            // triggers this callback. Without explicitly granting it here,
            // the camera silently fails inside the WebView even if the
            // Android CAMERA permission was already granted at the OS level.
            override fun onPermissionRequest(request: PermissionRequest) {
                val needsCamera = request.resources.any { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
                if (!needsCamera) {
                    runOnUiThread { request.grant(request.resources) }
                    return
                }
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    runOnUiThread { request.grant(request.resources) }
                } else {
                    pendingPermissionRequest = request
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }

            // Supports the "Upload Photo" tab's native file-picker.
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback = filePathCallback
                val intent = fileChooserParams?.createIntent()
                    ?: android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                    }
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    fileUploadCallback = null
                    false
                }
            }
        }
    }

    // --- Server address management -----------------------------------------

    private fun loadServerOrPrompt() {
        val saved = prefs.getString(KEY_SERVER_URL, null)
        if (saved.isNullOrBlank()) {
            showEmptyState(getString(R.string.no_server_set))
        } else {
            loadUrl(saved)
        }
    }

    private fun loadUrl(url: String) {
        hideEmptyState()
        webView.loadUrl(url)
    }

    private fun showEmptyState(message: String) {
        findViewById<android.widget.TextView>(R.id.emptyStateText).text = message
        emptyState.visibility = android.view.View.VISIBLE
        webView.visibility = android.view.View.GONE
    }

    private fun hideEmptyState() {
        emptyState.visibility = android.view.View.GONE
        webView.visibility = android.view.View.VISIBLE
    }

    private fun showServerDialog() {
        val container = FrameLayout(this)
        val padding = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = getString(R.string.server_dialog_hint)
            setText(prefs.getString(KEY_SERVER_URL, ""))
        }
        container.setPadding(padding, padding / 2, padding, 0)
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle(R.string.server_dialog_title)
            .setMessage(R.string.server_dialog_help)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                var url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "http://$url"
                    }
                    prefs.edit().putString(KEY_SERVER_URL, url).apply()
                    loadUrl(url)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Toolbar menu ---------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean = true // menu is set via XML on the Toolbar itself

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reload -> {
                webView.reload()
                true
            }
            R.id.action_server -> {
                showServerDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // --- Back button navigates WebView history first ---------------------
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val KEY_SERVER_URL = "server_url"
    }
}
