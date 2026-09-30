package com.tristinbaker.inkshelf.download

import com.tristinbaker.inkshelf.data.DownloadDao
import com.tristinbaker.inkshelf.data.DownloadEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Aggregate view of the download queue, keyed by library item. */
data class DownloadSummary(
    val itemId: String,
    val totalTracks: Int = 0,
    val doneTracks: Int = 0,
    val activeTracks: Int = 0,
    val runningTracks: Int = 0,
    val failedTracks: Int = 0,
    val bytesWritten: Long = 0,
    val bytesTotal: Long = 0,
    val error: String? = null,
) {
    val isComplete: Boolean get() = totalTracks > 0 && doneTracks == totalTracks

    /** Anything left to fetch, whether or not a service is currently moving bytes. */
    val isActive: Boolean get() = activeTracks > 0

    /** A track is being written right now, so the UI can show live progress. */
    val isRunning: Boolean get() = runningTracks > 0
    val hasFailure: Boolean get() = failedTracks > 0
    val progress: Float
        get() = if (bytesTotal > 0) (bytesWritten.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f

    /** Whole megabytes, so progress text stays readable on a 480px panel. */
    val megabytesWritten: Long get() = bytesWritten / (1024 * 1024)
    val megabytesTotal: Long get() = bytesTotal / (1024 * 1024)
}

sealed interface DownloadCommand {
    data class Enqueue(val itemId: String) : DownloadCommand
    data class Remove(val itemId: String) : DownloadCommand
    data class Retry(val itemId: String) : DownloadCommand
}

/**
 * UI seam for [DownloadService], mirroring [com.tristinbaker.inkshelf.playback.PlaybackCoordinator].
 */
class DownloadCoordinator(
    // Defaults keep unit tests, which have no database, able to construct this
    // directly and drive it through [set] instead.
    private val scope: CoroutineScope? = null,
    private val dao: (() -> DownloadDao)? = null,
    /**
     * Re-checks a row against what is really on disk and returns the corrected
     * row, or null to leave it alone. A row is only a record of what happened
     * once: a file deleted from a file manager, or app data cleared, leaves the
     * database claiming a download is complete when it is not. Reconciling here
     * rather than only on enqueue means the UI is honest the moment it loads.
     */
    private val reconcile: (suspend (DownloadEntity) -> DownloadEntity?)? = null,
) {
    init {
        // The service is only alive while something is downloading, so it cannot
        // be the only source of these summaries: without this the app forgets
        // every completed download on each launch and offers to download again.
        val source = dao
        val target = scope
        if (source != null && target != null) {
            val store = source()
            target.launch {
                store.observeAll().collect { rows ->
                    var changed = false
                    val corrected = rows.map { row ->
                        // With no service attached, nothing can be transferring, so
                        // a row still marked running was left behind by a killed
                        // process and is waiting to be picked back up.
                        val settled = if (!attached && row.state == DownloadEntity.STATE_RUNNING) {
                            row.copy(
                                state = DownloadEntity.STATE_QUEUED,
                                updatedAt = System.currentTimeMillis(),
                            )
                        } else {
                            row
                        }
                        val fixed = reconcile?.invoke(settled) ?: settled
                        if (fixed != row) {
                            changed = true
                            store.upsert(fixed)
                        }
                        fixed
                    }
                    _summaries.value = summarise(if (changed) corrected else rows)
                }
            }
        }
    }


    private val _commands = MutableSharedFlow<DownloadCommand>(
        replay = 0,
        extraBufferCapacity = 16,
    )
    val commands: SharedFlow<DownloadCommand> = _commands.asSharedFlow()

    private val _summaries = MutableStateFlow<Map<String, DownloadSummary>>(emptyMap())
    val summaries: StateFlow<Map<String, DownloadSummary>> = _summaries.asStateFlow()

    /**
     * The service may not exist yet, so hold at most one command and hand it over
     * in [attach] rather than dropping it on a non-replay flow.
     */
    @Volatile
    private var attached = false

    @Volatile
    private var pending: DownloadCommand? = null

    fun send(command: DownloadCommand) {
        if (attached) _commands.tryEmit(command) else pending = command
    }

    /** Called by the service once its collector is live. Returns the held command. */
    fun attach(): DownloadCommand? {
        attached = true
        val queued = pending
        pending = null
        return queued
    }

    fun detach() {
        attached = false
    }

    internal fun set(summaries: Map<String, DownloadSummary>) {
        _summaries.value = summaries
    }

    internal fun summarise(rows: List<DownloadEntity>): Map<String, DownloadSummary> =
        rows.groupBy { it.itemId }.mapValues { (_, group) ->
            DownloadSummary(
                itemId = group.first().itemId,
                totalTracks = group.size,
                doneTracks = group.count { it.state == DownloadEntity.STATE_DONE },
                activeTracks = group.count {
                    it.state == DownloadEntity.STATE_RUNNING || it.state == DownloadEntity.STATE_QUEUED
                },
                runningTracks = group.count { it.state == DownloadEntity.STATE_RUNNING },
                failedTracks = group.count { it.state == DownloadEntity.STATE_FAILED },
                bytesWritten = group.sumOf { it.bytesWritten },
                bytesTotal = group.sumOf { it.size },
                error = group.firstNotNullOfOrNull { it.error },
            )
        }
}
