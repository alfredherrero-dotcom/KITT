package com.kitt.audiobridge

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class PanelActivity : AppCompatActivity() {

    companion object {
        private const val RETRY_INTERVAL_MS = 2000L
        private const val BACK_PRESS_WINDOW_MS = 2000L
        private const val LOCATION_PERMISSION_REQUEST_CODE = 200

        /*
         * Durante las pruebas usamos la URL directamente para evitar que
         * SharedPreferences conserve una URL anterior.
         *
         * Cuando todo funcione, puedes volver a usar:
         * AppPreferences.getPanelUrl(this)
         */
        private const val PANEL_URL =
            "http://127.0.0.1:41088/storage/emulated/0/KITT/index.html"
    }

    private lateinit var webView: WebView
    private lateinit var retryText: TextView

    private val retryHandler = Handler(Looper.getMainLooper())

    private var retryScheduled = false
    private var lastLoadHadError = false
    private var lastBackPressTime = 0L

    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_panel)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        applyImmersiveMode()

        webView = findViewById(R.id.webview_panel)
        retryText = findViewById(R.id.text_retry)

        setupWebView()
        loadPanel()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val currentTime = System.currentTimeMillis()

                    if (
                        currentTime - lastBackPressTime <
                        BACK_PRESS_WINDOW_MS
                    ) {
                        finish()
                    } else {
                        lastBackPressTime = currentTime

                        Toast.makeText(
                            this@PanelActivity,
                            R.string.panel_back_to_exit,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            applyImmersiveMode()
        }
    }

    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowInsetsControllerCompat(
            window,
            window.decorView
        )

        controller.hide(
            WindowInsetsCompat.Type.systemBars()
        )

        controller.systemBarsBehavior =
            WindowInsetsControllerCompat
                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams
                        .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun setupWebView() {
        webView.setBackgroundColor(Color.BLACK)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false

            allowContentAccess = true
            allowFileAccess = true

            databaseEnabled = true

            userAgentString =
                "Mozilla/5.0 (Linux; Android 13) " +
                "AppleWebKit/537.36 " +
                "(KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        val cookieManager = CookieManager.getInstance()

        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(
            webView,
            true
        )

        webView.webChromeClient =
            object : WebChromeClient() {

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String,
                    callback: GeolocationPermissions.Callback
                ) {
                    val permissionGranted =
                        ContextCompat.checkSelfPermission(
                            this@PanelActivity,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED

                    if (permissionGranted) {
                        callback.invoke(
                            origin,
                            true,
                            false
                        )
                    } else {
                        pendingGeoOrigin = origin
                        pendingGeoCallback = callback

                        ActivityCompat.requestPermissions(
                            this@PanelActivity,
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ),
                            LOCATION_PERMISSION_REQUEST_CODE
                        )
                    }
                }
            }

        webView.webViewClient =
            object : WebViewClient() {

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: Bitmap?
                ) {
                    super.onPageStarted(
                        view,
                        url,
                        favicon
                    )

                    lastLoadHadError = false
                }

                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {
                    super.onPageFinished(
                        view,
                        url
                    )

                    /*
                     * Un servidor puede devolver una página 401 o 403
                     * sin activar onReceivedError().
                     *
                     * Por eso comprobamos también el contenido visible.
                     */
                    view?.evaluateJavascript(
                        """
                        (function() {
                            var text =
                                document.body
                                ? document.body.innerText
                                : '';

                            return text.substring(0, 500);
                        })();
                        """.trimIndent()
                    ) { result ->

                        val pageText =
                            result
                                ?.lowercase()
                                .orEmpty()

                        val unauthorized =
                            pageText.contains("unauthorized") ||
                            pageText.contains("401")

                        if (unauthorized) {
                            lastLoadHadError = true
                            showErrorScreen(
                                "Error 401: el servidor ha rechazado la petición"
                            )
                        } else if (!lastLoadHadError) {
                            hideRetryScreen()
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(
                        view,
                        request,
                        error
                    )

                    if (request?.isForMainFrame == true) {
                        lastLoadHadError = true

                        val errorCode =
                            error?.errorCode ?: -1

                        val description =
                            error?.description
                                ?.toString()
                                ?: "Error desconocido"

                        showErrorScreen(
                            "Error $errorCode: $description"
                        )
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    return false
                }
            }
    }

    private fun loadPanel() {
        lastLoadHadError = false

        retryText.text =
            getString(R.string.panel_connecting)

        retryText.visibility = View.VISIBLE
        webView.visibility = View.INVISIBLE

        /*
         * Usamos la URL fija mientras diagnosticamos.
         */
        webView.loadUrl(PANEL_URL)

        /*
         * Cuando funcione correctamente, puedes sustituir
         * la línea anterior por:
         *
         * webView.loadUrl(
         *     AppPreferences.getPanelUrl(this)
         * )
         */
    }

    private fun showErrorScreen(
        message: String
    ) {
        webView.visibility = View.INVISIBLE

        retryText.text =
            "$message\n\nReintentando..."

        retryText.visibility = View.VISIBLE

        scheduleRetry()
    }

    private fun showRetryScreen() {
        webView.visibility = View.INVISIBLE

        retryText.text =
            getString(R.string.panel_connecting)

        retryText.visibility = View.VISIBLE

        scheduleRetry()
    }

    private fun scheduleRetry() {
        if (retryScheduled) {
            return
        }

        retryScheduled = true

        retryHandler.postDelayed(
            {
                retryScheduled = false
                loadPanel()
            },
            RETRY_INTERVAL_MS
        )
    }

    private fun hideRetryScreen() {
        retryHandler.removeCallbacksAndMessages(null)
        retryScheduled = false

        retryText.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (
            requestCode ==
            LOCATION_PERMISSION_REQUEST_CODE
        ) {
            val permissionGranted =
                grantResults.isNotEmpty() &&
                grantResults[0] ==
                PackageManager.PERMISSION_GRANTED

            val origin = pendingGeoOrigin
            val callback = pendingGeoCallback

            if (
                origin != null &&
                callback != null
            ) {
                callback.invoke(
                    origin,
                    permissionGranted,
                    false
                )
            }

            pendingGeoOrigin = null
            pendingGeoCallback = null
        }
    }

    override fun onDestroy() {
        retryHandler.removeCallbacksAndMessages(null)

        pendingGeoCallback?.invoke(
            pendingGeoOrigin ?: "",
            false,
            false
        )

        pendingGeoOrigin = null
        pendingGeoCallback = null

        webView.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }

        super.onDestroy()
    }
}
