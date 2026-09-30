package com.tristinbaker.inkshelf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.core.content.ContextCompat
import androidx.room.Room
import com.tristinbaker.inkshelf.core.eink.MeinkController
import com.tristinbaker.inkshelf.core.eink.Settings
import com.tristinbaker.inkshelf.core.storage.DownloadFolder
import com.tristinbaker.inkshelf.core.net.AbsClient
import com.tristinbaker.inkshelf.cover.CoverStore
import com.tristinbaker.inkshelf.core.net.AuthStore
import com.tristinbaker.inkshelf.core.net.TlsPinner
import com.tristinbaker.inkshelf.data.AppDatabase
import com.tristinbaker.inkshelf.data.DownloadEntity
import com.tristinbaker.inkshelf.data.LibraryCache
import com.tristinbaker.inkshelf.download.DownloadCoordinator
import com.tristinbaker.inkshelf.download.DownloadService
import com.tristinbaker.inkshelf.playback.PlaybackCoordinator
import com.tristinbaker.inkshelf.playback.PlaybackService

/**
 * Hand-rolled service locator. A DI framework would be more machinery than a
 * single-module app with one graph this shallow warrants.
 *
 * Deliberately a process-wide singleton: the playback and download services run
 * in the same process as the UI, and they all have to observe the same
 * [AuthStore.session] StateFlow. Per-Activity instances would silently
 * desynchronise the token each copy thinks is current.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ServiceLocator private constructor(context: Context) {
    private val appContext = context.applicationContext

    /** Outlives every screen, so process-wide observers can collect on it. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: Settings by lazy { Settings(appContext) }

    /** Display name of the folder downloads are written to right now. */
    fun downloadFolderLabel(): String =
        DownloadFolder.label(settings.downloadFolder, appContext)

    /**
     * False only when a folder was chosen and has since become unreachable,
     * e.g. the SD card was pulled. No folder chosen means the MediaStore
     * default, which is always available.
     */
    fun downloadFolderUsable(): Boolean {
        val tree = settings.downloadFolder ?: return true
        return DownloadFolder.isUsable(appContext, tree)
    }
    val authStore: AuthStore by lazy { AuthStore(appContext) }
    val tlsPinner: TlsPinner by lazy { TlsPinner(appContext) }
    val meink: MeinkController by lazy { MeinkController(appContext) }

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "inkshelf.db")
            .addMigrations(AppDatabase.MIGRATION_3_4)
            .fallbackToDestructiveMigration()
            .build()
    }

    val api: AbsClient by lazy { AbsClient(authStore, tlsPinner) }

    val covers: CoverStore by lazy { CoverStore(appContext, api) }

    val playback: PlaybackCoordinator by lazy { PlaybackCoordinator() }

    val downloads: DownloadCoordinator by lazy {
        DownloadCoordinator(appScope, { database.downloadDao() }) { row ->
            if (row.state != DownloadEntity.STATE_DONE) return@DownloadCoordinator null
            val uri = row.uri ?: return@DownloadCoordinator null
            val resolver = appContext.contentResolver
            val present = runCatching {
                resolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
            }.getOrDefault(false)
            if (present) {
                null
            } else {
                Log.i("InkShelf", "download file missing, re-queueing ${row.fileName}")
                row.copy(
                    state = DownloadEntity.STATE_QUEUED,
                    bytesWritten = 0L,
                    uri = null,
                    error = null,
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    /**
     * The coordinators buffer a command until a service attaches, so it is safe to
     * start the service and send in either order. Both are started from a resumed
     * Activity, which keeps this within the background-start limits on API 31+.
     */
    fun startPlayback() = startService(PlaybackService::class.java)

    fun startDownloads() = startForegroundService(DownloadService::class.java)

    /**
     * `startService`, not `startForegroundService`: [PlaybackService] is a
     * `MediaSessionService`, which owns its notification and promotes itself to
     * foreground through Media3's own `MediaNotificationManager`. Starting it as
     * a foreground service instead means nothing calls `startForeground` within
     * five seconds and the platform kills the process.
     */
    private fun startService(clazz: Class<*>) {
        runCatching { appContext.startService(Intent(appContext, clazz)) }
            .onFailure { Log.w("ServiceLocator", "cannot start $clazz", it) }
    }

    private fun startForegroundService(clazz: Class<*>) {
        runCatching { ContextCompat.startForegroundService(appContext, Intent(appContext, clazz)) }
            .onFailure { Log.w("ServiceLocator", "cannot start $clazz", it) }
    }

    val libraryCache: LibraryCache by lazy {
        LibraryCache(database.libraryDao(), database.itemDao())
    }

    companion object {
        @Volatile
        private var instance: ServiceLocator? = null

        fun get(context: Context): ServiceLocator =
            instance ?: synchronized(this) {
                instance ?: ServiceLocator(context).also { instance = it }
            }
    }
}
