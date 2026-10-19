package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import org.jetbrains.compose.resources.painterResource
import com.music.bitchord.sharedui.resources.spotify_logo
import com.music.bitchord.sharedui.resources.Res
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.spotify.SpotifyPlaylist
import com.music.bitchord.data.spotify.SpotifySession
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * The signed-in Spotify account's library: synced playlists + Liked Songs.
 *
 * Sign-in is a pasted `sp_dc` session cookie taken from the listener's own
 * logged-in Spotify web player — no OAuth, no client id, no dashboard. The
 * rows come from the last successful sync ([SpotifySession.cachedPlaylists]),
 * not from a live request, so the list works offline. Tapping one opens it as
 * an ordinary playlist page ([onOpenPlaylist]) — this screen is only the way
 * in. Playback of any row resolves through the listener's own provider
 * sources, never as a Spotify stream.
 */
@Composable
fun SpotifyLibraryScreen(
    onOpenPlaylist: (SpotifyPlaylist) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val sessionState by SpotifySession.state.collectAsStateWithLifecycle()
    val homeError by SpotifySession.homeError.collectAsStateWithLifecycle()
    val lastSyncMs by AppSettings.spotifyLastSyncMs.collectAsStateWithLifecycle()
    val lastSyncError by AppSettings.spotifyLastSyncError.collectAsStateWithLifecycle()
    val uiScope = rememberCoroutineScope()
    var cookieInput by remember { mutableStateOf("") }
    var connectError by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var showBrowserLogin by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(enabled = showBrowserLogin) {
        showBrowserLogin = false
    }

    if (showBrowserLogin && sessionState is SpotifySession.State.SignedOut) {
        SpotifyCookieLoginScreen(
            onConnected = { cookie ->
                showBrowserLogin = false
                connecting = true
                connectError = null
                SpotifySession.connectWithCookie(cookie) { ok, error ->
                    connecting = false
                    if (!ok) connectError = error
                }
            },
            modifier = modifier.padding(contentPadding),
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.spotify_logo),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(38.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.spotify),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        when (val state = sessionState) {
            is SpotifySession.State.SignedOut -> {
                item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Text(
                            text = stringResource(R.string.spotify_connect_subtitle),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Paste your sp_dc cookie: open.spotify.com in a browser, " +
                                "sign in, open devtools → Application → Cookies, and copy " +
                                "the sp_dc value.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = cookieInput,
                            onValueChange = {
                                cookieInput = it
                                connectError = null
                            },
                            label = { Text("sp_dc cookie") },
                            singleLine = true,
                            enabled = !connecting,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        connectError?.let { message ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                connecting = true
                                connectError = null
                                SpotifySession.connectWithCookie(cookieInput) { ok, error ->
                                    connecting = false
                                    if (!ok) connectError = error
                                }
                            },
                            enabled = !connecting && cookieInput.isNotBlank(),
                        ) {
                            Text(if (connecting) "Connecting…" else "Connect with cookie")
                        }
                        TextButton(
                            onClick = { showBrowserLogin = true },
                            enabled = !connecting,
                        ) {
                            Text("Log in with browser instead")
                        }
                    }
                }
            }
            is SpotifySession.State.Syncing -> {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            is SpotifySession.State.Failed -> {
                item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { SpotifySession.sync() }) {
                                Text("Retry sync")
                            }
                            TextButton(
                                onClick = {
                                    uiScope.launch { SpotifySession.signOut() }
                                },
                            ) {
                                Text("Disconnect")
                            }
                        }
                    }
                }
            }
            is SpotifySession.State.Synced -> {
                val snapshot = state.snapshot
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = if (lastSyncMs > 0) {
                                "Last synced " + DateFormat.getDateTimeInstance(
                                    DateFormat.SHORT, DateFormat.SHORT,
                                ).format(Date(lastSyncMs))
                            } else {
                                "Synced"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { SpotifySession.sync() }) {
                            Text("Sync now")
                        }
                        TextButton(
                            onClick = {
                                uiScope.launch { SpotifySession.signOut() }
                            },
                        ) {
                            Text("Disconnect")
                        }
                    }
                }
                if (lastSyncError.isNotBlank()) {
                    item {
                        Text(
                            text = "Last sync: $lastSyncError",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                }
                val error = homeError
                if (error != null) {
                    item {
                        Text(
                            text = "Home suggestions unavailable: $error\n" +
                                "Your synced playlists, Liked Songs and mixes still show on Home.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                }
                item {
                    val scrobbleOn by AppSettings.spotifyScrobbleEnabled.collectAsStateWithLifecycle()
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                        TextButton(onClick = {
                            AppSettings.setSpotifyScrobbleEnabled(!scrobbleOn)
                        }) {
                            Text(if (scrobbleOn) "Scrobbling to Spotify: on" else "Scrobbling to Spotify: off")
                        }
                        Text(
                            text = "Reports plays to Spotify history (feeds recommendations). " +
                                "Unofficial — never counts toward Wrapped.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (snapshot.playlists.isEmpty()) {
                    item {
                        Text(
                            text = "No playlists synced yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
                items(snapshot.playlists, key = { it.id }) { item ->
                    LibraryRow(
                        title = item.name,
                        subtitle = item.owner.orEmpty(),
                        imageUrl = item.imageUrl,
                        onClick = {
                            onOpenPlaylist(
                                SpotifyPlaylist(
                                    id = item.id,
                                    name = item.name,
                                    owner = item.owner,
                                    imageUrl = item.imageUrl,
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    imageUrl: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
