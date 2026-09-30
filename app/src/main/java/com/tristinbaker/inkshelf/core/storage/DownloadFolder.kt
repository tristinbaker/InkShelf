package com.tristinbaker.inkshelf.core.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log

/**
 * Where downloads get written.
 *
 * A SAF tree URI, because that is the only handle that survives scoped storage
 * and still lets the user point at any folder on any volume, SD card included.
 * A filesystem path would need broad storage access and would break the moment
 * the card was remounted.
 *
 * Null means no folder has been chosen yet, and downloads fall back to the
 * default MediaStore location so the app works before anyone visits settings.
 */
object DownloadFolder {

    /** MediaStore location used until a folder is picked. */
    const val DEFAULT_LABEL = "Internal storage / Music/InkShelf"

    private const val TAG = "DownloadFolder"

    private val GRANT_FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** Human name for a picked folder, so settings never shows a raw tree URI. */
    fun label(treeUri: String?, context: Context): String {
        if (treeUri.isNullOrBlank()) return DEFAULT_LABEL
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return DEFAULT_LABEL
        return displayName(uri, context) ?: uri.lastPathSegment ?: DEFAULT_LABEL
    }

    private fun displayName(uri: Uri, context: Context): String? = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
        context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.onFailure { Log.w(TAG, "could not read folder name", it) }.getOrNull()

    /**
     * Takes a durable grant on the tree.
     *
     * Without the persistable flag the grant dies with the process, and
     * downloads run in a service that outlives the UI, so every download after
     * a process death would fail with a bare SecurityException.
     */
    fun persist(context: Context, treeUri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(treeUri, GRANT_FLAGS)
        true
    }.getOrElse {
        // A provider offering no persistable grants is still fine for this
        // session, so this warns rather than failing outright.
        Log.w(TAG, "tree grant could not be persisted", it)
        false
    }

    /** True when a previously granted tree is still writable. */
    fun isUsable(context: Context, treeUri: String?): Boolean {
        if (treeUri.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return false
        val persisted = runCatching {
            context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isWritePermission
            }
        }.getOrDefault(false)
        if (persisted) return true
        // Nothing persisted yet, but a grant may still be live for this process.
        return runCatching {
            context.contentResolver.takePersistableUriPermission(uri, GRANT_FLAGS)
            true
        }.getOrDefault(false)
    }

    fun defaultRelativePath(bookTitle: String): String =
        "${Environment.DIRECTORY_MUSIC}/InkShelf/$bookTitle"

    fun defaultCollection(): Uri =
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /**
     * The folder a book's files belong in under [treeUri], creating per-book
     * subfolders where the provider supports directories.
     *
     * Returns the picked folder itself when directories are unavailable, so a
     * restricted provider degrades to a flat folder rather than failing every
     * download.
     */
    fun bookFolder(context: Context, treeUri: Uri, bookTitle: String): Uri? = runCatching {
        val root = DocumentsContract.getTreeDocumentId(treeUri)
        var parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, root)
        for (segment in bookTitle.split('/').filter { it.isNotBlank() }) {
            parent = ensureChild(context, parent, segment) ?: return@runCatching parent
        }
        parent
    }.getOrNull()

    private fun ensureChild(context: Context, parent: Uri, name: String): Uri? {
        findChild(context, parent, name)?.let { return it }
        return runCatching {
            DocumentsContract.createDocument(
                context.contentResolver,
                parent,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            )
        }.onFailure { Log.w(TAG, "no subfolder support in $parent", it) }.getOrNull()
    }

    private fun findChild(context: Context, parent: Uri, name: String): Uri? = runCatching {
        val docId = DocumentsContract.getDocumentId(parent)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, docId)
        context.contentResolver.query(
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
                if (cursor.getString(1) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(
                        parent,
                        cursor.getString(0),
                    )
                }
            }
            null
        }
    }.getOrNull()
}