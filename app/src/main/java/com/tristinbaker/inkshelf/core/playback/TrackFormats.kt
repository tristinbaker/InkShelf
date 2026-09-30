package com.tristinbaker.inkshelf.core.playback

/**
 * Decides whether Audiobookshelf can stream a track straight from disk, or
 * whether the server has to transcode it first.
 *
 * We derive the MIME type from the file extension rather than trusting the
 * server's `mimeType` field, because that field is unreliable in two ways
 * found in v2.37.0:
 *
 *  - `getAudioMimeTypeFromExtname` has no `WAV` entry, so a WAV file is served
 *    with whatever Content-Type Express guesses (frequently `audio/mpeg`).
 *    Feeding that to the player makes a PCM stream look like an MP3.
 *  - `Book.getTracklist` copies `mimeType` straight from the scan result, which
 *    for `.aac` files is `audio/aac` even when the payload is ADTS.
 *
 * The extension is the only field the server guarantees to reflect the actual
 * container, and the extension table below mirrors Audiobookshelf's own
 * `AudioMimeType` map (server/utils/constants.js) with WAV added.
 */
object TrackFormats {

    /** What the player should be told, and whether we can play it as-is. */
    data class Format(val mimeType: String, val directPlay: Boolean)

    private val BY_EXTENSION: Map<String, String> = mapOf(
        "mp3" to "audio/mpeg",
        "mpeg" to "audio/mpeg",
        "mpg" to "audio/mpeg",
        "m4b" to "audio/mp4",
        "m4a" to "audio/mp4",
        "mp4" to "audio/mp4",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/ogg",
        "aac" to "audio/aac",
        "flac" to "audio/flac",
        "mka" to "audio/x-matroska",
        "mkv" to "audio/x-matroska",
        // Not in Audiobookshelf's table, which is exactly why it is mislabelled.
        "wav" to "audio/wav",
        "wave" to "audio/wav",
        // Present on the server but unplayable by ExoPlayer 1.5.1.
        "wma" to "audio/x-ms-wma",
        "aiff" to "audio/x-aiff",
        "aif" to "audio/x-aiff",
        "aifc" to "audio/x-aiff",
        "awb" to "audio/amr-wb",
        "amr" to "audio/amr",
        "caf" to "audio/x-caf",
        "webm" to "audio/webm",
        "webma" to "audio/webm",
    )

    /**
     * ExoPlayer 1.5.1 ships extractors for mp3, mp4, ogg, wav, matroska, ts,
     * amr and flv, plus FLAC via the `media3-flac` extension we depend on.
     * Everything else has no extractor at all.
     */
    private val DIRECT_PLAYABLE: Set<String> = setOf(
        "audio/mpeg",
        "audio/mp4",
        "audio/ogg",
        "audio/aac",
        "audio/flac",
        "audio/wav",
        "audio/x-matroska",
    )

    /**
     * Fallback when a file has no usable extension. Derived from the ffprobe
     * codec name, which describes the payload rather than the container. Only
     * codecs that are unambiguous in practice are listed; `aac` is deliberately
     * absent because it could be either ADTS or MP4 and guessing wrong means
     * the extractor refuses the stream.
     */
    private val BY_CODEC: Map<String, String> = mapOf(
        "mp3" to "audio/mpeg",
        "mp2" to "audio/mpeg",
        "opus" to "audio/ogg",
        "vorbis" to "audio/ogg",
        "flac" to "audio/flac",
        "alac" to "audio/mp4",
        "pcm_s16le" to "audio/wav",
        "pcm_s24le" to "audio/wav",
        "pcm_s32le" to "audio/wav",
        "pcm_f32le" to "audio/wav",
        "pcm_u8" to "audio/wav",
        "pcm_s8" to "audio/wav",
    )

    fun resolve(extension: String, codec: String?): Format {
        val ext = extension.lowercase().trimStart('.')
        BY_EXTENSION[ext]?.let { return Format(it, it in DIRECT_PLAYABLE) }

        val name = codec?.lowercase()?.trim()
        BY_CODEC[name]?.let { return Format(it, true) }

        // Unknown to us. Assume the server is right; the player will report a
        // clear error if it is not.
        return Format("application/octet-stream", directPlay = false)
    }

    /** True when every track can be played without the server transcoding. */
    fun allDirectPlayable(tracks: List<Pair<String, String?>>): Boolean =
        tracks.all { (ext, codec) -> resolve(ext, codec).directPlay }

    /** The subset of tracks that would need transcoding, for UI messaging. */
    fun unsupported(tracks: List<Pair<String, String?>>): List<String> =
        tracks.mapNotNull { (ext, codec) ->
            if (resolve(ext, codec).directPlay) null else ext.ifBlank { codec ?: "unknown" }
        }.distinct()
}
