package com.tristinbaker.inkshelf.data

import com.tristinbaker.inkshelf.core.abs.Chapter
import com.tristinbaker.inkshelf.core.net.AbsJson

/**
 * Round-trips the server's chapter list through a single TEXT column on
 * `book_metadata`, so adding chapters support is a plain `ALTER TABLE` and the
 * book metadata stays a flat snapshot.
 *
 * Stored verbatim from the server so the next read returns exactly what the
 * server gave us, including any chapter ids or titles the UI might want later.
 * An empty list encodes as null so the absence of chapters is itself one
 * cacheable fact: "the server has none" is not retried on every offline open.
 */
internal object ChaptersCodec {
    fun encode(chapters: List<Chapter>): String? =
        if (chapters.isEmpty()) null
        else AbsJson.json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(Chapter.serializer()),
            chapters,
        )

    fun decode(json: String?): List<Chapter> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            AbsJson.json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(Chapter.serializer()),
                json,
            )
        }.getOrDefault(emptyList())
    }
}
