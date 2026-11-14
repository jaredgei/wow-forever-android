package app.gamenative.ui.screen.wow.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shows Battle.net's own page and reads only its final navigation address.
 * The ST redirect contract follows Scuffle's MIT-licensed browser login implementation.
 */
@SuppressLint("SetJavaScriptEnabled") // Battle.net's sign-in page requires JavaScript.
@Composable
internal fun BattleNetWebDialog(
    url: String,
    onTicket: (ByteArray) -> Unit,
    onCancel: () -> Unit,
    onError: (String) -> Unit,
) {
    val ticketCallback by rememberUpdatedState(onTicket)
    val cancelCallback by rememberUpdatedState(onCancel)
    val errorCallback by rememberUpdatedState(onError)
    val completed = remember(url) { AtomicBoolean(false) }
    if (!BrowserAuthUrl.isInitialUrl(url)) {
        LaunchedEffect(url) {
            if (completed.compareAndSet(false, true)) errorCallback("Battle.net returned an unsupported sign-in address.")
        }
        return
    }

    val context = LocalContext.current
    var origin by remember(url) { mutableStateOf(BrowserAuthUrl.displayOrigin(url) ?: "Battle.net") }
    val webView = remember(url) {
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                @Suppress("DEPRECATION")
                saveFormData = false
            }
            webViewClient = object : WebViewClient() {
                private fun fail(view: WebView, message: String) {
                    view.stopLoading()
                    if (completed.compareAndSet(false, true)) errorCallback(message)
                }

                private fun navigate(view: WebView, address: String): Boolean {
                    if (completed.get()) return true
                    val ticket = BrowserAuthUrl.ticket(address)
                    if (ticket != null) {
                        view.stopLoading()
                        if (completed.compareAndSet(false, true)) ticketCallback(ticket) else ticket.fill(0)
                        return true
                    }
                    if (!BrowserAuthUrl.isNavigationUrl(address)) {
                        fail(view, "Sign-in tried to open an unsupported address. Cancel and try again.")
                        return true
                    }
                    origin = BrowserAuthUrl.displayOrigin(address) ?: "Battle.net"
                    return false
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return !request.url.scheme.equals("https", ignoreCase = true)
                    return navigate(view, request.url.toString())
                }

                override fun onPageStarted(view: WebView, address: String, favicon: Bitmap?) {
                    // Covers navigations for which WebView does not invoke shouldOverrideUrlLoading (e.g. POST).
                    navigate(view, address)
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.isForMainFrame && !BrowserAuthUrl.isNavigationUrl(request.url.toString())) {
                        // No network request is made to the localhost callback or another unapproved origin.
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return null
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    fail(view, "The sign-in page's secure connection could not be verified.")
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && !completed.get()) fail(view, "The sign-in page could not load. Check your connection and try again.")
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame && !completed.get()) fail(view, "Battle.net could not complete this sign-in page. Try again later.")
                }
            }
            loadUrl(url)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            completed.set(true)
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.removeAllViews()
            webView.destroy()
        }
    }
    val cancel = {
        if (completed.compareAndSet(false, true)) {
            webView.stopLoading()
            cancelCallback()
        }
    }
    Dialog(onDismissRequest = cancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(origin, modifier = Modifier.weight(1f))
                    TextButton(onClick = cancel) { Text("Cancel") }
                }
                AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxSize())
            }
        }
    }
}
