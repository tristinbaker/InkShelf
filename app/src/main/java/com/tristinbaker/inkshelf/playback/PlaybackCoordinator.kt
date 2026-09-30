package com.tristinbaker.inkshelf.playback

import com.tristinbaker.inkshelf.core.abs.Chapter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the player screen renders. Mirrors the parts of ExoPlayer we expose. */
data class PlayerState(
    val itemId: String? = null,
    val title: String = "",
    val author: String = "",
    val trackTitle: String = "",
    val trackIndex: Int = 0,
    val trackCount: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val error: String? = null,
    /** Extensions that will need server-side transcoding. Empty when all direct. */
    val needsTranscode: List<String> = emptyList(),
    val speed: Float = 1f,
    /** True when every file is being read from the device rather than streamed. */
    val offline: Boolean = false,
    /**
     * Chapter list as the server supplied it, kept here so the player screen
     * can label and the book screen can count without a second source. Empty
     * when the server had none; the boundary below still works in that case.
     */
    val chapters: List<Chapter> = emptyList(),
    /**
     * How many boundaries the skip buttons can move between, including the
     * synthetic five-minute markers built when the server supplied no chapters.
     * That distinction matters to the UI: it greys "Next" out against this
     * count rather than [chapters], which is empty for a book with synthetic
     * markers only.
     */
    val chapterCount: Int = 0,
    /**
     * Index into the boundary for the chapter the listener is currently in, or
     * -1 when there are no chapters. The service publishes this only when the
     * value changes, so the player screen does not recompose every tick.
     */
    val currentChapterIndex: Int = -1,
) {
    val hasBook: Boolean get() = itemId != null
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** Intent from the UI to the playback service. */
sealed interface PlayCommand {
    /**
     * @param startAtMs book-global position to start from, bypassing the saved
     *  bookmark. Used when the listener taps a chapter on the chapters screen:
     *  a separate SeekTo sent after Play would race the load, because the seek
     *  lands before the media items exist and is then overwritten by the
     *  bookmark restore.
     */
    data class Play(val itemId: String, val startAtMs: Long? = null) : PlayCommand

    /**
     * Opens a book at its bookmark without starting audio.
     *
     * Reopening after the app was closed is the case this exists for: the listener
     * should find their place on the player screen and start it themselves, rather
     * than have the book begin playing the moment the app appears.
     */
    data class Load(val itemId: String) : PlayCommand
    data object Toggle : PlayCommand
    data object Next : PlayCommand
    data object Previous : PlayCommand
    data object Stop : PlayCommand
    data class SeekTo(val positionMs: Long) : PlayCommand
    data class SeekBy(val deltaMs: Long) : PlayCommand
    data class SetSpeed(val speed: Float) : PlayCommand
}

/** Direction for chapter-level skips, shared by the service and its tests. */
enum class SkipDirection { Previous, Next }

/**
 * The seam between the UI and [PlaybackService].
 *
 * The service is a `MediaSessionService` so the platform notification, lock
 * screen controls and audio focus behave correctly, but custom session commands
 * are a poor fit for passing a library item id: Media3 only accepts a custom
 * command whose extras bundle matches the one registered on the session, so
 * arbitrary ids cannot travel that way. Both sides live in the same process, so
 * a shared flow is both simpler and impossible to desynchronise.
 */
class PlaybackCoordinator {

    private val _commands = MutableSharedFlow<PlayCommand>(
        replay = 0,
        extraBufferCapacity = 16,
    )
    val commands: SharedFlow<PlayCommand> = _commands.asSharedFlow()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /**
     * The service may not exist yet: `playBook` can fire before the process has
     * started it. A non-replay flow would drop that command, so hold at most one
     * and hand it over in [attach].
     */
    @Volatile
    private var attached = false

    @Volatile
    private var pending: PlayCommand? = null

    fun send(command: PlayCommand) {
        if (attached) _commands.tryEmit(command) else pending = command
    }

    /** Called by the service once its collector is live. Returns the held command. */
    fun attach(): PlayCommand? {
        attached = true
        val queued = pending
        pending = null
        return queued
    }

    fun detach() {
        attached = false
    }

    /** Only the service mutates this. */
    internal fun update(transform: (PlayerState) -> PlayerState) {
        _state.value = transform(_state.value)
    }
}
