package com.tristinbaker.inkshelf.core.net

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Session(
    val serverUrl: String,
    val username: String,
    val accessToken: String?,
    val refreshToken: String?,
)

/**
 * Token storage.
 *
 * Access tokens expire after two hours and refresh tokens after thirty days, so
 * both are persisted. They live in `EncryptedSharedPreferences` because a plain
 * prefs file is world-readable on a rooted device and these grant full library
 * access.
 *
 * `X-Return-Tokens: true` is mandatory on login: without it the server returns
 * the refresh token only as an httpOnly cookie, which a native client silently
 * discards and is therefore stuck re-authenticating every two hours.
 */
class AuthStore(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "inkshelf_auth",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ) as SharedPreferences
    }.getOrElse {
        // A corrupt keystore entry (OS upgrade, restored backup) must not brick
        // the app. Fall back to clear prefs and force a re-login.
        context.deleteSharedPreferences("inkshelf_auth")
        context.getSharedPreferences("inkshelf_auth_fallback", Context.MODE_PRIVATE)
    }

    private val _session = MutableStateFlow(read())
    val session: StateFlow<Session?> = _session.asStateFlow()

    val isSignedIn: Boolean get() = _session.value?.refreshToken != null

    fun save(session: Session) {
        prefs.edit()
            .putString(KEY_URL, session.serverUrl)
            .putString(KEY_USER, session.username)
            .putString(KEY_ACCESS, session.accessToken)
            .putString(KEY_REFRESH, session.refreshToken)
            .apply()
        _session.value = session
    }

    fun updateTokens(accessToken: String?, refreshToken: String?) {
        val current = _session.value ?: return
        save(
            current.copy(
                accessToken = accessToken,
                refreshToken = refreshToken ?: current.refreshToken,
            ),
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
        _session.value = null
    }

    fun read(): Session? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        val user = prefs.getString(KEY_USER, null) ?: return null
        return Session(
            serverUrl = url,
            username = user,
            accessToken = prefs.getString(KEY_ACCESS, null),
            refreshToken = prefs.getString(KEY_REFRESH, null),
        )
    }

    private companion object {
        const val KEY_URL = "server_url"
        const val KEY_USER = "username"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
    }
}
