package app.gamenative.ui.screen.wow

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import timber.log.Timber

/** Explains the BLZ51914400 lock and offers to verify the sign-in on battle.net. */
@Composable
fun BattleNetLockDialog(
    onVerify: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Blizzard blocked the sign-in", fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "Your password was accepted, but Blizzard's account security blocked the sign-in because it came " +
                    "from a device or network it doesn't recognise (BLZ51914400, status 14400).\n\n" +
                    "To unlock it:\n" +
                    "1. Turn off any VPN and stay on the network you play on.\n" +
                    "2. Tap Verify and sign in to your Battle.net account. If Blizzard asks for a code from your " +
                    "email, enter it.\n" +
                    "3. Tap Done & Play to start the game again.\n\n" +
                    "If the page won't sign you in, use Open in Browser instead.",
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        },
        confirmButton = { TextButton(onClick = onVerify) { Text("VERIFY", fontWeight = FontWeight.Bold) } },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    openInBrowser(context, BattleNetLoginLock.VERIFY_URL)
                    onDismiss()
                }) { Text("Open in Browser") }
                TextButton(onClick = onDismiss) { Text("Not Now") }
            }
        },
    )
}

/**
 * Full-screen Battle.net sign-in inside the app, so Blizzard sees the same device and network as the game. The page is
 * Blizzard's own: the app doesn't fill in or read anything on it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BattleNetVerifyScreen(
    onDone: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var currentUrl by remember { mutableStateOf(BattleNetLoginLock.VERIFY_URL) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        BackHandler {
            val view = webView
            if (view != null && view.canGoBack()) view.goBack() else onClose()
        }
        Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0B1220))) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = Uri.parse(currentUrl).host.orEmpty(),
                    color = Color(0xFFCBD5E1),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                TextButton(onClick = { openInBrowser(context, currentUrl) }) { Text("Open in Browser") }
                TextButton(onClick = onClose) { Text("Close") }
                TextButton(onClick = onDone) { Text("DONE & PLAY", fontWeight = FontWeight.Bold) }
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        val cookies = CookieManager.getInstance()
                        cookies.setAcceptCookie(true)
                        // Blizzard's sign-in may load its captcha from another site.
                        cookies.setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url
                                return when (url.scheme) {
                                    "https" -> false
                                    // Links such as mailto: belong to other apps; plain http stays blocked.
                                    "http" -> true
                                    else -> {
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                                        true
                                    }
                                }
                            }

                            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                url?.let { currentUrl = it }
                            }
                        }
                        loadUrl(BattleNetLoginLock.VERIFY_URL)
                        webView = this
                    }
                },
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush()
            webView?.destroy()
            webView = null
        }
    }
}

private fun openInBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Timber.w(it, "No browser to open $url") }
}
