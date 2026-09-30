package com.tristinbaker.inkshelf.core.net

/**
 * A validated Audiobookshelf base URL, normalised so that joining paths is
 * mechanical. Handles servers mounted under a subpath via `ROUTER_BASE_PATH`,
 * with or without a trailing slash.
 *
 * Plain HTTP is rejected: the manifest's network security config blocks cleartext
 * and we would rather fail at the input with a clear message than at the socket.
 */
class ServerUrl private constructor(
    val canonical: String,
    /** Bare hostname with no port, which is what OkHttp's pinner matches on. */
    val host: String,
    val port: Int,
) {
    val isSubPath: Boolean
        get() = canonical != "https://$hostPort"

    /** `host` or `host:port`, for display and diagnostics. */
    val hostPort: String
        get() = if (port == HTTPS_PORT) host else "$host:$port"

    fun resolve(path: String): String {
        val suffix = path.removePrefix("/")
        return "$canonical/$suffix"
    }

    fun resolveWithQuery(path: String, query: List<Pair<String, String>>): String {
        if (query.isEmpty()) return resolve(path)
        val encoded = query.joinToString("&") { (k, v) -> "$k=${percentEncode(v)}" }
        return "${resolve(path)}?$encoded"
    }

    override fun toString(): String = canonical

    companion object {
        private val ALLOWED_SCHEMES = setOf("https")
        const val HTTPS_PORT = 443

        /**
         * Returns null when [raw] is unusable, so callers can surface a specific
         * reason rather than catching a generic parse error.
         */
        fun parse(raw: String): Result {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return Result.Invalid("Enter your server address")

            val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
            if (scheme.isEmpty()) {
                return Result.Invalid("Address must start with https://")
            }
            if (scheme !in ALLOWED_SCHEMES) {
                return Result.Invalid(
                    if (scheme == "http") {
                        "Plain http:// is not supported. Use https://, or put a TLS " +
                            "terminating reverse proxy in front of Audiobookshelf."
                    } else {
                        "Unsupported scheme \"$scheme://\", expected https://"
                    },
                )
            }

            val afterScheme = trimmed.substringAfter("://")
            val authority = afterScheme.substringBefore('/')
            if (authority.isBlank()) return Result.Invalid("Address is missing a host")

            val hostPart = authority.substringBeforeLast(':', missingDelimiterValue = authority)
            if (hostPart.isBlank()) return Result.Invalid("Address is missing a host")
            if (hostPart.contains(' ')) return Result.Invalid("Host contains a space")

            val portPart = authority.substringAfterLast(':', missingDelimiterValue = "")
            if (portPart.isNotEmpty() && (portPart.toIntOrNull() == null || portPart.toInt() !in 1..65535)) {
                return Result.Invalid("Port must be a number between 1 and 65535")
            }

            // Strip trailing slashes so resolve() never doubles them.
            val path = afterScheme.substringAfter('/', missingDelimiterValue = "")
                .trim('/')
                .let { if (it.isEmpty()) "" else "/$it" }

            return Result.Valid(
                ServerUrl(
                    canonical = "https://$authority$path",
                    host = hostPart.lowercase(),
                    port = portPart.toIntOrNull() ?: HTTPS_PORT,
                ),
            )
        }
    }

    sealed interface Result {
        data class Valid(val url: ServerUrl) : Result
        data class Invalid(val reason: String) : Result
    }
}

/**
 * OkHttp percent-encodes query values for us, but we build some URLs by hand, so
 * this covers the characters that actually appear in Audiobookshelf sort keys
 * (`media.metadata.title`) and in base64 filter values.
 */
internal fun percentEncode(value: String): String = buildString {
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val c = byte.toInt().toChar()
        if (c.isLetterOrDigit() || c in "-_.~") {
            append(c)
        } else {
            append('%')
            append(HEX[(byte.toInt() shr 4) and 0xF])
            append(HEX[byte.toInt() and 0xF])
        }
    }
}

private const val HEX = "0123456789ABCDEF"
