package com.music.bitchord.download

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.innertube.PlayerClient
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.sources.StreamFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * Pulls a resolved provider stream onto disk.
 *
 * Provider URLs are fetched whole (one GET with the source's own headers —
 * see [SourceStream.headers][com.music.bitchord.data.sources.SourceStream.headers]),
 * unlike the old ranged googlevideo walk, which was removed with YouTube
 * Music support. There is no re-resolve on refusal: a source URL that stops
 * working fails the download with the server's own status.
 */
object Downloader {

    private const val TAG = "BitChord"

    /** Matches the range size read-ahead settled on; large enough to amortise, small enough to cancel promptly. */
    private const val CHUNK_BYTES = 2L * 1024 * 1024

    private const val BUFFER_BYTES = 64 * 1024

    /**
     * Fetch all of [url] into [sink], for a stream that isn't googlevideo's.
     *
     * Deliberately not a parameterised [fetch], on two counts that both matter:
     *
     *  - **One GET, not ranges.** The chunk loop above exists to defeat
     *    googlevideo's pacing, and nothing else paces bytes that way. Worse, a
     *    server that ignores `Range` answers 200 with the whole file rather
     *    than 206 with the slice asked for — and that loop would then write the
     *    opening of the file into the middle of the output and commit something
     *    the right length and unplayable.
     *  - **The source's own headers.** A source hands them over alongside the
     *    URL — see [SourceStream.headers][com.music.bitchord.data.sources.SourceStream.headers]
     *    — and they are whatever that server binds its links to. The
     *    [PlayerClient] identity [fetch] recovers from a googlevideo URL means
     *    nothing here.
     *
     * There is no re-resolve on a refusal. A source's URL that stops working
     * has a server behind it with its own reasons; the failure surfaces with
     * the server's own status rather than a silent retry.
     *
     * @param onProgress called as bytes land, and only when the response stated
     *   a length to measure against — an unstated one leaves the progress
     *   indeterminate rather than dividing by zero.
     * @return how many bytes were written.
     */
    suspend fun fetchDirect(
        url: String,
        headers: Map<String, String>,
        sink: OutputStream,
        onProgress: (written: Long, total: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()

        Http.client.newCall(request).execute().use { response ->
            if (response.code !in 200..299) error("Download failed (HTTP ${response.code})")
            val body = response.body ?: error("Download failed: empty response")
            val total = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
            val source = body.byteStream()
            val buffer = ByteArray(BUFFER_BYTES)
            var written = 0L

            while (true) {
                coroutineContext.ensureActive()
                val read = source.read(buffer)
                if (read == -1) break
                sink.write(buffer, 0, read)
                written += read
                if (total != null) onProgress(written, total)
            }
            sink.flush()

            // A stated length that didn't all arrive is the one failure the
            // pending-row dance cannot catch on its own: nothing threw, so
            // committing would publish a file that looks whole and stops
            // halfway through the song.
            if (total != null && written < total) {
                error("Download stopped at ${written}B of $total — try again")
            }
            if (written == 0L) error("Download failed: nothing was sent")
            Log.d(TAG, "fetched ${written}B directly")
            written
        }
    }

    /**
     * Verify that a source URL is a file in the container it advertised before
     * it is allowed to become a download route.
     *
     * Some module backends label an HLS master playlist as FLAC. Downloading
     * that text faithfully produces a tiny `.flac` which no player can open;
     * rejecting it here lets the ordinary YouTube fallback take over instead.
     */
    suspend fun isDirectAudioFile(
        url: String,
        headers: Map<String, String>,
        format: StreamFormat,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url)
                .header("Range", "bytes=0-31")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            Http.client.newCall(request).execute().use { response ->
                if (response.code !in 200..299) return@use false
                val type = response.header("Content-Type").orEmpty().lowercase()
                if ("mpegurl" in type || "vnd.apple.mpegurl" in type) return@use false
                val head = response.body?.byteStream()?.readNBytes(32) ?: return@use false
                if (head.startsWith("#EXTM3U".toByteArray())) return@use false
                when (format.codec?.lowercase()) {
                    "flac", "x-flac" -> head.startsWith("fLaC".toByteArray())
                    "m4a", "mp4", "alac" -> head.size >= 8 &&
                        head.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())
                    else -> head.isNotEmpty()
                }
            }
        }.onFailure { Log.w(TAG, "could not probe source file: ${it.message}") }.getOrDefault(false)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun open(url: String, position: Long, length: Long) = Http.client
        .newCall(
            Request.Builder()
                .url(url)
                .header("Range", "bytes=$position-${position + length - 1}")
                .apply {
                    StreamResolver.mediaHeadersFor(url)
                        .forEach { (name, value) -> header(name, value) }
                }
                .build(),
        )
        .execute()

    /**
     * How many bytes the whole track is.
     *
     * Every progressive googlevideo URL carries it as `clen`, which costs no
     * request at all. The `bytes=0-0` probe behind it is for the URLs that
     * don't — the extraction failsafe can produce one — and reads the total out
     * of the `Content-Range` header of a one-byte response.
     */
    private fun contentLength(url: String): Long? {
        url.toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?.let { return it }

        return runCatching {
            open(url, 0, 1).use { response ->
                response.header("Content-Range")
                    ?.substringAfter('/', "")
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
            }
        }.onFailure { Log.w(TAG, "could not measure the track: ${it.message}") }.getOrNull()
    }

    private val REFUSAL_CODES = setOf(403, 404, 410)
}
