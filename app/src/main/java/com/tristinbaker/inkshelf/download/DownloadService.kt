package com.tristinbaker.inkshelf.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tristinbaker.inkshelf.MainActivity
import com.tristinbaker.inkshelf.ServiceLocator
import com.tristinbaker.inkshelf.core.net.ApiResult
import com.tristinbaker.inkshelf.core.net.ServerUrl
import com.tristinbaker.inkshelf.core.playback.TrackFormats
import com.tristinbaker.inkshelf.core.storage.DownloadFolder
import com.tristinbaker.inkshelf.data.DownloadDao
import com.tristinbaker.inkshelf.data.DownloadEntity
import com.tristinbaker.inkshelf.data.toBookMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fetches books under `Music/InkShelf/`, into whichever folder the user picked
 * in settings, or the default MediaStore location when they have not.
 *
 * Media3's own `DownloadManager` is deliberately not used: it insists on owning
 * the cache layout and on a shared download database. Writing to MediaStore
 * directly puts files where any other audio app would put them, and they
 * survive the app being uninstalled.
 *
 * Files are fetched one at a time. Audiobookshelf is usually a home server on a
 * slow uplink, so parallelising would only delay the first track.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locator: ServiceLocator

    /**
     * "There may be work" signals for the one drain loop below.
     *
     * Draining used to be kicked off from several places at once behind a plain
     * Boolean. Two callers could interleave badly enough to strand a queued row:
     * one drain could finish its last empty check just as another enqueued and
     * bailed on the flag, leaving the row queued with nobody left to pick it up.
     * A conflated channel keeps exactly one drain loop running instead, and a
     * signal arriving while a drain is busy is held rather than dropped.
     */
    private val work = Channel<Unit>(Channel.CONFLATED)

    /**
     * Commands being handled right now. `enqueue` fetches the item detail over
     * the network before it writes any row, so a drain that starts alongside it
     * can see an empty queue and stop the service out from under it.
     */
    private val inFlight = AtomicInteger(0)

    /**
     * Row this process is actively transferring, if any.
     *
     * Reclaiming a row whose previous coroutine is still parked in a write
     * would put two writers on one file and corrupt it, so nothing else is
     * allowed to re-queue the row that is currently being worked on.
     */
    @Volatile
    private var activeRowId: String? = null

    override fun onCreate() {
        super.onCreate()
        locator = ServiceLocator.get(this)
        createChannel()
        val dao = locator.database.downloadDao()

        scope.launch {
            locator.downloads.commands.collect { command -> dispatch(command) }
        }

        scope.launch {
            dao.observeAll().collect { locator.downloads.set(locator.downloads.summarise(it)) }
        }

        // One drain loop for the life of the process. It recovers rows left
        // mid-flight by a killed process, then keeps draining as work is
        // signalled, so starting the service is enough to resume a download.
        scope.launch {
            recoverInterrupted()
            for (ignored in work) {
                drain()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification("Preparing downloads", null, 0))
        // The collector above is already subscribed on `Dispatchers.IO`, so the
        // command the UI sent before this process existed can be claimed now.
        locator.downloads.attach()?.let { queued ->
            Log.i(TAG, "picking up command held before start: $queued")
            scope.launch { dispatch(queued) }
        }
        work.trySend(Unit)
        return START_NOT_STICKY
    }

    private suspend fun dispatch(command: DownloadCommand) {
        inFlight.incrementAndGet()
        try {
            when (command) {
                is DownloadCommand.Enqueue -> enqueue(command.itemId)
                is DownloadCommand.Remove -> remove(command.itemId)
                is DownloadCommand.Retry -> retry(command.itemId)
            }
        } finally {
            inFlight.decrementAndGet()
            // The rows are written now, so the drain loop has something new to
            // look at. Signalling after the write is what stops the loop from
            // checking the queue before the row exists and stopping.
            work.trySend(Unit)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        locator.downloads.detach()
        scope.cancel()
        super.onDestroy()
    }

    // ---- queue ------------------------------------------------------------

    private suspend fun enqueue(itemId: String) {
        val url = serverUrl() ?: return
        val detail = when (val result = locator.api.itemDetail(url, itemId)) {
            is ApiResult.Failure -> {
                Log.w(TAG, "cannot queue $itemId: ${result.message}")
                return
            }

            is ApiResult.Ok -> result.value
        }

        val tracks = detail.media.tracks
        if (tracks.isEmpty()) return

        val title = detail.media.metadata.title.orEmpty().ifBlank { "Untitled" }
        val dao = locator.database.downloadDao()
        val now = System.currentTimeMillis()

        // The expanded item in hand is the only chance to record what this book
        // looks like. Without it a downloaded book would have no title, series or
        // narrator to show once the device is offline.
        val metadataDao = locator.database.bookMetadataDao()
        val cached = metadataDao.get(itemId)
        val serverTime = detail.userMediaProgress?.currentTime ?: 0.0
        metadataDao.upsert(
            detail.toBookMetadata(
                // Never move a local bookmark backwards on a metadata refresh.
                currentTime = maxOf(cached?.currentTime ?: 0.0, serverTime),
                isFinished = detail.userMediaProgress?.isFinished ?: (cached?.isFinished ?: false),
                cachedAt = now,
            ),
        )

        for (track in tracks) {
            val path = track.contentUrl ?: continue
            // Keyed by inode so a partly downloaded book resumes file by file.
            val id = "$itemId:${track.ino}"
            val existing = dao.get(id)
            if (existing != null) {
                // A row only proves what the database remembers. If the file has
                // since been deleted from a file manager, or the app data was
                // cleared, the row would keep reporting the track as complete and
                // drain() would skip it forever, so re-queue anything that no
                // longer has bytes behind it. Re-tapping Download also retries
                // a previous failure.
                val fileMissing = existing.uri != null && sizeOf(Uri.parse(existing.uri)) <= 0L
                if (!fileMissing && existing.state != DownloadEntity.STATE_FAILED) continue
                dao.upsert(
                    existing.copy(
                        state = DownloadEntity.STATE_QUEUED,
                        bytesWritten = 0L,
                        uri = null,
                        error = null,
                        startOffsetSeconds = track.startOffset,
                        updatedAt = now,
                    ),
                )
                continue
            }
            dao.upsert(
                DownloadEntity(
                    id = id,
                    itemId = itemId,
                    libraryItemTitle = title,
                    trackIndex = track.index,
                    fileName = track.fileName,
                    url = url.resolve(path),
                    mimeType = TrackFormats.resolve(track.extension, track.codec).mimeType,
                    size = track.metadata?.size ?: 0L,
                    bytesWritten = 0L,
                    state = DownloadEntity.STATE_QUEUED,
                    uri = null,
                    error = null,
                    startOffsetSeconds = track.startOffset,
                    updatedAt = now,
                ),
            )
        }

        // `onStartCommand` drains on its own, but it can finish that drain before
        // these rows exist, so every enqueue kicks one too. `drain` is guarded.
        drain()
    }

    private suspend fun retry(itemId: String) {
        val dao = locator.database.downloadDao()
        val now = System.currentTimeMillis()
        for (row in dao.observeForItem(itemId).first()) {
            if (row.state == DownloadEntity.STATE_FAILED) {
                dao.upsert(row.copy(state = DownloadEntity.STATE_QUEUED, error = null, updatedAt = now))
            }
        }
    }

    private suspend fun remove(itemId: String) {
        val dao = locator.database.downloadDao()
        for (row in dao.observeForItem(itemId).first()) {
            row.uri?.let { deleteMedia(Uri.parse(it)) }
        }
        dao.deleteForItem(itemId)
        // The offline description goes with the files: keeping it would leave a
        // book the app can still describe but can no longer play.
        locator.database.bookMetadataDao().delete(itemId)
    }

    /**
     * Re-queues rows left mid-flight by a dead process, then wakes the drain loop.
     *
     * The drain used to be kicked separately by onStartCommand, which raced this:
     * drain skips rows that are still RUNNING, so it would find nothing to do,
     * and this function then flipped the row to QUEUED with nothing left to pick
     * it up. The download sat there forever, reporting no progress and no error.
     * Signalling the one drain loop keeps that ordering impossible.
     */
    private suspend fun recoverInterrupted() {
        val dao = locator.database.downloadDao()
        val now = System.currentTimeMillis()
        for (row in dao.unfinished()) {
            if (row.state == DownloadEntity.STATE_RUNNING) {
                // Safe to re-queue: the resume offset is read back off the file
                // rather than trusted from this row.
                dao.upsert(row.copy(state = DownloadEntity.STATE_QUEUED, updatedAt = now))
            }
        }
        work.trySend(Unit)
    }

    /** Only ever called from the single drain loop, so it needs no guard. */
    private suspend fun drain() {
        val dao = locator.database.downloadDao()
        while (currentCoroutineContext().isActive) {
            // A row only reads RUNNING while a transfer owns it, and the
            // transfer republishes updatedAt as bytes land. One that has gone
            // quiet for longer than a stalled transfer is allowed to take is
            // dead, most often killed between closing the file and committing
            // DONE, and this process is what steps over it forever otherwise.
            reclaimStale(dao)
            val next = dao.unfinished()
                .firstOrNull { it.state != DownloadEntity.STATE_RUNNING }
                ?: break
            // Without a session there is nothing to fetch. Fetching anyway would
            // return without touching the row and the loop would spin on it.
            if (serverUrl() == null) {
                Log.i(TAG, "no session yet; leaving ${next.fileName} queued")
                break
            }
            fetchOne(dao, next)
        }
        // Queue empty and nothing still being set up: leave the foreground so no
        // permanent notification lingers. Stopping while a command is in flight
        // would cancel it, and the user would tap Download and see nothing.
        if (inFlight.get() == 0 && dao.unfinished().isEmpty()) {
            stopForegroundCompat()
            stopSelf()
        }
    }

    private suspend fun reclaimStale(dao: DownloadDao) {
        val now = System.currentTimeMillis()
        for (row in dao.unfinished()) {
            if (row.state != DownloadEntity.STATE_RUNNING) continue
            if (row.updatedAt >= now - STALE_RUNNING_MS) continue
            if (row.id == activeRowId) {
                // Ours, and still going quiet. A write can block on the storage
                // layer with no way to time it out from inside the loop, so the
                // honest thing is to say so rather than leave the bar frozen
                // looking like progress. Re-queuing here would double up on the
                // file, so this only reports, and the transfer is left to
                // finish or be retried by the user.
                if (row.updatedAt < now - STUCK_RUNNING_MS) {
                    Log.w(TAG, "${row.fileName} has stopped responding")
                    dao.upsert(
                        row.copy(
                            state = DownloadEntity.STATE_FAILED,
                            error = "The download stopped responding",
                            updatedAt = now,
                        ),
                    )
                }
                continue
            }
            Log.i(TAG, "reclaiming ${row.fileName}: no progress since ${row.updatedAt}")
            dao.upsert(row.copy(state = DownloadEntity.STATE_QUEUED, updatedAt = now))
        }
    }

    private suspend fun fetchOne(dao: DownloadDao, row: DownloadEntity) {
        val url = serverUrl() ?: return
        activeRowId = row.id
        try {
            fetchWithServer(dao, row, url)
        } finally {
            activeRowId = null
        }
    }

    private suspend fun fetchWithServer(
        dao: DownloadDao,
        row: DownloadEntity,
        url: ServerUrl,
    ) {
        val client = locator.api.okHttpClient(url)
        val token = locator.authStore.session.value?.accessToken

        // The file on disk, not the database counter, is the resume point: it is
        // the only thing that reflects what actually survived a crash.
        val existing = row.uri?.let { Uri.parse(it) }?.takeIf { sizeOf(it) > 0L }
        val resumeFrom = existing?.let { sizeOf(it) } ?: 0L
        val remote = remoteLength(client, row.url, token)

        // A file that already matches the server has nothing left to fetch, and
        // asking for a range starting at the end of it is answered with 416. That
        // is the normal shape of a download killed between the last byte landing
        // and DONE being committed, and failing it would report a complete book
        // as broken.
        if (remote > 0L && resumeFrom >= remote) {
            Log.i(TAG, "${row.fileName}: already complete on disk, $resumeFrom of $remote")
            return dao.upsert(
                row.copy(
                    state = DownloadEntity.STATE_DONE,
                    bytesWritten = resumeFrom,
                    size = remote,
                    uri = existing.toString(),
                    error = null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }

        markRunning(dao, row, resumeFrom)
        publishNotification()

        val request = Request.Builder()
            .url(row.url)
            .apply { if (!token.isNullOrEmpty()) header("Authorization", "Bearer $token") }
            .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
            .build()

        var response: Response? = null
        try {
            response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                // The length probe can be refused by a server that serves the
                // body anyway, so a rejected range still means the file on disk
                // reached the end of the one on the server. Treat it as finished
                // rather than leaving the user with a failed download they
                // cannot make progress on by trying again.
                if (response.code == 416 && resumeFrom > 0L) {
                    Log.i(TAG, "${row.fileName}: range refused, $resumeFrom bytes on disk")
                    return dao.upsert(
                        row.copy(
                            state = DownloadEntity.STATE_DONE,
                            bytesWritten = resumeFrom,
                            size = if (remote > 0L) remote else resumeFrom,
                            uri = existing.toString(),
                            error = null,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                return fail(dao, row, "Server returned HTTP ${response.code}")
            }

            // A server that ignores Range answers 200 with the whole body, which
            // cannot be appended to, so start that file over.
            val appending = resumeFrom > 0 && response.code == 206
            val startAt = if (appending) resumeFrom else 0L
            Log.i(
                TAG,
                "${row.fileName}: HTTP ${response.code}, resuming at $startAt of ${row.size}",
            )
            val remaining = response.body?.contentLength()?.takeIf { it >= 0 } ?: 0L
            val total = if (remaining > 0) startAt + remaining else row.size

            val uri = existing.takeIf { appending } ?: createFile(dao, row)
                ?: return fail(dao, row, "Could not create a file in ${targetLabel()}")

            val written = writeTo(dao, row, uri, response, startAt, total)

            dao.upsert(
                row.copy(
                    state = DownloadEntity.STATE_DONE,
                    bytesWritten = written,
                    size = if (total > 0) total else written,
                    uri = uri.toString(),
                    error = null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            publish(uri)
            publishNotification()
        } catch (e: IOException) {
            fail(dao, row, "Network error: ${e.message ?: "connection lost"}")
        } catch (e: Exception) {
            Log.w(TAG, "download failed for ${row.id}", e)
            fail(dao, row, e.message ?: "Download failed")
        } finally {
            response?.close()
        }
    }

    private suspend fun writeTo(
        dao: DownloadDao,
        row: DownloadEntity,
        uri: Uri,
        response: Response,
        startAt: Long,
        total: Long,
    ): Long {
        val body = response.body ?: return startAt
        var written = startAt
        var lastPublish = 0L
        // "wa" appends so a resume keeps the bytes already on disk; "wt"
        // truncates, which is required when restarting from zero.
        val mode = if (startAt > 0) "wa" else "wt"

        body.byteStream().use { input ->
            val output = contentResolver.openOutputStream(uri, mode)
                ?: throw IOException("Could not open $uri for writing")
            output.use { sink ->
                val buffer = ByteArray(BUFFER)
                while (currentCoroutineContext().isActive) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
                    written += read
                    val now = System.currentTimeMillis()
                    if (now - lastPublish > PROGRESS_PUBLISH_MS) {
                        lastPublish = now
                        dao.upsert(
                            row.copy(
                                state = DownloadEntity.STATE_RUNNING,
                                bytesWritten = written,
                                size = if (total > 0) total else row.size,
                                uri = uri.toString(),
                                updatedAt = now,
                            ),
                        )
                        publishNotification()
                    }
                }
                sink.flush()
            }
        }
        // OkHttp already throws on a truncated body, but a server that closes
        // cleanly early must not leave a short file that later reports as
        // "Saved to Music/InkShelf".
        if (total > 0 && written < total) {
            throw IOException("Incomplete download: got $written of $total bytes")
        }
        return written
    }

    private suspend fun markRunning(dao: DownloadDao, row: DownloadEntity, resumeFrom: Long) {
        dao.upsert(
            row.copy(
                state = DownloadEntity.STATE_RUNNING,
                bytesWritten = resumeFrom,
                error = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun fail(dao: DownloadDao, row: DownloadEntity, message: String) {
        dao.upsert(
            row.copy(
                state = DownloadEntity.STATE_FAILED,
                error = message,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        publishNotification()
    }

    // ---- MediaStore -------------------------------------------------------

    /** The picked folder tree, or null when the default MediaStore path is in use. */
    private fun targetTree(): Uri? {
        val tree = locator.settings.downloadFolder
        if (!DownloadFolder.isUsable(this, tree)) {
            // A card that was pulled, or a folder deleted, must not wedge every
            // download. Fall back to the default rather than failing outright.
            if (!tree.isNullOrBlank()) Log.w(TAG, "folder $tree unusable, using default")
            return null
        }
        return runCatching { Uri.parse(tree!!) }.getOrNull()
    }

    private fun targetLabel(): String =
        DownloadFolder.label(locator.settings.downloadFolder, this)

    private suspend fun createFile(dao: DownloadDao, row: DownloadEntity): Uri? {
        val tree = targetTree()
        val uri = if (tree != null) {
            createInTree(tree, row)
        } else {
            createInMediaStore(row)
        }
        if (uri != null) {
            dao.upsert(row.copy(uri = uri.toString(), updatedAt = System.currentTimeMillis()))
        }
        return uri
    }

    /**
     * SAF path. Adopts an existing document of the same name so a file left
     * behind by a lost database row is resumed rather than duplicated into
     * "track (1).mp3".
     */
    private fun createInTree(tree: Uri, row: DownloadEntity): Uri? {
        val folder = DownloadFolder.bookFolder(this, tree, sanitize(row.libraryItemTitle))
            ?: return null
        val displayName = sanitize(row.fileName)
        findDocument(folder, displayName)?.let {
            Log.i(TAG, "adopting existing $displayName in $folder")
            return it
        }
        return runCatching {
            DocumentsContract.createDocument(contentResolver, folder, row.mimeType, displayName)
        }.onFailure { Log.w(TAG, "createDocument failed in $folder", it) }.getOrNull()
    }

    private fun findDocument(folder: Uri, displayName: String): Uri? = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            folder,
            DocumentsContract.getDocumentId(folder),
        )
        contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == displayName) {
                    return DocumentsContract.buildDocumentUriUsingTree(folder, cursor.getString(0))
                }
            }
            null
        }
    }.getOrNull()

    private suspend fun createInMediaStore(row: DownloadEntity): Uri? {
        val collection = DownloadFolder.defaultCollection()
        val displayName = sanitize(row.fileName)
        val relativePath = DownloadFolder.defaultRelativePath(sanitize(row.libraryItemTitle))

        // Inserting blindly makes MediaStore disambiguate a name clash into
        // "track (1).mp3", so a file left behind by a lost database row would
        // grow a duplicate on every re-download. Adopt the existing row instead,
        // which also makes a download self-healing after the app data is cleared.
        val existing = findMediaRow(collection, displayName, relativePath)
        if (existing != null) {
            contentResolver.update(
                existing,
                ContentValues().apply {
                    put(MediaStore.Audio.Media.MIME_TYPE, row.mimeType)
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                },
                null,
                null,
            )
            return existing
        }

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, row.mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            // Deliberately not IS_PENDING. A pending row is hidden and its bytes
            // are not reliably reopenable after the process dies, which would
            // throw away every partial download and force a restart from zero.
            // A half-written file inside InkShelf/ is harmless and is what makes
            // a Range resume possible at all.
        }
        return contentResolver.insert(collection, values)
    }

    private fun findMediaRow(collection: Uri, displayName: String, relativePath: String): Uri? {
        val projection = arrayOf(MediaStore.Audio.Media._ID)
        // MediaStore normalises RELATIVE_PATH to a trailing slash on insert, so
        // comparing against the raw path never matches an existing row.
        val selection =
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND " +
                "${MediaStore.Audio.Media.RELATIVE_PATH} = ?"
        val args = arrayOf(displayName, "$relativePath/")
        return try {
            contentResolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.Audio.Media._ID} DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                ContentUris.withAppendedId(collection, cursor.getLong(0))
            }
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * Clears IS_PENDING so the finished file becomes visible to other apps.
     *
     * MediaStore only. A SAF document has no IS_PENDING to clear and its
     * provider rejects the update outright, so this is skipped for those rather
     * than logging a stack trace on every finished download.
     */
    private fun publish(uri: Uri) {
        if (uri.authority != MediaStore.AUTHORITY) return
        val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
        runCatching { contentResolver.update(uri, values, null, null) }
            .onFailure { Log.w(TAG, "could not publish $uri: ${it.message}") }
    }

    /**
     * Length of [url] on the server, or -1 when it cannot be determined.
     *
     * HEAD is tried first because it is cheap, but some file endpoints refuse it,
     * so a single-byte range request reading `Content-Range` is the fallback.
     */
    private fun remoteLength(client: OkHttpClient, url: String, token: String?): Long {
        fun request(builder: Request.Builder): Request =
            builder.url(url)
                .apply { if (!token.isNullOrEmpty()) header("Authorization", "Bearer $token") }
                .build()

        val head = runCatching {
            client.newCall(request(Request.Builder().head())).execute().use {
                if (it.isSuccessful) it.header("Content-Length")?.toLongOrNull() else null
            }
        }.getOrNull()
        if (head != null && head > 0L) return head

        val range = runCatching {
            client.newCall(request(Request.Builder().header("Range", "bytes=0-0")))
                .execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.header("Content-Range")
                        ?.substringAfter('/')
                        ?.trim()
                        ?.toLongOrNull()
                }
        }.getOrNull()
        return range?.takeIf { it > 0L } ?: -1L
    }

    private fun sizeOf(uri: Uri): Long = runCatching {
        contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    private fun deleteMedia(uri: Uri) {
        runCatching { contentResolver.delete(uri, null, null) }
            .onFailure { Log.w(TAG, "could not delete $uri", it) }
    }

    private fun serverUrl(): ServerUrl? =
        (ServerUrl.parse(locator.authStore.session.value?.serverUrl.orEmpty()) as? ServerUrl.Result.Valid)
            ?.url

    private fun sanitize(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().take(120).ifBlank { "Untitled" }

    // ---- notification -----------------------------------------------------

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(title: String, text: String?, progress: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .apply { if (progress > 0) setProgress(100, progress, false) }
            .build()
    }

    private fun publishNotification() {
        val active = locator.downloads.summaries.value.values
            .firstOrNull { it.isActive || it.hasFailure }
        val notification = if (active == null) {
            buildNotification("Downloads complete", null, 0)
        } else {
            buildNotification(
                active.itemId.takeLast(48),
                "${active.doneTracks} of ${active.totalTracks} files",
                (active.progress * 100).toInt(),
            )
        }
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.w(TAG, "startForeground refused", it) }
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    private companion object {
        const val TAG = "DownloadService"
        const val CHANNEL_ID = "inkshelf_downloads"
        const val NOTIFICATION_ID = 4201
        const val BUFFER = 64 * 1024
        const val PROGRESS_PUBLISH_MS = 1_000L

        /**
         * How long a RUNNING row may go without republishing before the service
         * assumes the transfer behind it died. Comfortably longer than
         * [PROGRESS_PUBLISH_MS] so a slow but healthy transfer is never
         * reclaimed, and comfortably shorter than a user would wait before
         * deciding the app has hung.
         */
        const val STALE_RUNNING_MS = 60_000L

        /**
         * Longer than [STALE_RUNNING_MS] before a transfer this process still
         * owns is called unresponsive and reported as failed.
         */
        const val STUCK_RUNNING_MS = 5 * 60_000L
    }
}
