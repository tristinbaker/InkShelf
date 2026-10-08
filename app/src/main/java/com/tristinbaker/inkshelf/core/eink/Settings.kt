package com.tristinbaker.inkshelf.core.eink

import android.content.Context
import android.content.SharedPreferences
import com.tristinbaker.inkshelf.core.abs.BrowseOrder

/**
 * Synchronous settings, backed by plain prefs rather than DataStore because the
 * e-ink display mode has to be applied during `onResume` on the very first
 * frame, and a suspending read would flash the wrong panel mode first.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("inkshelf_settings", Context.MODE_PRIVATE)

    var einkMode: EinkMode
        get() = EinkMode.fromCode(prefs.getInt(KEY_EINK, EinkMode.DEFAULT.code)) ?: EinkMode.DEFAULT
        set(value) = prefs.edit().putInt(KEY_EINK, value.code).apply()

    var browseOrder: BrowseOrder
        get() = runCatching { BrowseOrder.valueOf(prefs.getString(KEY_ORDER, null) ?: "") }
            .getOrDefault(BrowseOrder.TITLE)
        set(value) = prefs.edit().putString(KEY_ORDER, value.name).apply()

    var descending: Boolean
        get() = prefs.getBoolean(KEY_DESC, false)
        set(value) = prefs.edit().putBoolean(KEY_DESC, value).apply()

    var libraryId: String?
        get() = prefs.getString(KEY_LIBRARY, null)
        set(value) = prefs.edit().putString(KEY_LIBRARY, value).apply()

    /**
     * SAF tree URI downloads are written into, chosen by the user in settings.
     * Null means the default MediaStore location under `Music/InkShelf`.
     *
     * Stored as a URI rather than a path so scoped storage keeps working and
     * the choice survives the card being remounted elsewhere.
     */
    var downloadFolder: String?
        get() = prefs.getString(KEY_DOWNLOAD_FOLDER, null)
        set(value) = prefs.edit().putString(KEY_DOWNLOAD_FOLDER, value).apply()

    /**
     * Whether list rows draw cover art next to the titles.
     *
     * Off trades the artwork for speed: every thumbnail is a decode and a blit
     * while the list is scrolling, which on this panel is what turns a page jump
     * into a visible repaint of the whole list.
     */
    var showCovers: Boolean
        get() = prefs.getBoolean(KEY_SHOW_COVERS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_COVERS, value).apply()

    /** Page turns by default: smooth scrolling smears on this panel. */
    var scrollMode: ScrollMode
        get() = runCatching { ScrollMode.valueOf(prefs.getString(KEY_SCROLL_MODE, null) ?: "") }
            .getOrDefault(ScrollMode.PAGE)
        set(value) = prefs.edit().putString(KEY_SCROLL_MODE, value.name).apply()

    /**
     * Book the listener was last playing, so closing the app and coming back
     * lands on its player rather than the library.
     *
     * Has to outlive the process: the playback service is not promoted to the
     * foreground, so closing the app from recents takes playback down with it
     * and an in-memory record would be gone before the app came back. Cleared
     * when playback stops or finishes, so neither case reopens a book.
     */
    var lastPlayedItemId: String?
        get() = prefs.getString(KEY_LAST_PLAYED, null)
        set(value) = prefs.edit().putString(KEY_LAST_PLAYED, value).apply()

    private companion object {
        const val KEY_EINK = "eink_mode"
        const val KEY_ORDER = "browse_order"
        const val KEY_DESC = "browse_desc"
        const val KEY_LIBRARY = "library_id"
        const val KEY_DOWNLOAD_FOLDER = "download_folder"
        const val KEY_LAST_PLAYED = "last_played_item"
        const val KEY_SHOW_COVERS = "show_covers"
        const val KEY_SCROLL_MODE = "scroll_mode"
    }
}
