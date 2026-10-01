package com.tristinbaker.inkshelf.playback

import android.app.PendingIntent
import android.content.Intent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.core.app.ServiceCompat
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.tristinbaker.inkshelf.MainActivity
import com.tristinbaker.inkshelf.R
import com.tristinbaker.inkshelf.ServiceLocator
import com.google.common.collect.ImmutableList
import com.tristinbaker.inkshelf.core.abs.AudioTrack
import com.tristinbaker.inkshelf.core.abs.ProgressUpdate
import com.tristinbaker.inkshelf.core.net.ApiResult
import com.tristinbaker.inkshelf.core.net.ServerUrl
import com.tristinbaker.inkshelf.core.playback.TrackFormats
import com.tristinbaker.inkshelf.data.BookMetadataEntity
import com.tristinbaker.inkshelf.data.ChaptersCodec
import com.tristinbaker.inkshelf.data.DownloadEntity
import com.tristinbaker.inkshelf.data.toBookMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays books straight off the Audiobookshelf server.
 *
 * A book is a directory of audio files, and the expanded item already carries a
 * prepared track list: each entry has a `contentUrl` for
 * `GET /api/items/:id/file/:ino` and a `startOffset` equal to the running sum of
 * the preceding durations. Playing the files in order therefore reproduces the
 * book's timeline exactly, so no playback session and no transcoding negotiation
 * are needed in the common case.
 *
 * Each file is a separate media item rather than a period of one merged source.
 * A merged source only learns a track's duration by reading it, so until every
 * file has been opened its timeline is incomplete: `contentDuration` reports the
 * first file alone and seeking past it lands back at the start. Discrete items
 * give reliable track boundaries, and the book-level position, duration and
 * progress are computed from the server's own offsets.
 */
// Media3's builders and source types are all marked unstable, and there is no
// stable alternative for a custom DataSource or a merged multi-file source.
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var locator: ServiceLocator
    private lateinit var player: ExoPlayer
    private lateinit var dataSourceFactory: DataSource.Factory
    private var mediaSession: MediaSession? = null
    private var reporter: Job? = null

    private var currentItemId: String? = null
    private var currentUrl: ServerUrl? = null

    /** Whether the service is currently holding a foreground notification. */
    private var inForeground = false

    /**
     * Title and transport label behind the notification currently posted.
     *
     * The reporter publishes once a second and [syncNotification] runs on every
     * publish, so without these the notification would be rebuilt and redrawn
     * sixty times a minute for a book that is not changing.
     */
    private var notifiedTitle: String? = null
    private var notifiedTransport: String? = null

    /**
     * Start of each track in milliseconds from the start of the book, taken from
     * the server's `startOffset` rather than accumulated locally. The player only
     * learns a track's length once it has read that file, so the book timeline
     * cannot come from the player and is driven from these instead.
     */
    private var trackStarts: List<Long> = emptyList()
    private var trackNames: List<String> = emptyList()

    /**
     * Sorted chapter starts for the current book, in book-global milliseconds.
     * Empty when the book has no chapters; the skip buttons still work because
     * [ChapterBoundaryBuilder] fills in synthetic intervals from the duration
     * rather than leaving the player stuck.
     */
    private var chapterBoundary: ChapterBoundary = ChapterBoundary(LongArray(0))

    /**
     * Last [PlayerState.currentChapterIndex] value published to the UI. The
     * reporter loop ticks every second but the chapter only changes at a
     * boundary, so this lets [publish] skip a recomposing state update on every
     * tick and only publish when the listener crosses into a new chapter.
     */
    private var lastPublishedChapterIndex: Int = -1

    /** Cached chapter list shown to the UI. Empty for books without chapters. */
    private var publishedChapters: List<com.tristinbaker.inkshelf.core.abs.Chapter> = emptyList()

    /**
     * Whole-book length from `media.duration`.
     *
     * The player cannot supply this: a merged source only learns a track's
     * duration once it has read that track, so `contentDuration` reports just the
     * first file for an unread book. The server already knows the total and the
     * offset of every track, so the book timeline is derived from those instead.
     */
    private var bookDurationMs: Long = 0L
    private var lastProgressAt: Long = 0L

    /** Set once the server becomes unreachable, so the warning is not per-tick. */
    private val warnedOffline = java.util.concurrent.atomic.AtomicBoolean(false)
    private var loadJob: Job? = null

    /**
     * The MP4 extractor holds the whole sample table on the Java heap, about 24
     * bytes per AAC frame, so a 60-hour single-file m4b needs some 230 MB before
     * a sample plays. That is why the manifest asks for a large heap. Edit lists
     * are ignored because applying one copies every table, doubling that peak,
     * and all it buys an audiobook is trimming a few dozen milliseconds of
     * encoder priming.
     */
    private fun extractorsFactory() = DefaultExtractorsFactory()
        .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)

    override fun onCreate() {
        super.onCreate()
        locator = ServiceLocator.get(this)
        dataSourceFactory = buildDataSourceFactory()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                // Take audio focus so a phone call or another player can
                // interrupt us the way the user expects.
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory()))
            .build()

        player.addListener(playerListener)

        ensureNotificationChannel()

        // Set before the session exists: Media3 rejects a provider change once any
        // session has been added.
        setMediaNotificationProvider(mediaNotificationProvider)

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openAppIntent())
            .build()

        scope.launch {
            locator.playback.commands.collect { command -> dispatch(command) }
        }
    }

    private suspend fun dispatch(command: PlayCommand) {
        when (command) {
            is PlayCommand.Play -> loadBook(command.itemId, command.startAtMs)
            // Opens a book without starting audio. The launch path uses this so a
            // listener who swiped the app away mid-book comes back to their place
            // on the player screen rather than having the book start talking at
            // them the moment the app opens.
            is PlayCommand.Load -> loadBook(command.itemId, autoPlay = false)
            PlayCommand.Toggle -> if (player.isPlaying) player.pause() else player.play()
            // Prev/Next skip chapters, not files. A single-file m4b makes a
            // track skip a dead button, and the split of a multi-file book is
            // an implementation detail the listener never navigates by. The
            // ±30s buttons next to these cover fine scrubbing.
            PlayCommand.Next -> handleSkipChapter(SkipDirection.Next)
            PlayCommand.Previous -> handleSkipChapter(SkipDirection.Previous)
            PlayCommand.Stop -> stopPlayback()
            // Content coordinates: the UI thinks in whole-book time, and
            // `seekTo` is period-relative.
            is PlayCommand.SeekTo -> seekInBook(command.positionMs)
            is PlayCommand.SeekBy -> seekInBook(
                (bookPositionMs() + command.deltaMs).coerceAtLeast(0L),
            )

            is PlayCommand.SetSpeed -> player.playbackParameters =
                PlaybackParameters(command.speed)
        }
    }

    /**
     * Jumps to the previous or next chapter boundary using the cached
     * [chapterBoundary]. The seek itself is a normal content seek, so the
     * service's own progress flush covers the new position the same way a
     * scrub would.
     */
    private fun handleSkipChapter(direction: SkipDirection) {
        val position = bookPositionMs()
        val index = when (direction) {
            SkipDirection.Previous -> chapterBoundary.previousIndex(position)
            SkipDirection.Next -> chapterBoundary.nextIndex(position)
        }
        if (index < 0) return
        seekInBook(chapterBoundary.startMs(index))
    }

    /**
     * Injects the bearer token per request instead of baking it into a header
     * map. Access tokens last two hours and a long book outlives that, so
     * reading the live token per request means a seek after a silent refresh
     * still authenticates rather than failing with a 401.
     */
    private fun buildDataSourceFactory(): DataSource.Factory {
        val base = urlOrNull()
        val upstream = OkHttpDataSource.Factory(
            base?.let { locator.api.okHttpClient(it) }
                ?: okhttp3.OkHttpClient(),
        )
        val resolving = ResolvingDataSource.Factory(upstream) { dataSpec ->
            val token = locator.authStore.session.value?.accessToken
            if (token.isNullOrEmpty()) dataSpec
            else dataSpec.withAdditionalHeaders(mapOf("Authorization" to "Bearer $token"))
        }
        return DefaultDataSource.Factory(this, resolving)
    }

    private fun urlOrNull(): ServerUrl? =
        (ServerUrl.parse(locator.authStore.session.value?.serverUrl.orEmpty()) as? ServerUrl.Result.Valid)
            ?.url

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Closing the app means stopping the audio. Pressing HOME does not reach
        // here, which is what separates the two: the notification keeps the
        // service alive in the background, while swiping the task away ends it.
        //
        // The last-played item is deliberately left in place so reopening lands on
        // that book's player screen, paused rather than playing.
        stopPlayback(clearLastPlayed = false)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * `onCreate` launched the collector on `Dispatchers.Main.immediate`, so it is
     * already subscribed by the time the main thread reaches here. Any command
     * the UI sent before the process started the service is delivered now.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The notification's transport buttons come back here as service intents.
        // The service is already running by the time anyone can see them, so this
        // is a plain delivery rather than a new foreground start.
        when (intent?.action) {
            ACTION_PREVIOUS -> locator.playback.send(PlayCommand.Previous)
            ACTION_TOGGLE -> locator.playback.send(PlayCommand.Toggle)
            ACTION_NEXT -> locator.playback.send(PlayCommand.Next)
        }
        locator.playback.attach()?.let { queued ->
            Log.i(TAG, "picking up command held before start: $queued")
            scope.launch { dispatch(queued) }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        demoteForeground()
        locator.playback.detach()
        reporter?.cancel()
        flushProgress(finished = false)
        mediaSession?.release()
        mediaSession = null
        player.removeListener(playerListener)
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    // ---- loading ----------------------------------------------------------

    private suspend fun loadBook(
        itemId: String,
        startAtMs: Long? = null,
        autoPlay: Boolean = true,
    ) {
        // Recorded before the fetch so a book that fails to load still counts as
        // the one being opened, matching what the screen shows the listener.
        locator.settings.lastPlayedItemId = itemId
        val cached = locator.database.bookMetadataDao().get(itemId)
        val downloaded = locator.database.downloadDao().doneForItem(itemId)

        // A finished download means the whole book is on this device, so it beats
        // streaming: it starts instantly, costs no data, and is the only option
        // with no connection. The server is still consulted below, but only to pick
        // up a fresher bookmark, never to decide whether playback is possible.
        val fullyDownloaded = cached != null &&
            downloaded.isNotEmpty() &&
            downloaded.size == cached.trackCount

        val parsed = ServerUrl.parse(locator.authStore.session.value?.serverUrl.orEmpty())
        val url = (parsed as? ServerUrl.Result.Valid)?.url
        val detail = url?.let { server ->
            when (val result = locator.api.itemDetail(server, itemId)) {
                is ApiResult.Failure -> {
                    Log.w(TAG, "item detail failed for $itemId: ${result.message}")
                    null
                }

                is ApiResult.Ok -> result.value
            }
        }

        if (detail != null) {
            // Keep the offline description current whenever the server is there.
            locator.database.bookMetadataDao().upsert(
                detail.toBookMetadata(
                    currentTime = maxOf(
                        cached?.currentTime ?: 0.0,
                        detail.userMediaProgress?.currentTime ?: 0.0,
                    ),
                    isFinished = detail.userMediaProgress?.isFinished
                        ?: (cached?.isFinished ?: false),
                    cachedAt = System.currentTimeMillis(),
                ),
            )
        }

        if (fullyDownloaded && cached != null) {
            val fresh = locator.database.bookMetadataDao().get(itemId) ?: cached
            loadFromDisk(
                itemId = itemId,
                meta = fresh,
                tracks = downloaded,
                serverUrl = url,
                autoPlay = autoPlay,
                startTime = startAtMs?.let { it / 1000.0 }
                    ?: detail?.userMediaProgress?.currentTime
                        ?.takeIf { it > 0.0 }
                    ?: fresh.currentTime,
            )
            return
        }

        if (url == null) {
            locator.playback.update {
                it.copy(error = "No connection, and this book is not downloaded")
            }
            return
        }

        val streamed = detail ?: run {
            locator.playback.update {
                it.copy(error = "Could not reach the server to load this book")
            }
            return
        }

        val tracks = streamed.media.tracks
        if (tracks.isEmpty()) {
            locator.playback.update {
                it.copy(error = "This item has no audio tracks on the server")
            }
            return
        }

        val items = buildItems(url, tracks)
        if (items.isEmpty()) {
            locator.playback.update {
                it.copy(error = "The server did not return any playable files")
            }
            return
        }

        // One job per request: tapping a second book while the first is still
        // loading must not leave two sources racing for the player.
        loadJob?.cancel()
        loadJob = scope.launch {
            player.setMediaItems(items)
            player.prepare()

            val startMs = startAtMs
                ?: ((streamed.userMediaProgress?.currentTime ?: 0.0) * 1000).toLong()
            if (startMs > 0) seekInBook(startMs)
            if (autoPlay) player.play()

            currentItemId = itemId
            currentUrl = url
            lastProgressAt = 0L
            bookDurationMs = (streamed.media.duration ?: 0.0).toLong() * 1000
            loadChapterBoundary(cached, bookDurationMs)

            locator.playback.update {
                it.copy(
                    itemId = itemId,
                    title = streamed.media.metadata.title.orEmpty(),
                    author = streamed.media.metadata.authorName.orEmpty(),
                    trackCount = tracks.size,
                    durationMs = bookDurationMs,
                    error = null,
                    needsTranscode = TrackFormats.unsupported(
                        tracks.map { t -> t.extension to t.codec },
                    ),
                    chapters = publishedChapters,
                    chapterCount = chapterBoundary.starts.size,
                )
            }
            startReporting()
        }
    }

    /**
     * Plays a book entirely from files already under `Music/InkShelf`.
     *
     * Everything the streaming path takes from the server is in the database
     * instead: the track list from the download rows, the book timeline from the
     * offsets stored with them, the length and description from the cached
     * metadata, and the bookmark from whichever copy is further along.
     */
    private fun loadFromDisk(
        itemId: String,
        meta: BookMetadataEntity,
        tracks: List<DownloadEntity>,
        serverUrl: ServerUrl?,
        startTime: Double,
        autoPlay: Boolean,
    ) {
        val items = tracks.mapNotNull { row ->
            val uri = row.uri ?: return@mapNotNull null
            val part = MediaItem.Builder()
                .setUri(Uri.parse(uri))
                .setMediaId(row.id)
                .setMimeType(row.mimeType)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(row.fileName)
                        .setTrackNumber(row.trackIndex + 1)
                        .build(),
                )
                .build()
            part
        }
        if (items.isEmpty()) {
            locator.playback.update {
                it.copy(error = "The downloaded files could not be opened")
            }
            return
        }

        trackStarts = tracks.map { (it.startOffsetSeconds * 1000).toLong() }
        trackNames = tracks.map { it.fileName }
        bookDurationMs = (meta.durationSeconds * 1000).toLong()

        // No server means nowhere to write a bookmark to, but the service still
        // records one locally below.
        currentUrl = serverUrl
        currentItemId = itemId
        lastProgressAt = 0L

        loadJob?.cancel()
        loadJob = scope.launch {
            player.setMediaItems(items)
            player.prepare()
            val startMs = (startTime * 1000).toLong()
            if (startMs > 0) seekInBook(startMs)
            // A restore leaves the book cued at its bookmark instead of
            // starting it; the transport controls start it when asked.
            if (autoPlay) player.play()
            syncNotification()

            loadChapterBoundary(meta, bookDurationMs)
            locator.playback.update {
                it.copy(
                    itemId = itemId,
                    title = meta.title,
                    author = meta.authorName.orEmpty(),
                    trackCount = tracks.size,
                    durationMs = bookDurationMs,
                    error = null,
                    // The files were vetted for direct play when they were queued.
                    needsTranscode = emptyList(),
                    offline = true,
                    chapters = publishedChapters,
                    chapterCount = chapterBoundary.starts.size,
                )
            }
            startReporting()
        }
    }

    /** One media item per file, in track order. */
    private fun buildItems(url: ServerUrl, tracks: List<AudioTrack>): List<MediaItem> {
        val parts = mutableListOf<MediaItem>()
        val starts = mutableListOf<Long>()
        val names = mutableListOf<String>()
        for (track in tracks) {
            val path = track.contentUrl
            if (path.isNullOrBlank()) {
                Log.w(TAG, "track ${track.index} has no contentUrl, skipping")
                continue
            }
            starts += (track.startOffset * 1000).toLong()
            names += track.fileName
            val format = TrackFormats.resolve(track.extension, track.codec)
            val item = MediaItem.Builder()
                .setUri(url.resolve(path))
                .setMediaId(track.ino)
                .setMimeType(format.mimeType)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.fileName)
                        .setTrackNumber(track.index + 1)
                        .build(),
                )
                .build()
            parts += item
        }
        trackStarts = starts
        trackNames = names
        return parts
    }

    /** Position in the whole book, derived from the current track's offset. */
    private fun bookPositionMs(): Long {
        val start = trackStarts.getOrNull(currentTrackIndex()) ?: 0L
        return start + player.currentPosition.coerceAtLeast(0L)
    }

    /** Moves within the whole book, in milliseconds from its start. */
    private fun seekInBook(contentMs: Long) {
        val target = if (bookDurationMs > 0) {
            contentMs.coerceIn(0L, bookDurationMs)
        } else {
            contentMs.coerceAtLeast(0L)
        }
        val index = trackStarts.indexOfLast { it <= target }.coerceAtLeast(0)
        val start = trackStarts.getOrNull(index) ?: 0L
        player.seekTo(index, target - start)
    }

    private fun currentTrackIndex(): Int = player.currentMediaItemIndex.coerceAtLeast(0)

    private fun stopPlayback(flush: Boolean = true, clearLastPlayed: Boolean = true) {
        if (flush) flushProgress(finished = false)
        demoteForeground()
        reporter?.cancel()
        reporter = null
        currentItemId = null
        currentUrl = null
        trackStarts = emptyList()
        trackNames = emptyList()
        bookDurationMs = 0L
        chapterBoundary = ChapterBoundary(LongArray(0))
        lastPublishedChapterIndex = -1
        publishedChapters = emptyList()
        player.stop()
        player.clearMediaItems()
        // Covers both an explicit Stop and the book finishing: neither should
        // come back up on the player's previous occupant. Closing the app is the
        // one exception, where reopening should still land on this book.
        if (clearLastPlayed) locator.settings.lastPlayedItemId = null
        locator.playback.update { PlayerState() }
    }

    // ---- notification and foreground service -------------------------------

    /**
     * Creates the low-importance channel the playback notification lives on.
     *
     * Low, so starting a book does not make a sound. The notification is a
     * control surface, not an alert.
     */
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.playback_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.playback_channel_name)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Media3 is told about a session but never asked for a notification on this
     * path: `createNotification` is not called even though the provider is
     * installed, and nothing reaches the shade. That leaves the service with no
     * notification and therefore no reason to be foreground, so audio dies with
     * the process the moment the app is backgrounded.
     *
     * This builds the notification instead. It is the same one handed back to
     * Media3, so whichever of the two paths asks for it first, the result is a
     * single notification under a single id.
     */
    private fun buildNotification(): Notification {
        val state = locator.playback.state.value
        // The platform style, not NotificationCompat.MediaStyle: androidx.core
        // dropped that class, and minSdk is 29 so the platform one is always there.
        val style = Notification.MediaStyle()
            .setMediaSession(mediaSession?.platformToken)
            .setShowActionsInCompactView(0, 1, 2)

        // Built with the platform builder rather than NotificationCompat: the
        // compat style hierarchy no longer accepts Notification.MediaStyle, and
        // minSdk is 29, so there is nothing left for the compat layer to bridge.
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.title.ifBlank { getString(R.string.app_name) })
            .setContentText(state.author.ifBlank { null })
            .setContentIntent(openAppIntent())
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(state.playing)
            .setStyle(style)
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_prev,
                    "Prev",
                    servicePendingIntent(ACTION_PREVIOUS),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    if (state.playing) R.drawable.ic_pause else R.drawable.ic_play,
                    if (state.playing) "Pause" else "Play",
                    servicePendingIntent(ACTION_TOGGLE),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_next,
                    "Next",
                    servicePendingIntent(ACTION_NEXT),
                ).build(),
            )
            .build()
    }

    /**
     * Keeps the notification, and with it the foreground state, in step with the
     * book.
     *
     * Called from every publish, which ticks once a second, so the title and
     * transport label are compared against what was last posted and the work is
     * skipped unless one of them moved. The notification is a static image of the
     * transport state: only those two things about it change.
     */
    private fun syncNotification() {
        val state = locator.playback.state.value
        if (!state.hasBook) {
            // Nothing loaded: stop being foreground so the process is not held
            // resident by a book that is no longer playing.
            demoteForeground()
            return
        }

        val playLabel = if (state.playing) "Pause" else "Play"
        if (state.title == notifiedTitle && playLabel == notifiedTransport) return
        notifiedTitle = state.title
        notifiedTransport = playLabel

        val notification = buildNotification()
        if (inForeground) {
            // startForeground also refreshes an already posted notification, so
            // one call covers both cases.
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            return
        }

        // Not in the foreground yet. Try to enter it, and fall back to a plain
        // notification if the platform refuses, which keeps playback working and
        // costs only background survival.
        val promoted = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        }.onFailure { Log.w(TAG, "could not enter the foreground", it) }
            .isSuccess

        inForeground = promoted
        if (!promoted) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Leaves the foreground and takes the notification down with it.
     *
     * Runs when playback ends rather than when it is paused: a paused book still
     * has a notification worth keeping, because the listener may be reaching for
     * it to resume.
     */
    private fun demoteForeground() {
        notifiedTitle = null
        notifiedTransport = null
        if (!inForeground) return
        inForeground = false
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }.onFailure { Log.w(TAG, "could not leave the foreground", it) }
    }

    private fun servicePendingIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    // ---- progress ---------------------------------------------------------

    private fun startReporting() {
        reporter?.cancel()
        reporter = scope.launch {
            while (isActive) {
                delay(UI_TICK_MS)
                // ExoPlayer only calls back on discrete events, so nothing moves
                // the clock in the UI unless we tick it ourselves.
                if (currentItemId != null) publish()
                if (!isActive) break
                if (elapsedSinceProgress() >= PROGRESS_INTERVAL_MS && player.isPlaying) {
                    reportProgress()
                }
            }
        }
    }

    private fun elapsedSinceProgress(): Long =
        if (lastProgressAt == 0L) PROGRESS_INTERVAL_MS
        else System.currentTimeMillis() - lastProgressAt

    private fun reportProgress() {
        val itemId = currentItemId ?: return
        val position = bookPositionMs()
        val duration = bookDurationMs
        if (position < 0 || duration <= 0) return

        // The caller throttles this to every [PROGRESS_INTERVAL_MS]. The server
        // stores absolute positions, and every call competes with a rate limit,
        // so there is nothing to gain from finer granularity.
        lastProgressAt = System.currentTimeMillis()

        val currentTime = position / 1000.0
        val total = duration / 1000.0
        val update = ProgressUpdate(
            currentTime = currentTime,
            duration = total,
            progress = (currentTime / total).coerceIn(0.0, 1.0),
            isFinished = total - currentTime <= FINISH_WINDOW_SECONDS,
        )
        val url = currentUrl
        scope.launch(Dispatchers.IO) {
            // Recorded first and unconditionally: a bookmark that only reaches the
            // server is a bookmark the device forgets the moment it goes offline.
            locator.database.bookMetadataDao().updateProgress(
                itemId = itemId,
                currentTime = update.currentTime,
                isFinished = update.isFinished,
            )
            if (url == null) return@launch
            val result = locator.api.updateProgress(url, itemId, update)
            if (result is ApiResult.Failure) {
                // Being offline is a normal state for a downloaded book, not a
                // fault worth a line every ten seconds. Warn once, then stay
                // quiet until something actually changes.
                if (warnedOffline.compareAndSet(false, true)) {
                    Log.i(TAG, "offline: bookmark kept locally, not sent to the server")
                }
            }
        }
    }

    /**
     * Detached scope on purpose: this runs from [onDestroy], where the service's
     * own scope is already cancelled, and a dropped bookmark is worse than a
     * stray request.
     */
    private fun flushProgress(finished: Boolean) {
        val itemId = currentItemId ?: return
        val position = bookPositionMs()
        if (position < 0) return
        val url = currentUrl
        val duration = bookDurationMs
        val total = if (duration > 0) duration / 1000.0 else null
        val currentTime = position / 1000.0
        val shouldFinish = finished ||
            (total != null && total - currentTime <= FINISH_WINDOW_SECONDS)

        CoroutineScope(Dispatchers.IO).launch {
            locator.database.bookMetadataDao().updateProgress(
                itemId = itemId,
                currentTime = currentTime,
                isFinished = shouldFinish,
            )
            if (url == null) return@launch
            when (val result = locator.api.updateProgress(
                url,
                itemId,
                ProgressUpdate(
                    currentTime = currentTime,
                    duration = total,
                    progress = total?.let { (currentTime / it).coerceIn(0.0, 1.0) },
                    isFinished = shouldFinish,
                ),
            )) {
                is ApiResult.Ok -> warnedOffline.set(false)
                is ApiResult.Failure -> Unit
            }
        }
    }

    // ---- player events ----------------------------------------------------

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            publish()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            publish()
            if (playbackState != Player.STATE_ENDED) return
            // The only place a book gets marked finished. Doing this on a media
            // item transition instead would be wrong: the tracks are merged into
            // a single item today, but a per-track arrangement would then mark the
            // whole book finished at the first chapter boundary.
            flushProgress(finished = true)
            // Already wrote the final position above.
            stopPlayback(flush = false)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = publish()

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "playback failed", error)
            locator.playback.update { it.copy(error = error.readableMessage()) }
        }
    }

    /**
     * Reads chapter data off the cached metadata and rebuilds the in-memory
     * boundary used for skip-chapter navigation. Called from both load paths;
     * one place keeps the contract in sync if either input field changes.
     */
    private fun loadChapterBoundary(meta: BookMetadataEntity?, durationMs: Long) {
        val serverChapters = ChaptersCodec.decode(meta?.chaptersJson)
        chapterBoundary = ChapterBoundaryBuilder.build(
            serverChapters = serverChapters,
            durationMs = durationMs,
        )
        publishedChapters = serverChapters
        lastPublishedChapterIndex = -1
    }

    /** Pushes the player snapshot into the shared state the UI renders. */
    private fun publish() {
        val position = bookPositionMs()
        val duration = bookDurationMs
        val index = currentTrackIndex()
        val trackName = trackNames.getOrNull(index).orEmpty()

        // The reporter loop ticks once a second. The chapter index changes only
        // when the listener crosses a boundary, so publishing it on every tick
        // would force the player screen to recompose for nothing. The index
        // short-circuits the update when it has not moved; the rest of the state
        // still updates as normal so the playhead bar keeps moving.
        val chapterIndex = chapterBoundary.indexAt(position)
        val chapterChanged = chapterIndex != lastPublishedChapterIndex

        locator.playback.update {
            if (chapterChanged) {
                it.copy(
                    positionMs = position,
                    durationMs = duration,
                    playing = player.isPlaying,
                    buffering = player.playbackState == Player.STATE_BUFFERING,
                    trackIndex = if (index >= 0) index else it.trackIndex,
                    trackTitle = trackName.ifBlank { it.trackTitle },
                    speed = player.playbackParameters.speed,
                    currentChapterIndex = chapterIndex,
                )
            } else {
                it.copy(
                    positionMs = position,
                    durationMs = duration,
                    playing = player.isPlaying,
                    buffering = player.playbackState == Player.STATE_BUFFERING,
                    trackIndex = if (index >= 0) index else it.trackIndex,
                    trackTitle = trackName.ifBlank { it.trackTitle },
                    speed = player.playbackParameters.speed,
                )
            }
        }
        if (chapterChanged) lastPublishedChapterIndex = chapterIndex
        syncNotification()
    }

    /**
     * Media3 is told about a session but never asks for a notification on this
     * path: its provider is installed and never called, and nothing reaches the
     * shade. Handing it our own notification keeps whichever path fires first
     * pointed at the single id below, so there is never a second notification.
     *
     * Lazy, because a property initialiser runs before Android has attached a
     * context to the service and the media session token is needed to build one.
     */
    private val mediaNotificationProvider: MediaNotification.Provider by lazy {
        object : MediaNotification.Provider {
            override fun createNotification(
                mediaSession: MediaSession,
                customLayout: ImmutableList<CommandButton>,
                actionFactory: MediaNotification.ActionFactory,
                callback: MediaNotification.Provider.Callback,
            ): MediaNotification = MediaNotification(NOTIFICATION_ID, buildNotification())

            override fun handleCustomCommand(
                mediaSession: MediaSession,
                action: String,
                extras: Bundle,
            ): Boolean = false
        }
    }

    private companion object {
        const val TAG = "PlaybackService"

        const val NOTIFICATION_ID = 4201
        const val NOTIFICATION_CHANNEL_ID = "inkshelf_playback"

        const val ACTION_PREVIOUS = "com.tristinbaker.inkshelf.action.PREVIOUS"
        const val ACTION_TOGGLE = "com.tristinbaker.inkshelf.action.TOGGLE"
        const val ACTION_NEXT = "com.tristinbaker.inkshelf.action.NEXT"
        const val PROGRESS_INTERVAL_MS = 10_000L

        /** How often the player snapshot is pushed to the UI. */
        const val UI_TICK_MS = 1_000L

        /**
         * Only this close to the end counts as finished. The server's own
         * heuristic is 10 seconds, which is far too eager when someone scrubs
         * around the last chapter.
         */
        const val FINISH_WINDOW_SECONDS = 3.0

    }
}

/**
 * Turns a [PlaybackException] into something worth showing on a 2.8 inch
 * e-ink panel. The raw code plus a stack trace helps nobody holding the device.
 */
private fun PlaybackException.readableMessage(): String {
    val httpStatus = generateSequence(cause) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()
        ?.responseCode

    if (httpStatus == 401 || httpStatus == 403) {
        return "The server rejected the access token. Sign in again."
    }

    // Surfaces as ERROR_CODE_IO_UNSPECIFIED, which used to be reported as an
    // unreachable server, even for a file already on the device.
    if (generateSequence(cause) { it.cause }.any { it is OutOfMemoryError }) {
        return "This book is too large for the player to open."
    }

    return when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "Could not reach the server."

        // Not a network code: it is any I/O failure, local files included.
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
            "The audio could not be read."

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "The server refused the request${httpStatus?.let { " (HTTP $it)" } ?: ""}."

        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        -> "This device cannot decode that audio format."

        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        -> "That audio format is not supported for direct playback. " +
            "It needs to be transcoded by the server."

        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        -> "The audio output could not be started."

        else -> "Playback failed ($errorCodeName)"
    }
}
