package com.tristinbaker.inkshelf.core.net

import kotlinx.serialization.json.Json

object AbsJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }
}
