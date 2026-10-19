package com.music.bitchord.data.scrobbling

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

object LastFM {
    const val DEFAULT_API_ENDPOINT = "https://ws.audioscrobbler.com/2.0/"
    const val LIBREFM_API_ENDPOINT = "https://libre.fm/2.0/"

    @Serializable
    data class Session(val name: String, val key: String, val subscriber: Int)

    @Serializable
    data class Authentication(val session: Session)

    @Serializable
    data class TokenResponse(val token: String)

    @Serializable
    data class LastFmError(val error: Int, val message: String)

    data class RuntimeConfig(
        val endpoint: String,
        val apiKey: String,
        val secret: String,
        val sessionKey: String?,
    )

    @Volatile
    private var runtimeConfig =
        RuntimeConfig(
            endpoint = DEFAULT_API_ENDPOINT,
            apiKey = "",
            secret = "",
            sessionKey = null,
        )

    var sessionKey: String?
        get() = runtimeConfig.sessionKey
        set(value) {
            runtimeConfig = runtimeConfig.copy(sessionKey = value)
        }

    private val json =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
        }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            expectSuccess = false
        }
    }

    private fun Map<String, String>.apiSig(secret: String): String = apiSignature(this, secret)

    private fun HttpRequestBuilder.lastfmParams(
        method: String,
        apiKey: String,
        secret: String,
        sessionKey: String? = null,
        extra: Map<String, String> = emptyMap(),
        format: String = "json",
    ) {
        headers.append(HttpHeaders.UserAgent, "BitChord (https://github.com/kushagrasinghx/BitChord)")
        val paramsForSig =
            mutableMapOf(
                "method" to method,
                "api_key" to apiKey,
            ).apply {
                sessionKey?.let { put("sk", it) }
                putAll(extra)
            }
        val apiSig = paramsForSig.apiSig(secret)

        setBody(
            FormDataContent(
                Parameters.build {
                    paramsForSig.forEach { (key, value) -> append(key, value) }
                    append("api_sig", apiSig)
                    append("format", format)
                },
            ),
        )
    }

    suspend fun getToken() =
        runCatching {
            postAndDecode<TokenResponse>(
                method = "auth.getToken",
            )
        }

    suspend fun getSession(token: String) =
        runCatching {
            postAndDecode<Authentication>(
                method = "auth.getSession",
                extra = mapOf("token" to token),
            )
        }

    fun getAuthUrl(token: String): String {
        val config = runtimeConfig
        return if (config.endpoint == LIBREFM_API_ENDPOINT) {
            "https://libre.fm/api/auth?api_key=${config.apiKey}&token=$token"
        } else {
            "https://www.last.fm/api/auth/?api_key=${config.apiKey}&token=$token"
        }
    }

    suspend fun getMobileSession(
        username: String,
        password: String,
    ) = runCatching {
        postAndDecode<Authentication>(
            method = "auth.getMobileSession",
            extra = mapOf("username" to username, "password" to password),
        )
    }

    class LastFmException(
        val code: Int,
        override val message: String,
    ) : Exception(message)

    suspend fun updateNowPlaying(
        artist: String,
        track: String,
        album: String? = null,
        trackNumber: Int? = null,
        duration: Int? = null,
    ) = runCatching {
        postAndRead(
            method = "track.updateNowPlaying",
            sessionKey = requireSessionKey(),
            extra =
                buildMap {
                    put("artist", artist)
                    put("track", track)
                    album?.let { put("album", it) }
                    trackNumber?.let { put("trackNumber", it.toString()) }
                    duration?.let { put("duration", it.toString()) }
                },
        )
    }

    suspend fun scrobble(
        artist: String,
        track: String,
        timestamp: Long,
        album: String? = null,
        trackNumber: Int? = null,
        duration: Int? = null,
    ) = runCatching {
        postAndRead(
            method = "track.scrobble",
            sessionKey = requireSessionKey(),
            extra =
                buildMap {
                    put("artist[0]", artist)
                    put("track[0]", track)
                    put("timestamp[0]", timestamp.toString())
                    album?.let { put("album[0]", it) }
                    trackNumber?.let { put("trackNumber[0]", it.toString()) }
                    duration?.let { put("duration[0]", it.toString()) }
                },
        )
    }

    fun initialize(
        apiKey: String,
        secret: String,
    ) {
        require(apiKey.isNotBlank() && secret.isNotBlank()) {
            "Last.fm API credentials are not configured for this build"
        }
        configure(
            endpoint = runtimeConfig.endpoint,
            apiKey = apiKey,
            secret = secret,
            sessionKey = runtimeConfig.sessionKey,
        )
    }

    fun configure(
        endpoint: String,
        apiKey: String,
        secret: String,
        sessionKey: String? = runtimeConfig.sessionKey,
    ) {
        runtimeConfig =
            RuntimeConfig(
                endpoint = normalizeEndpoint(endpoint),
                apiKey = apiKey,
                secret = secret,
                sessionKey = sessionKey,
            )
    }

    fun currentConfig(): RuntimeConfig = runtimeConfig

    fun isInitialized(): Boolean = runtimeConfig.apiKey.isNotEmpty() && runtimeConfig.secret.isNotEmpty()

    fun normalizeEndpoint(endpoint: String): String {
        val uri = URI(endpoint.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https")
        require(!uri.host.isNullOrBlank())
        require(uri.query == null && uri.fragment == null)

        val path =
            uri.path
                ?.takeIf { it.isNotBlank() && it != "/" }
                ?.trimEnd('/')
                ?: "/2.0"

        return URI(
            scheme,
            uri.userInfo,
            uri.host,
            uri.port,
            "$path/",
            null,
            null,
        ).toString()
    }

    private fun requireSessionKey(): String = runtimeConfig.sessionKey ?: throw LastFmException(9, "Session key missing")

    private suspend inline fun <reified T> postAndDecode(
        method: String,
        sessionKey: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): T =
        json.decodeFromString(
            postAndRead(
                method = method,
                sessionKey = sessionKey,
                extra = extra,
            ),
        )

    private suspend fun postAndRead(
        method: String,
        sessionKey: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): String {
        val config = runtimeConfig
        val response =
            client.post(config.endpoint) {
                lastfmParams(
                    method = method,
                    apiKey = config.apiKey,
                    secret = config.secret,
                    sessionKey = sessionKey,
                    extra = extra,
                )
            }

        val responseText = response.bodyAsText()
        // The body is read before the status is judged. Last.fm answers a
        // malformed request with a non-2xx *and* an error document, and it is
        // the document's code — not the HTTP status — that decides whether
        // trying again could ever help. See [LastFmScrobbler].
        responseText.asLastFmFailure()?.let { throw it }
        if (!response.status.isSuccess()) {
            throw LastFmException(response.status.value, response.status.description)
        }
        return responseText
    }

    /**
     * The failure a response body reports, or null when it reports a success.
     *
     * Read out of a parsed document rather than searched for as text. A track
     * whose title holds the word `error` is not a failed request, and matching
     * on the substring treated it as one: the scrobble was thrown away even
     * though the request had been accepted. Last.fm signals a real failure with
     * a top-level `error` member and nothing else.
     */
    private fun String.asLastFmFailure(): LastFmException? {
        val root =
            runCatching { json.parseToJsonElement(this) }.getOrNull() as? JsonObject
                ?: return null
        val code = (root["error"] as? JsonPrimitive)?.intOrNull ?: return null
        val message = (root["message"] as? JsonPrimitive)?.contentOrNull
        return LastFmException(code, message ?: "Last.fm error $code")
    }

    const val DEFAULT_SCROBBLE_DELAY_PERCENT = 0.5f
    const val DEFAULT_SCROBBLE_MIN_SONG_DURATION = 30

    /**
     * The cap on the half-the-track rule: four minutes, as the Scrobbling 2.0
     * documentation states it — "played for at least half its duration, or for
     * 4 minutes (whichever occurs earlier)".
     */
    const val DEFAULT_SCROBBLE_DELAY_SECONDS = 240
}

/**
 * The Last.fm `api_sig`.
 *
 * Every parameter sent, `format` and `callback` excepted, sorted by name,
 * concatenated as `name`+`value`, with the shared secret appended, then hashed
 * with MD5 and rendered as lowercase hex.
 *
 * Extracted from the request builder so it can be pinned against a known
 * vector. A signature that is wrong in any small way is rejected as "Invalid
 * method signature" on every single call, and the only symptom in the app is
 * that nothing is ever scrobbled — which is exactly the kind of failure nobody
 * reports.
 *
 * The byte encoding is stated rather than defaulted: the API requires UTF-8,
 * and a platform default that differs (as it does on a Windows JVM) would sign
 * non-ASCII metadata with different bytes than the ones that are sent.
 */
internal fun apiSignature(
    params: Map<String, String>,
    secret: String,
): String {
    val toHash = params.toSortedMap().entries.joinToString("") { it.key + it.value } + secret
    val digest = MessageDigest.getInstance("MD5").digest(toHash.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
}

/**
 * [ScrobbleRequest]s sent to Last.fm, with Last.fm's answer translated into
 * whether the attempt is worth repeating.
 *
 * The retry list is the API's own: error **11** ("Service Offline") and error
 * **16** ("The service is temporarily unavailable") are the two it asks a
 * client to retry, error **9** asks for a re-authentication first, and
 * everything else means the request was wrong and would be wrong again. A
 * request that never reached the service at all is retried too.
 */
internal object LastFmScrobbler : Scrobbler {
    /** Last.fm error 11 — "Service Offline - This service is temporarily offline, try again later." */
    private const val ERROR_SERVICE_OFFLINE = 11

    /** Last.fm error 16 — "The service is temporarily unavailable, please try again." */
    private const val ERROR_TEMPORARILY_UNAVAILABLE = 16

    /**
     * Above this the number can only be an HTTP status: Last.fm's own error
     * codes do not reach it. A 5xx is the server having a bad time, which is
     * worth another attempt.
     */
    private const val FIRST_HTTP_SERVER_ERROR = 500

    override suspend fun submit(request: ScrobbleRequest): Submission =
        when (request) {
            is ScrobbleRequest.NowPlaying ->
                LastFM
                    .updateNowPlaying(
                        artist = request.artist,
                        track = request.track,
                        album = request.album,
                        duration = request.durationSeconds.takeIf { it > 0 },
                    ).asSubmission()

            is ScrobbleRequest.Scrobble ->
                LastFM
                    .scrobble(
                        artist = request.artist,
                        track = request.track,
                        timestamp = request.timestampSeconds,
                        album = request.album,
                        duration = request.durationSeconds.takeIf { it > 0 },
                    ).asSubmission()
        }

    private fun Result<*>.asSubmission(): Submission =
        fold(
            onSuccess = { Submission.ACCEPTED },
            onFailure = { throwable ->
                when {
                    throwable !is LastFM.LastFmException -> Submission.RETRY
                    throwable.code == ERROR_SERVICE_OFFLINE -> Submission.RETRY
                    throwable.code == ERROR_TEMPORARILY_UNAVAILABLE -> Submission.RETRY
                    throwable.code >= FIRST_HTTP_SERVER_ERROR -> Submission.RETRY
                    else -> Submission.FAILED
                }
            },
        )
}
