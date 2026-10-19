package com.music.bitchord.ui

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher

/**
 * Merging the catalogs into one Home and one search list.
 *
 * YouTube Music contributes metadata (tracks, albums, artists, playlists);
 * provider sources contribute playable rows; Spotify contributes the signed-in
 * library. Neither catalog supplies audio — playback always resolves through
 * the enabled providers.
 *
 * Provenance is preserved structurally, not by merging identities:
 * - provider rows keep their `src:` track keys (self-describing),
 * - YTM rows keep bare video ids,
 * - Spotify shelves carry the "Spotify" subtitle and `spotify:playlist:` ids.
 * [searchOrigins] records the same per search row for callers that need it.
 */
object CatalogMerge {

    const val ORIGIN_YTM = "ytm"
    const val ORIGIN_PROVIDER = "provider"
    const val ORIGIN_SPOTIFY = "spotify"

    /** Origin of each search row by [rowKey], filled by [mergeSongRows]. */
    val searchOrigins = mutableMapOf<String, String>()

    fun rowKey(row: SearchResult): String = when (row) {
        is SearchResult.TopTrack -> "v:${row.song.videoId}"
        is SearchResult.Track -> "v:${row.song.videoId}"
        is SearchResult.Browse -> "b:${row.item.browseId}"
    }

    fun originOf(row: SearchResult): String =
        searchOrigins[rowKey(row)] ?: ORIGIN_YTM

    /**
     * One list from YTM rows and provider rows.
     *
     * Provider rows come first (they are directly playable). A YTM row that
     * is the *same recording* as a provider row is dropped — the provider
     * copy is what a tap would play anyway. Anything else is kept: dropping
     * a near-miss would hide a version the providers genuinely do not have.
     *
     * Same recording = normalized title and artist equal, both runtimes known
     * and within [SAME_RECORDING_SEC]. Unknown runtimes never merge: a live
     * cut and a studio cut share a title and an artist, and only a runtime
     * tells them apart.
     */
    fun mergeSongRows(
        ytm: List<SearchResult.Track>,
        provider: List<SearchResult.Track>,
    ): List<SearchResult> {
        val merged = ArrayList<SearchResult>(provider.size + ytm.size)
        merged += provider
        provider.forEach { rowKey(it).let { key -> searchOrigins[key] = ORIGIN_PROVIDER } }
        ytm.forEach { yRow ->
            val dup = provider.any { pRow -> sameRecording(pRow.song, yRow.song) }
            if (!dup) {
                merged += yRow
                searchOrigins[rowKey(yRow)] = ORIGIN_YTM
            }
        }
        return merged
    }

    fun sameRecording(a: Song, b: Song): Boolean {
        if (a.isVideo || b.isVideo) return false
        if (norm(a.title) != norm(b.title)) return false
        if (norm(a.artist) != norm(b.artist)) return false
        val da = TrackMatcher.secondsOf(a.durationText) ?: return false
        val db = TrackMatcher.secondsOf(b.durationText) ?: return false
        return kotlin.math.abs(da - db) <= SAME_RECORDING_SEC
    }

    /**
     * Spotify library shelves for the unified Home, from the last successful
     * sync. Empty when never synced or signed out. Taps route to the existing
     * Spotify detail pages via `spotify:playlist:` browse ids.
     */
    fun spotifyShelves(
        playlists: List<com.music.bitchord.data.spotify.SpotifyPlaylist>,
        maxPlaylists: Int = 20,
    ): List<HomeShelf> {
        if (playlists.isEmpty()) return emptyList()
        val liked = playlists.firstOrNull { it.id == com.music.bitchord.data.spotify.SpotifyLibrary.LIKED_ID }
        val rest = playlists.filterNot { it.id == com.music.bitchord.data.spotify.SpotifyLibrary.LIKED_ID }
            .take(maxPlaylists)
        val shelves = mutableListOf<HomeShelf>()
        if (liked != null) {
            shelves += HomeShelf(
                title = "Liked Songs",
                subtitle = "Spotify",
                items = listOf(
                    ShelfItem(
                        title = "Liked Songs",
                        subtitle = "Spotify library",
                        thumbnailUrl = liked.imageUrl,
                        videoId = null,
                        browseId = "spotify:playlist:${liked.id}",
                    ),
                ),
            )
        }
        // Split across shelves rather than one twenty-card row the eye skips.
        // These cost no request at all — the list is already synced — so they
        // are the backbone of what Home shows while Spotify's live side is
        // refusing or cooling down. [maxPlaylists] is twenty, so two shelves at
        // most and the two titles below never repeat.
        rest.chunked(PLAYLISTS_PER_SHELF).forEachIndexed { index, chunk ->
            shelves += HomeShelf(
                title = if (index == 0) "Your Spotify playlists" else "Spotify · More of your playlists",
                subtitle = "Spotify",
                items = chunk.map { playlist ->
                    ShelfItem(
                        title = playlist.name,
                        subtitle = playlist.owner.orEmpty(),
                        thumbnailUrl = playlist.imageUrl,
                        videoId = null,
                        browseId = "spotify:playlist:${playlist.id}",
                    )
                },
            )
        }
        return shelves
    }

    /**
     * The single card that guarantees a Spotify section on Home whatever else
     * happens: before setup it opens the connect screen, and after a sync that
     * produced nothing (no snapshot, every live call refused) it opens the same
     * library page, where the failure is spelled out. Without it an empty
     * Spotify section was indistinguishable from Spotify not being wired up at
     * all — the shelf simply was not there.
     */
    fun spotifyDoorShelves(connected: Boolean): List<HomeShelf> = listOf(
        HomeShelf(
            title = "Spotify",
            subtitle = "Spotify",
            items = listOf(
                ShelfItem(
                    title = if (connected) "Open Spotify" else "Connect Spotify",
                    subtitle = if (connected) {
                        "Your playlists, Liked Songs and mixes"
                    } else {
                        "Playlists, Liked Songs and mixes"
                    },
                    thumbnailUrl = null,
                    videoId = null,
                    browseId = com.music.bitchord.ui.screens.SPOTIFY_BROWSE_ID,
                ),
            ),
        ),
    )

    /**
     * A named shelf of Spotify playlist cards. Taps route to the existing
     * Spotify detail pages via `spotify:playlist:` browse ids, exactly like
     * library shelves — the page then matches each row to a provider source,
     * which is what makes a playlist card playable rather than decorative.
     *
     * [editorialShelves] is this with Spotify's own name on it; the Home feed
     * uses it again for each browse category it shows.
     */
    fun playlistShelves(
        title: String,
        playlists: List<com.music.bitchord.data.spotify.SpotifyPlaylist>,
    ): List<HomeShelf> {
        if (playlists.isEmpty()) return emptyList()
        return listOf(
            HomeShelf(
                title = title,
                subtitle = "Spotify",
                items = playlists.map { playlist ->
                    ShelfItem(
                        title = playlist.name,
                        subtitle = playlist.owner.orEmpty(),
                        thumbnailUrl = playlist.imageUrl,
                        videoId = null,
                        browseId = "spotify:playlist:${playlist.id}",
                    )
                },
            ),
        )
    }

    /**
     * Spotify editorial picks (Today's Top Hits & co.) as one Home shelf.
     * Taps route to the existing Spotify detail pages via
     * `spotify:playlist:` browse ids, exactly like library shelves.
     */
    fun editorialShelves(playlists: List<com.music.bitchord.data.spotify.SpotifyPlaylist>): List<HomeShelf> =
        playlistShelves("Spotify picks", playlists)

    /**
     * A shelf of **playable** track rows, already resolved to provider sources.
     *
     * Unlike [trackShelves], which hands out `sp:` rows that still have to be
     * matched — and cannot be played until they are, so on Home they sat under
     * a "matching" spinner forever — these carry the provider's own `src:` id
     * and a tap starts them immediately. Where a Spotify detail page ends up
     * once its matching finishes.
     */
    fun songShelves(title: String, songs: List<Song>, maxItems: Int = 20): List<HomeShelf> {
        if (songs.isEmpty()) return emptyList()
        return listOf(
            HomeShelf(
                title = title,
                subtitle = "Spotify",
                items = songs.take(maxItems).map { song ->
                    ShelfItem(
                        title = song.title,
                        subtitle = song.artist,
                        thumbnailUrl = song.thumbnailUrl,
                        videoId = song.videoId,
                        browseId = null,
                    )
                },
            ),
        )
    }

    /**
     * "Made for you" split, MetroFuse-style: the listener's own algorithmic
     * playlists (Daily Mix, Discover Weekly, Release Radar, …) lifted out of
     * the library list by Spotify ownership + title keywords. No extra
     * endpoint — it re-slices data already synced.
     */
    fun madeForYouShelves(
        playlists: List<com.music.bitchord.data.spotify.SpotifyPlaylist>,
    ): List<HomeShelf> {
        val mixes = playlists.filter { isMadeForYou(it) }
        if (mixes.isEmpty()) return emptyList()
        return listOf(
            HomeShelf(
                title = "Made for you",
                subtitle = "Spotify",
                items = mixes.map { playlist ->
                    ShelfItem(
                        title = playlist.name,
                        subtitle = playlist.owner.orEmpty(),
                        thumbnailUrl = playlist.imageUrl,
                        videoId = null,
                        browseId = "spotify:playlist:${playlist.id}",
                    )
                },
            ),
        )
    }

    private fun isMadeForYou(playlist: com.music.bitchord.data.spotify.SpotifyPlaylist): Boolean {
        if (!playlist.owner.orEmpty().contains("spotify", ignoreCase = true)) return false
        val title = playlist.name.lowercase()
        return listOf(
            "daily mix", "discover weekly", "release radar", "on repeat",
            "repeat rewind", "daylist", "blend", "radio", "mix",
        ).any { title.contains(it) }
    }

    /**
     * Spotify track rows (top songs, recently played) as one Home shelf.
     * Each row carries the Spotify id in a pending `sp:` video id: tapping
     * plays it by matching against the provider sources at tap time, exactly
     * like pending rows on Spotify detail pages. No provider search happens
     * while building the shelf — the shelf stays cheap.
     */
    fun trackShelves(
        title: String,
        tracks: List<com.music.bitchord.data.spotify.SpotifyTrack>,
        maxItems: Int = 20,
    ): List<HomeShelf> {
        if (tracks.isEmpty()) return emptyList()
        return listOf(
            HomeShelf(
                title = title,
                subtitle = "Spotify",
                items = tracks.take(maxItems).map { track ->
                    ShelfItem(
                        title = track.title,
                        subtitle = track.artist,
                        thumbnailUrl = track.imageUrl,
                        videoId = com.music.bitchord.data.model.SPOTIFY_PENDING_PREFIX + track.id,
                        browseId = null,
                    )
                },
            ),
        )
    }

    private fun norm(raw: String): String =
        raw.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }
            .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    private const val SAME_RECORDING_SEC = 2

    /** Cards per Spotify playlist shelf; see [spotifyShelves]. */
    private const val PLAYLISTS_PER_SHELF = 10
}
