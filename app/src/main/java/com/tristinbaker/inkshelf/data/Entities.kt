package com.tristinbaker.inkshelf.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tristinbaker.inkshelf.core.abs.ItemProgress
import com.tristinbaker.inkshelf.core.abs.LibraryItem
import com.tristinbaker.inkshelf.core.abs.Media
import com.tristinbaker.inkshelf.core.abs.Metadata
import com.tristinbaker.inkshelf.core.abs.SeriesRef

@Entity(tableName = "libraries")
data class LibraryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mediaType: String,
    val cachedAt: Long,
)

/**
 * Flattened snapshot of a minified library item.
 *
 * Minified rows carry no `audioFiles` and no `tracks` (those are expanded-only),
 * so everything needed for browsing has to be lifted out of `media.metadata`
 * here or it is lost on the way to disk.
 */
@Entity(
    tableName = "items",
    indices = [
        Index("libraryId"),
        Index("seriesId"),
        Index("authorName"),
        Index("title"),
    ],
)
data class ItemEntity(
    @PrimaryKey val id: String,
    val libraryId: String,
    val title: String,
    val authorName: String,
    val authorId: String?,
    val seriesName: String?,
    val seriesId: String?,
    val seriesSequence: String?,
    val narratorName: String?,
    val durationSeconds: Double,
    val coverPath: String?,
    val relPath: String?,
    val currentTime: Double,
    val isFinished: Boolean,
    val cachedAt: Long,
)

/**
 * One row per downloaded file.
 *
 * `bytesWritten` is the resume point. MediaStore cannot tell us how much of a
 * pending entry has landed after a crash, so we track it ourselves and issue a
 * `Range:` request from that offset. `uri` is the MediaStore row we own, which
 * is also what makes the file removable by the user from a normal file manager.
 */
@Entity(
    tableName = "downloads",
    indices = [Index("itemId"), Index("state")],
)
data class DownloadEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val libraryItemTitle: String,
    val trackIndex: Int,
    val fileName: String,
    val url: String,
    val mimeType: String,
    val size: Long,
    val bytesWritten: Long,
    val state: String,
    val uri: String?,
    val error: String?,
    /**
     * Seconds from the start of the book, as the server computed it. The player
     * needs this to turn a file position into a book position, and offline the
     * server is not there to be asked, so it has to be stored with the file.
     */
    val startOffsetSeconds: Double,
    val updatedAt: Long,
) {
    companion object {
        const val STATE_QUEUED = "queued"
        const val STATE_RUNNING = "running"
        const val STATE_DONE = "done"
        const val STATE_FAILED = "failed"
    }
}

/**
 * Everything the book screen renders, kept so a downloaded book can still
 * describe itself with no connection.
 *
 * Written when a download is queued, which is the one moment the app holds a
 * fully expanded item; the browse cache cannot supply this because minified
 * list rows carry no series, narrator or duration detail.
 */
@Entity(tableName = "book_metadata")
data class BookMetadataEntity(
    @PrimaryKey val itemId: String,
    val libraryId: String?,
    val title: String,
    val authorName: String?,
    val seriesName: String?,
    val seriesSequence: String?,
    val narratorName: String?,
    val durationSeconds: Double,
    val trackCount: Int,
    /**
     * Local bookmark. The server is the source of truth when reachable, but
     * holding a copy is what makes resume work on a plane.
     */
    val currentTime: Double,
    val isFinished: Boolean,
    val cachedAt: Long,
    /**
     * Server-provided chapter list as JSON, kept so a downloaded book can still
     * offer chapter navigation with no connection. Null when the server did not
     * supply chapters for the item, or the field has not been hydrated yet.
     *
     * Stored as text rather than a child table so adding the column is a single
     * `ALTER TABLE` and the book metadata stays a flat snapshot.
     */
    val chaptersJson: String? = null,
)

/**
 * Rebuilds the shape the UI already consumes, so an offline book needs no
 * separate screen or a second set of parameters threaded through the view model.
 */
fun BookMetadataEntity.toLibraryItem(): LibraryItem = LibraryItem(
    id = itemId,
    libraryId = libraryId,
    media = Media(
        metadata = Metadata(
            title = title,
            authorName = authorName,
            narratorName = narratorName,
            seriesName = seriesName,
            series = listOfNotNull(
                seriesName?.let { SeriesRef(id = "", name = it, sequence = seriesSequence) },
            ),
        ),
        duration = durationSeconds,
        numTracks = trackCount,
        chapters = ChaptersCodec.decode(chaptersJson),
    ),
    userMediaProgress = ItemProgress(
        currentTime = currentTime,
        isFinished = isFinished,
    ),
)

/**
 * Splits a series into a base name and a sequence.
 *
 * Audiobookshelf is inconsistent here: `series[].name` is "Cedar Cove" with
 * `sequence` "2", but `seriesName` on the same item is the pre-joined
 * "Cedar Cove #2". Storing the joined form and then appending the sequence
 * again renders "Cedar Cove #2 #2", so the two are kept apart here.
 */
internal fun splitSeries(metadata: Metadata): Pair<String?, String?> {
    metadata.series.firstOrNull()?.let { ref ->
        return ref.name to ref.sequence?.removePrefix("#")?.ifBlank { null }
    }
    val name = metadata.seriesName ?: return null to null
    val cut = name.lastIndexOf(" #")
    return if (cut > 0) {
        name.substring(0, cut) to name.substring(cut + 2).removePrefix("#").ifBlank { null }
    } else {
        name to null
    }
}

fun LibraryItem.toBookMetadata(currentTime: Double, isFinished: Boolean, cachedAt: Long): BookMetadataEntity {
    val (series, sequence) = splitSeries(media.metadata)
    return BookMetadataEntity(
        itemId = id,
        libraryId = libraryId,
        title = media.metadata.title.orEmpty().ifBlank { "Untitled" },
        authorName = media.metadata.authorName
            ?: media.metadata.authors.joinToString(", ") { it.name }.ifBlank { null },
        seriesName = series,
        seriesSequence = sequence,
        narratorName = media.metadata.narratorName,
        durationSeconds = media.duration ?: 0.0,
        trackCount = media.numTracks ?: media.tracks.size,
        chaptersJson = ChaptersCodec.encode(media.chapters),
        currentTime = currentTime,
        isFinished = isFinished,
        cachedAt = cachedAt,
    )
}

fun LibraryItem.toEntity(cachedAt: Long): ItemEntity {
    val metadata = media.metadata
    return ItemEntity(
        id = id,
        libraryId = libraryId.orEmpty(),
        title = metadata.title.orEmpty().ifBlank { "Untitled" },
        authorName = metadata.authorName
            ?: metadata.authors.joinToString(", ") { it.name },
        authorId = metadata.authors.firstOrNull()?.id,
        seriesName = metadata.seriesName ?: metadata.series.firstOrNull()?.name,
        seriesId = metadata.series.firstOrNull()?.id,
        seriesSequence = metadata.series.firstOrNull()?.sequence,
        narratorName = metadata.narratorName,
        durationSeconds = media.duration ?: 0.0,
        coverPath = null,
        relPath = path,
        // List responses never carry progress: the server only attaches
        // `userMediaProgress` to a single-item request with `include=progress`.
        // These stay at their defaults here and are filled in by the detail call.
        currentTime = userMediaProgress?.currentTime ?: 0.0,
        isFinished = userMediaProgress?.isFinished ?: false,
        cachedAt = cachedAt,
    )
}
