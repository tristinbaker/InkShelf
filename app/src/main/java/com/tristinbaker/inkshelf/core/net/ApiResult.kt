package com.tristinbaker.inkshelf.core.net

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    data class Failure(
        val message: String,
        val kind: Kind = Kind.Unknown,
    ) : ApiResult<Nothing> {
        enum class Kind {
            /** Could not open a socket at all. */
            Network,

            /** 401/403, or the refresh token is dead and the user must sign in. */
            Unauthorized,

            /** 429. Audiobookshelf limits auth to 40 per 10 minutes per IP. */
            RateLimited,

            /** 5xx. */
            Server,

            /** Other 4xx, usually a malformed request. */
            Client,
            Unknown,
        }
    }
}
