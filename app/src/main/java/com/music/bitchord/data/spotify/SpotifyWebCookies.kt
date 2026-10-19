package com.music.bitchord.data.spotify

import android.webkit.CookieManager

/**
 * Clears Spotify's WebView cookies on disconnect. The login WebView keeps
 * its own jar: without this, the next "Log in with browser" is waved
 * straight through as the account just signed out of, with no chance to
 * choose another. Only `*.spotify.com` cookies go — the same jar holds the
 * YouTube Music sign-in.
 */
object SpotifyWebCookies {
    fun clear() {
        val cookies = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        for (url in listOf(
            "https://open.spotify.com",
            "https://accounts.spotify.com",
            "https://www.spotify.com",
            "https://spotify.com",
        )) {
            val names = cookies.getCookie(url)?.split(";")
                ?.map { it.substringBefore("=").trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            for (name in names) {
                val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
                cookies.setCookie(url, expired)
                cookies.setCookie(url, "$expired; Domain=.spotify.com")
            }
        }
        cookies.flush()
    }
}
