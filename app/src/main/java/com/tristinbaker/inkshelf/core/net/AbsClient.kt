package com.tristinbaker.inkshelf.core.net

import android.util.Log
import com.tristinbaker.inkshelf.core.abs.AuthorEntry
import com.tristinbaker.inkshelf.core.abs.AuthorSort
import com.tristinbaker.inkshelf.core.abs.LibrariesResponse
import com.tristinbaker.inkshelf.core.abs.Library
import com.tristinbaker.inkshelf.core.abs.LibraryItem
import com.tristinbaker.inkshelf.core.abs.ListEnvelope
import com.tristinbaker.inkshelf.core.abs.LoginRequest
import com.tristinbaker.inkshelf.core.abs.LoginResponse
import com.tristinbaker.inkshelf.core.abs.ProgressUpdate
import com.tristinbaker.inkshelf.core.abs.SeriesEntry
import com.tristinbaker.inkshelf.core.abs.SeriesSort
import com.tristinbaker.inkshelf.core.abs.StatusResponse
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val EMPTY_JSON = "".toRequestBody(JSON_MEDIA_TYPE)

private data class RawResponse(val code: Int, val body: String)

/**
 * Audiobookshelf REST client.
 *
 * Written against v2.37.0 source rather than the public docs, which are stale.
 * What matters here:
 *  - access tokens last 2h and refresh tokens 30d, and are returned as JSON
 *    only when `X-Return-Tokens: true` is sent. Without it the refresh token is
 *    an httpOnly cookie that a native client discards.
 *  - auth is rate limited to 40 attempts per 10 minutes per IP and *successful*
 *    attempts count, so refresh is single-flight and retried at most once.
 */
class AbsClient(
    private val authStore: AuthStore,
    private val pinner: TlsPinner,
) {
    private val refreshLock = Mutex()
    private var cachedClient: OkHttpClient? = null
    private var cachedUrl: ServerUrl? = null

    private fun httpClient(url: ServerUrl): OkHttpClient {
        cachedClient?.let { if (cachedUrl == url) return it }
        val built = OkHttpClient.Builder()
            .applyPinning(url, pinner)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        cachedClient = built
        cachedUrl = url
        return built
    }

    // ---- auth -------------------------------------------------------------

    suspend fun status(url: ServerUrl): ApiResult<StatusResponse> =
        call(url, path = "/status", authorized = false, serializer = StatusResponse.serializer())

    suspend fun login(
        url: ServerUrl,
        username: String,
        password: String,
    ): ApiResult<Session> {
        val body = AbsJson.json.encodeToString(
            LoginRequest.serializer(),
            LoginRequest(username, password),
        )
        val raw = execute(
            url = url,
            method = "POST",
            path = "/login",
            body = body,
            headers = mapOf("X-Return-Tokens" to "true"),
            authorized = false,
        )
        return when (raw) {
            is ApiResult.Failure -> raw
            is ApiResult.Ok -> {
                val decoded = decode(raw.value, LoginResponse.serializer())
                when (decoded) {
                    is ApiResult.Failure -> decoded
                    is ApiResult.Ok -> {
                        val tokens = decoded.value.user
                        if (tokens.accessToken == null && tokens.refreshToken == null) {
                            ApiResult.Failure(
                                "Server returned no tokens. If it is SSO-only, " +
                                    "password login is unavailable.",
                                ApiResult.Failure.Kind.Unauthorized,
                            )
                        } else {
                            val session = Session(
                                serverUrl = url.canonical,
                                username = tokens.username ?: username,
                                accessToken = tokens.accessToken,
                                refreshToken = tokens.refreshToken,
                            )
                            authStore.save(session)
                            ApiResult.Ok(session)
                        }
                    }
                }
            }
        }
    }

    /**
     * Single-flight token refresh. Returns false when the refresh token is dead,
     * which is the signal to drop the session and show the login screen. Never
     * retried automatically, because the server counts successes against a
     * 40-per-10-minute IP limit.
     */
    suspend fun refresh(url: ServerUrl): Boolean = refreshLock.withLock {
        val refreshToken = authStore.session.value?.refreshToken ?: return false
        val raw = execute(
            url = url,
            method = "POST",
            path = "/auth/refresh",
            headers = mapOf("X-Refresh-Token" to refreshToken),
            authorized = false,
        )
        if (raw is ApiResult.Failure) return false

        val decoded = decode((raw as ApiResult.Ok).value, LoginResponse.serializer())
        if (decoded is ApiResult.Failure) return false

        val tokens = (decoded as ApiResult.Ok).value.user
        if (tokens.accessToken == null) return false
        authStore.updateTokens(tokens.accessToken, tokens.refreshToken)
        true
    }

    fun signOut() = authStore.clear()

    // ---- libraries --------------------------------------------------------

    suspend fun libraries(url: ServerUrl): ApiResult<List<Library>> =
        call(url, path = "/api/libraries", serializer = LibrariesResponse.serializer())
            .mapSuccess { it.libraries }

    suspend fun items(
        url: ServerUrl,
        libraryId: String,
        sort: String,
        descending: Boolean,
        filter: String? = null,
        limit: Int = PAGE_SIZE,
        page: Int = 0,
    ): ApiResult<ListEnvelope<LibraryItem>> {
        val query = buildList {
            add("limit" to limit.toString())
            add("page" to page.toString())
            add("sort" to sort)
            add("desc" to descFlag(descending))
            add("minified" to "1")
            filter?.let { add("filter" to it) }
        }
        return call(
            url = url,
            path = "/api/libraries/$libraryId/items",
            query = query,
            serializer = ListEnvelope.serializer(LibraryItem.serializer()),
        )
    }

    suspend fun authors(
        url: ServerUrl,
        libraryId: String,
        descending: Boolean,
        limit: Int = PAGE_SIZE,
        page: Int = 0,
    ): ApiResult<ListEnvelope<AuthorEntry>> {
        val query = buildList {
            add("limit" to limit.toString())
            add("page" to page.toString())
            // Sorting by numBooks is what makes the server include the count
            // field, exactly as it does for series. Sorting by lastFirst omitted
            // it, so every author came back with numBooks absent and a missing
            // count was indistinguishable from an author with no books. The list
            // is re-sorted by surname locally regardless, so nothing is lost.
            add("sort" to AuthorSort.NUM_BOOKS)
            add("desc" to descFlag(descending))
        }
        return call(
            url = url,
            path = "/api/libraries/$libraryId/authors",
            query = query,
            serializer = ListEnvelope.serializer(AuthorEntry.serializer()),
        )
    }

    /**
     * Every series in the library, paging until the server says it is done.
     *
     * The server's own series list is the authoritative one: it counts a book
     * against each series it belongs to, so a book in two series is listed under
     * both, and it is not capped by how many books happen to fit in one page of
     * items. Deriving series from item rows loses both of those.
     */
    suspend fun allSeries(
        url: ServerUrl,
        libraryId: String,
        descending: Boolean = false,
    ): ApiResult<List<SeriesEntry>> = paged(
        fetch = { page -> series(url, libraryId, descending, PAGE_SIZE, page) },
    )

    suspend fun allAuthors(
        url: ServerUrl,
        libraryId: String,
        descending: Boolean = false,
    ): ApiResult<List<AuthorEntry>> = paged(
        fetch = { page -> authors(url, libraryId, descending, PAGE_SIZE, page) },
    )

    /**
     * Walks pages until the collected count reaches the reported total, or a page
     * comes back empty, or the page size stops advancing. Guards against a
     * server that never advances `page`, which would otherwise loop forever.
     */
    private suspend fun <T> paged(
        fetch: suspend (Int) -> ApiResult<ListEnvelope<T>>,
    ): ApiResult<List<T>> {
        val collected = mutableListOf<T>()
        var page = 0
        var total: Int
        while (true) {
            when (val result = fetch(page)) {
                is ApiResult.Failure -> return ApiResult.Failure(result.message)
                is ApiResult.Ok -> {
                    val envelope = result.value
                    val pageItems = envelope.items
                    if (pageItems.isEmpty()) break
                    val before = collected.size
                    collected += pageItems
                    total = envelope.total ?: collected.size
                    if (collected.size >= total || collected.size == before) break
                }
            }
            page++
        }
        return ApiResult.Ok(collected)
    }

    suspend fun series(
        url: ServerUrl,
        libraryId: String,
        descending: Boolean,
        limit: Int = PAGE_SIZE,
        page: Int = 0,
    ): ApiResult<ListEnvelope<SeriesEntry>> {
        val query = buildList {
            add("limit" to limit.toString())
            add("page" to page.toString())
            // Sorting by numBooks is what makes the server include the count
            // field; the list is re-sorted by name locally either way.
            add("sort" to "numBooks")
            add("desc" to descFlag(descending))
        }
        return call(
            url = url,
            path = "/api/libraries/$libraryId/series",
            query = query,
            serializer = ListEnvelope.serializer(SeriesEntry.serializer()),
        )
    }

    // ---- playback ---------------------------------------------------------

    /**
     * The expanded item, which is the only response carrying `media.tracks`.
     * List rows are minified and contain no file information at all, so
     * playback cannot be started without this call.
     */
    suspend fun itemDetail(url: ServerUrl, itemId: String): ApiResult<LibraryItem> =
        call(
            url = url,
            path = "/api/items/$itemId",
            query = listOf("expanded" to "1", "include" to "progress"),
            serializer = LibraryItem.serializer(),
        )

    /**
     * `PATCH /api/me/progress/:libraryItemId`.
     *
     * Progress writes are fire-and-forget from the player's point of view: a
     * failure is logged and retried on the next tick rather than surfaced,
     * because interrupting audio to report a bookmark is worse than losing one.
     */
    suspend fun updateProgress(
        url: ServerUrl,
        itemId: String,
        update: ProgressUpdate,
    ): ApiResult<Unit> {
        val raw = execute(
            url = url,
            method = "PATCH",
            path = "/api/me/progress/$itemId",
            body = AbsJson.json.encodeToString(ProgressUpdate.serializer(), update),
            authorized = true,
        )
        return when (raw) {
            is ApiResult.Failure -> raw
            // 200 with an empty body; the endpoint uses sendStatus(200).
            is ApiResult.Ok -> ApiResult.Ok(Unit)
        }
    }

    /**
     * Cover bytes for a library item, resized server-side to [width] px.
     *
     * Audiobookshelf whitelists `/api/items/:id/cover` out of authentication for
     * GET (`Auth.ignorePatterns`), so no token is attached; the endpoint 400s if
     * a cover is missing, which is a normal outcome here and not an error.
     */
    suspend fun cover(libraryItemId: String, width: Int): ByteArray? {
        val raw = authStore.read()?.serverUrl ?: return null
        val url = (ServerUrl.parse(raw) as? ServerUrl.Result.Valid)?.url ?: return null
        val target = url.resolveWithQuery(
            "/api/items/$libraryItemId/cover",
            listOf("width" to width.toString(), "format" to "jpeg"),
        )
        return withContext(Dispatchers.IO) {
            runCatching {
                httpClient(url).newCall(Request.Builder().url(target).get().build()).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.bytes()
                }
            }.getOrNull()
        }
    }

    /**
     * The pinned client, shared with ExoPlayer and the downloader so that
     * trust-on-first-use certificates apply to audio bytes too and not just to
     * JSON.
     */
    fun okHttpClient(url: ServerUrl): OkHttpClient = httpClient(url)

    // ---- plumbing ---------------------------------------------------------

    /**
     * Authorised GET that transparently refreshes the access token exactly once
     * on a 401/403 before giving up and asking the user to sign in again.
     */
    private suspend fun <T> call(
        url: ServerUrl,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        authorized: Boolean = true,
        serializer: KSerializer<T>,
    ): ApiResult<T> {
        val first = execute(url, "GET", path, query = query, authorized = authorized)
        when (first) {
            is ApiResult.Ok -> return decode(first.value, serializer)
            is ApiResult.Failure ->
                if (first.kind != ApiResult.Failure.Kind.Unauthorized) return first
        }

        if (!refresh(url)) {
            return ApiResult.Failure(
                "Session expired, please sign in again",
                ApiResult.Failure.Kind.Unauthorized,
            )
        }
        val second = execute(url, "GET", path, query = query, authorized = true)
        return when (second) {
            is ApiResult.Ok -> decode(second.value, serializer)
            is ApiResult.Failure -> second
        }
    }

    private fun <T> decode(raw: RawResponse, serializer: KSerializer<T>): ApiResult<T> = try {
        ApiResult.Ok(AbsJson.json.decodeFromString(serializer, raw.body))
    } catch (t: Throwable) {
        ApiResult.Failure("Unexpected response from server: ${t.message}")
    }

    private suspend fun execute(
        url: ServerUrl,
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        authorized: Boolean,
    ): ApiResult<RawResponse> = withContext(Dispatchers.IO) {
        val target = if (query.isEmpty()) {
            url.resolve(path)
        } else {
            url.resolve(path) + "?" + query.joinToString("&") { (k, v) ->
                // OkHttp's HttpUrl would encode these, but we assemble the string
                // ourselves for predictable ordering, so encode by hand.
                "$k=${percentEncode(v)}"
            }
        }

        val builder = Request.Builder().url(target)
        // OkHttp refuses a null body on POST/PUT/PATCH and refuses a body on
        // GET/HEAD. `/auth/refresh` is a POST whose payload is the X-Refresh-Token
        // header, so it legitimately has none, and passing null there threw and
        // took the whole app down the first time an access token expired.
        val permitsBody = method != "GET" && method != "HEAD"
        val requestBody = body?.toRequestBody(JSON_MEDIA_TYPE)
            ?: if (permitsBody) EMPTY_JSON else null
        builder.method(method, requestBody)
        if (authorized) {
            authStore.session.value?.accessToken?.let {
                builder.header("Authorization", "Bearer $it")
            }
        }
        headers.forEach { (k, v) -> builder.header(k, v) }

        try {
            httpClient(url).newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    ApiResult.Ok(RawResponse(response.code, text))
                } else {
                    ApiResult.Failure(describe(response.code, text), kindFor(response.code))
                }
            }
        } catch (e: IOException) {
            ApiResult.Failure(
                "Cannot reach ${url.hostPort}. Check the address and your connection.",
                ApiResult.Failure.Kind.Network,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never let an unexpected throwable from the HTTP stack reach the
            // caller. A crash here loses the screen the user was on, and every
            // failure mode we can describe is more useful than a stack trace.
            Log.w(TAG, "unexpected failure for ${url.canonical}$path", e)
            ApiResult.Failure(
                "Request to ${url.hostPort} failed: ${e.javaClass.simpleName}: " +
                    (e.message ?: "no message"),
                ApiResult.Failure.Kind.Network,
            )
        }
    }

    private fun kindFor(code: Int): ApiResult.Failure.Kind = when {
        code == 401 || code == 403 -> ApiResult.Failure.Kind.Unauthorized
        code == 429 -> ApiResult.Failure.Kind.RateLimited
        code >= 500 -> ApiResult.Failure.Kind.Server
        code >= 400 -> ApiResult.Failure.Kind.Client
        else -> ApiResult.Failure.Kind.Unknown
    }

    private fun describe(code: Int, body: String): String {
        val serverMessage = runCatching {
            val element = AbsJson.json.parseToJsonElement(body)
            (element as? kotlinx.serialization.json.JsonObject)
                ?.get("error")
                ?.toString()
                ?.trim('"')
        }.getOrNull()

        return when (code) {
            401 -> "Incorrect username or password"
            429 -> "Too many attempts. Audiobookshelf locks out an IP for 10 minutes " +
                "after 40 auth requests, and successful logins count."
            else -> serverMessage ?: "Server returned HTTP $code"
        }
    }

    private fun <T, R> ApiResult<T>.mapSuccess(transform: (T) -> R): ApiResult<R> = when (this) {
        is ApiResult.Ok -> ApiResult.Ok(transform(value))
        is ApiResult.Failure -> this
    }

    companion object {
        const val PAGE_SIZE = 200
        private const val TAG = "AbsClient"
        private fun descFlag(descending: Boolean) = if (descending) "1" else "0"
    }
}
