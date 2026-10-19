package com.music.bitchord.data.sources

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song

/**
 * YouTube Music catalog, wrapped so it can be ordered alongside everything else.
 *
 * Catalog-only: [search] answers from YouTube Music metadata, but this source
 * never serves audio — [stream] always misses and [capabilities] denies
 * playback. Playback of any row, including rows found here, resolves through
 * the enabled add-on/provider sources (see [SourceResolver]).
 *
 * Tracks keep their bare video ids rather than being wrapped in a
 * [SourceRegistry.trackKey]. Half the app knows what a YouTube video id is —
 * the like button, the lyrics lookup keyed on it, the radio endpoint, the
 * canvas lookup, the scrobbler — and re-keying them here would break every one
 * of those for no gain, since an unwrapped id already routes to exactly this
 * source by default.
 */
class YouTubeSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.YOUTUBE
    override val displayName: String get() = config.label.ifBlank { SourceKind.YOUTUBE.label }

    override val capabilities: ProviderCapabilities
        get() = ProviderCapabilities.ADDON_DEFAULT.copy(
            search = true,
            trackMetadata = true,
            artistInfo = true,
            albumInfo = true,
            playlistRetrieval = true,
            userLibrary = true,
            discovery = false,
            playback = false,
            authentication = true,
            localFileAccess = false,
        )

    /**
     * Always Ok.
     *
     * There is no cheap probe worth making here: the endpoints that would
     * answer are the same ones a search is about to hit anyway, and a failing
     * YouTube shows up as a failed resolve with a real reason attached rather
     * than as a red dot on a settings row.
     */
    override suspend fun health(): SourceHealth = SourceHealth.Ok()

    /**
     * [waitForAll] is moot: there is one endpoint here, and it is always waited
     * for. So is [request] — YouTube publishes one set of rows and the tier is
     * decided per stream, long after this. (There is no stream: see [stream].)
     */
    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> =
        YtMusicRepository.search(query, SearchFilter.SONGS)
            .getOrDefault(emptyList())
            .filterIsInstance<SearchResult.Track>()
            .map { it.song }
            .take(limit)

    /**
     * Always misses: YouTube Music audio resolution is disabled in this
     * build. A null here is a miss, not an error, and lets [SourceResolver]
     * move to the next source.
     */
    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? = null
}
