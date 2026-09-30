package com.tristinbaker.inkshelf.core.abs

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Audiobookshelf encodes item filters as `<group>.<base64(value)>`, decoded
 * server-side by `Buffer.from(decodeURIComponent(text), 'base64').toString()`.
 *
 * The value is an **id**, not a display name, for `authors` and `series`: the
 * server filters on `where: { id: filterValue }`. Other groups such as `tags`,
 * `genres`, `languages` and `publishers` match on their name.
 */
enum class FilterGroup(val wire: String, val matchesOn: FilterTarget) {
    AUTHORS("authors", FilterTarget.ID),
    SERIES("series", FilterTarget.ID),
    NARRATORS("narrators", FilterTarget.NAME),
    GENRES("genres", FilterTarget.NAME),
    TAGS("tags", FilterTarget.NAME),
    PUBLISHERS("publishers", FilterTarget.NAME),
    LANGUAGES("languages", FilterTarget.NAME),
    ;

    enum class FilterTarget { ID, NAME }
}

/**
 * Standard (not URL-safe) base64, matching the server's Node decode of
 * `Buffer.from(decodeURIComponent(text), 'base64').toString()`. Padding is kept
 * because that is what the encoder emits and Node decodes it either way.
 *
 * `java.util.Base64` rather than `android.util.Base64` so this round-trips under
 * plain JVM unit tests, which is why minSdk is 26.
 */
fun encodeFilterValue(value: String): String =
    Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

fun decodeFilterValue(value: String): String =
    String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)

fun buildFilter(group: FilterGroup, value: String): String =
    "${group.wire}.${encodeFilterValue(value)}"

/** The `series.no-series` and `missing.*` special forms carry an unencoded value. */
fun buildBareFilter(group: FilterGroup, value: String): String = "${group.wire}.$value"
