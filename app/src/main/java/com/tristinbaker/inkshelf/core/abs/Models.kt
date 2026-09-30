package com.tristinbaker.inkshelf.core.abs

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Field names follow Audiobookshelf v2.37.0 exactly.
 *
 * Note the identifier split: a library item's top-level `id` is the
 * libraryItemId used by `/api/items/:id/...`, while `media.id` is the book id.
 * Minified list rows carry no `audioFiles` and no `tracks`; those only appear on
 * the expanded item.
 */
@Serializable
data class StatusResponse(
    val authMethods: List<String>? = null,
    val serverVersion: String? = null,
) {
    /** If `local` is absent the server only accepts SSO, so a password form cannot work. */
    val supportsLocalLogin: Boolean get() = authMethods?.contains("local") ?: true
}

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class UserTokens(
    val id: String? = null,
    val username: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
)

@Serializable
data class LoginResponse(
    val user: UserTokens = UserTokens(),
)

@Serializable
data class Library(
    val id: String,
    val name: String,
    val mediaType: String = "book",
)

@Serializable
data class LibrariesResponse(
    val libraries: List<Library> = emptyList(),
)

@Serializable
data class AuthorRef(
    val id: String,
    val name: String,
)

@Serializable
data class SeriesRef(
    val id: String,
    val name: String,
    val sequence: String? = null,
)

/**
 * A single entry or a list of them.
 *
 * Audiobookshelf collapses these to a bare object when there is exactly one, and
 * only uses an array when there are several. A book in one series therefore sends
 * `"series": {"id":..,"name":..}` while a book in two sends an array, and a plain
 * `List<SeriesRef>` throws on the first shape: it made the whole items response
 * fail to parse, taking the book list and every drill-down with it.
 */
class SingleOrList<T>(private val element: KSerializer<T>) :
    KSerializer<List<T>> {

    override val descriptor: SerialDescriptor = SerialDescriptor("SingleOrList", element.descriptor)

    override fun deserialize(decoder: Decoder): List<T> {
        val input = decoder as? JsonDecoder
            ?: return decoder.decodeSerializableValue(ListSerializer(element))
        val json = input.json
        return when (val node = json.decodeFromJsonElement(JsonElement.serializer(), input.decodeJsonElement())) {
            is JsonArray -> node.map { json.decodeFromJsonElement(element, it) }
            is JsonObject -> listOf(json.decodeFromJsonElement(element, node))
            else -> emptyList()
        }
    }

    override fun serialize(encoder: Encoder, value: List<T>) {
        ListSerializer(element).serialize(encoder, value)
    }
}

@Serializable
data class Metadata(
    val title: String? = null,
    val authorName: String? = null,
    val narratorName: String? = null,
    val seriesName: String? = null,
    val publishedYear: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    @Serializable(with = SingleOrList::class)
    val authors: List<AuthorRef> = emptyList(),
    @Serializable(with = SingleOrList::class)
    val series: List<SeriesRef> = emptyList(),
) {
}

@Serializable
data class Media(
    val id: String? = null,
    val metadata: Metadata = Metadata(),
    val duration: Double? = null,
    val numTracks: Int? = null,
    val numAudioFiles: Int? = null,
    val size: Long? = null,
    /**
     * Only present on the expanded item. The server builds these itself in
     * `Book.getTracklist()`, which is why `contentUrl` and `startOffset` can be
     * trusted: `startOffset` is the running sum of the preceding durations, so
     * it is already a position within the whole book.
     */
    val tracks: List<AudioTrack> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
)

@Serializable
data class TrackMetadata(
    val filename: String? = null,
    val ext: String? = null,
    val path: String? = null,
    val relPath: String? = null,
    val size: Long? = null,
)

@Serializable
data class AudioTrack(
    val index: Int = 0,
    /** Inode string. Also the final path segment of [contentUrl]. */
    val ino: String = "",
    val title: String? = null,
    /** Seconds from the start of the book. */
    val startOffset: Double = 0.0,
    val duration: Double = 0.0,
    val codec: String? = null,
    /** Unreliable. Audiobookshelf omits WAV and can mislabel containers. */
    val mimeType: String? = null,
    val bitRate: Int? = null,
    val channels: Int? = null,
    val format: String? = null,
    val metadata: TrackMetadata? = null,
    val contentUrl: String? = null,
) {
    val fileName: String get() = metadata?.filename ?: title ?: "Track ${index + 1}"
    val extension: String get() = metadata?.ext?.lowercase()?.trimStart('.') ?: ""
}

@Serializable
data class Chapter(
    val id: Int = 0,
    val start: Double = 0.0,
    val end: Double = 0.0,
    val title: String? = null,
)

/**
 * Body for `PATCH /api/me/progress/:libraryItemId`.
 *
 * `markAsFinishedTimeRemaining` is not optional in practice. The server
 * defaults it to 10 seconds and auto-marks anything within that window as
 * finished, which would silently finish books whenever someone seeks near the
 * end. Sending 0 disables that heuristic; the client decides when a book is
 * genuinely finished.
 */
@Serializable
data class ProgressUpdate(
    val currentTime: Double,
    val duration: Double? = null,
    val progress: Double? = null,
    val isFinished: Boolean = false,
    val markAsFinishedTimeRemaining: Double = 0.0,
)

/**
 * The listen progress for one item, from the server's `userMediaProgress`.
 *
 * Note the name: `GET /api/items/:id?expanded=1&include=progress` does not return
 * this as `progress`. `LibraryItemController.findOne` attaches it as
 * `userMediaProgress`, and the non-expanded `toOldJSON()` omits it entirely.
 */
@Serializable
data class ItemProgress(
    val currentTime: Double? = null,
    val duration: Double? = null,
    val progress: Double? = null,
    val isFinished: Boolean? = null,
    val hideFromContinueListening: Boolean? = null,
    val lastUpdate: Long? = null,
)

@Serializable
data class LibraryItem(
    val id: String,
    val libraryId: String? = null,
    val path: String? = null,
    val isMissing: Boolean = false,
    val mediaType: String = "book",
    val media: Media = Media(),
    /** Only present when requested with `include=progress` on an expanded item. */
    val userMediaProgress: ItemProgress? = null,
)

/**
 * The list envelope. Audiobookshelf returns `{authors: [...]}` for
 * `GET /api/libraries/:id/authors` unless *both* `limit` and `page` are supplied
 * as numbers, in which case it returns `{results, total, limit, page}`. We always
 * send both so one shape covers every list endpoint, but we tolerate either.
 */
@Serializable
data class ListEnvelope<T>(
    val results: List<T>? = null,
    val authors: List<T>? = null,
    val total: Int? = null,
    val limit: Int? = null,
    val page: Int? = null,
) {
    val items: List<T> get() = results ?: authors ?: emptyList()

    /** Total row count across all pages, when the endpoint was paginated. */
    val totalCount: Int get() = total ?: items.size
}

@Serializable
data class AuthorEntry(
    val id: String,
    val name: String,
    @SerialName("lastFirst") val lastFirst: String? = null,
    val numBooks: Int? = null,
    /**
     * The server's own article-stripped sort key: "Andy Weir" sorts as
     * "Weir, Andy", "The Expanse" as "Expanse, The". Preferred over doing it
     * locally so ordering matches the server's own lists.
     */
    val nameIgnorePrefix: String? = null,
)

@Serializable
data class SeriesEntry(
    val id: String,
    val name: String,
    val description: String? = null,
    /**
     * Only present when the query sorts by it, so the book count is otherwise
     * taken from the length of [books].
     */
    val numBooks: Int? = null,
    val nameIgnorePrefix: String? = null,
    val totalDuration: Double? = null,
    /**
     * Present on every series entry, one minified library item per book. Only the
     * length is ever read; the request sorts by `numBooks` so the cheap field is
     * normally there and this is just the fallback.
     */
    val books: List<JsonElement>? = null,
) {
    val bookCount: Int get() = numBooks ?: books?.size ?: 0
}
