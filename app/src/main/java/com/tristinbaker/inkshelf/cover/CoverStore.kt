package com.tristinbaker.inkshelf.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import com.tristinbaker.inkshelf.core.net.AbsClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Cover art, fetched at exactly the size it will be drawn.
 *
 * Audiobookshelf exposes `GET /api/items/:id/cover` and resizes server-side to
 * `width`, caching the result on its own disk as `{id}_{width}x{height}.{fmt}`.
 * Asking for the precise pixel width we draw means a thumbnail costs one
 * server-side resize, once, and every later request is a file read.
 *
 * Covers are deliberately *not* token-bearing. `Auth.ignorePatterns` whitelists
 * `/api/items/:id/cover` from authentication for GET, so the bytes come back
 * without a session and a cached cover survives signing out.
 *
 * Decoding is what actually costs on a 16-level panel, so bitmaps are decoded
 * straight to the requested size rather than full-size-then-scaled, then run
 * through [CoverDither] so the panel draws them as shading rather than as a
 * dark square. The disk cache keeps the original JPEG; only the memory cache
 * holds the dithered result, as RGB_565 because covers have no alpha.
 */
class CoverStore(
    context: Context,
    private val api: AbsClient,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dir = File(appContext.filesDir, "covers")

    /**
     * Bounded by bytes, not by count, because a wide cover at the same
     * thumbnail width costs several times a square one. Sized off the app heap
     * so it cannot crowd out whatever else is running.
     */
    private val maxKb =
        (Runtime.getRuntime().maxMemory() / 1024 / 6).coerceIn(4L * 1024, 24L * 1024).toInt()

    private val memory = object : LruCache<String, Bitmap>(maxKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
    }

    /**
     * The panel is slow and single threaded; a burst of parallel decodes does
     * not help it and costs the heap. Two at a time keeps a fast page scroll
     * fed without thrashing.
     */
    private val decodePermits = Semaphore(2)

    private val inflight = mutableMapOf<String, Deferred<Bitmap?>>()

    /**
     * A cover for [itemId] at [width] pixels, or null when there is none or the
     * server is unreachable. Never throws: a missing cover is a blank tile, not
     * a failed screen.
     */
    suspend fun load(itemId: String, width: Int): Bitmap? {
        if (itemId.isBlank() || width <= 0) return null
        val key = "$itemId@$width"

        memory[key]?.let { return it }

        // One in-flight fetch per key, so scrolling a row back into view does
        // not start a second request for a cover already on the way.
        val pending = synchronized(inflight) {
            inflight[key] ?: scope.async { fetch(itemId, width, key) }.also { inflight[key] = it }
        }
        return try {
            pending.await()
        } finally {
            synchronized(inflight) { if (inflight[key] === pending) inflight.remove(key) }
        }
    }

    private suspend fun fetch(itemId: String, width: Int, key: String): Bitmap? {
        val file = File(dir, "$itemId@$width.jpg")
        val bytes = if (file.isFile && file.length() > 0L) {
            runCatching { file.readBytes() }.getOrNull()
        } else {
            api.cover(itemId, width)?.also { body ->
                runCatching {
                    dir.mkdirs()
                    file.writeBytes(body)
                    prune()
                }.onFailure { Log.w(TAG, "cannot cache cover for $itemId", it) }
            }
        } ?: return null

        return decodePermits.withPermit {
            decode(bytes, width)?.let { raw ->
                CoverDither.process(raw, width).also { if (it !== raw) raw.recycle() }
            }
        }?.also { memory.put(key, it) }
    }

    /**
     * Decodes at no more than [target] px wide. The server already honours
     * `width`, so this is normally a straight decode, but a server that ignored
     * the parameter would otherwise hand us a full-size cover per row.
     */
    private fun decode(bytes: ByteArray, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            },
        )
    }

    /**
     * Bounded by total size rather than file count, oldest evicted first. Only
     * walks the directory, and only after a write.
     */
    private fun prune() {
        var total = 0L
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        for (f in files) {
            total += f.length()
            if (total > MAX_DISK_BYTES) f.delete()
        }
    }

    private companion object {
        const val TAG = "CoverStore"

        /** Covers are small; a few thousand thumbnails still fit in this. */
        const val MAX_DISK_BYTES = 96L * 1024 * 1024
    }
}
