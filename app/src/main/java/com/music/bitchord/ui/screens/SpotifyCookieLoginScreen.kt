package com.music.bitchord.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri

private const val SPOTIFY_LOGIN_URL =
    "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"

private const val SPOTIFY_LOGIN_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private const val SPOTIFY_LAYOUT_FIX = """
    (function () {
      var id = 'bitfuse-login-layout-fix';
      if (document.getElementById(id)) return;
      var st = document.createElement('style');
      st.id = id;
      st.textContent =
        'html, body { height: auto !important; min-height: 100% !important; overflow: visible !important; }' +
        'body > div { height: auto !important; min-height: 100% !important; }' +
        'main { position: static !important; height: auto !important; min-height: 100dvh !important;' +
        ' max-height: none !important; overflow: visible !important; }';
      (document.head || document.documentElement).appendChild(st);
    })();
"""

private val SPOTIFY_LOGIN_HOSTS = listOf(
    "spotify.com", "scdn.co", "google.com", "gstatic.com", "facebook.com", "apple.com",
)

private fun isLoginHost(uri: Uri?): Boolean {
    if (uri?.scheme != "https") return false
    val host = uri.host?.lowercase() ?: return false
    return SPOTIFY_LOGIN_HOSTS.any { host == it || host.endsWith(".$it") }
}

private fun isSpotifyHost(uri: Uri?): Boolean {
    if (uri?.scheme != "https") return false
    val host = uri.host?.lowercase() ?: return false
    return host == "spotify.com" || host.endsWith(".spotify.com")
}

/**
 * In-app Spotify login that captures the `sp_dc` session cookie by itself,
 * MetroFuse-style: the listener signs in to Spotify in this WebView, and the
 * moment the web player opens, the cookie is read out and handed to
 * [onConnected]. No pasting, no devtools.
 *
 * Only Spotify's own pages (plus the identity providers its sign-in hands
 * off to) ever load here. Signing out later clears just Spotify's cookies,
 * never the YouTube Music session in the same jar.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyCookieLoginScreen(
    onConnected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sent by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.userAgentString = SPOTIFY_LOGIN_USER_AGENT
                    if (Build.VERSION.SDK_INT >= 33) {
                        settings.isAlgorithmicDarkeningAllowed = false
                    } else if (Build.VERSION.SDK_INT >= 29) {
                        @Suppress("DEPRECATION")
                        settings.forceDark = WebSettings.FORCE_DARK_OFF
                    }
                    setBackgroundColor(0xFF121212.toInt())
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        // Spotify's own pages and the identity providers its sign-in
                        // hands off to; anything else is not part of logging in.
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean = !isLoginHost(request.url)

                        override fun onPageFinished(view: WebView, url: String?) {
                            if (isSpotifyHost(url?.toUri())) view.evaluateJavascript(SPOTIFY_LAYOUT_FIX, null)
                            loaded = true
                            if (sent || url?.startsWith("https://open.spotify.com") != true) return
                            val raw = CookieManager.getInstance().getCookie("https://open.spotify.com") ?: return
                            val token = raw.split(";")
                                .map { it.trim() }
                                .firstOrNull { it.startsWith("sp_dc=") }
                                ?.substringAfter("=")
                                ?.takeIf { it.isNotBlank() }
                                ?: return
                            sent = true
                            onConnected(token)
                        }
                    }
                    loadUrl(SPOTIFY_LOGIN_URL)
                }
            },
            onRelease = { it.destroy() },
        )
        if (!loaded) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
