package io.github.trickhook.shadowzap.core

import kotlinx.serialization.json.Json

/** Shared JSON configuration: tolerant when reading files written by JS or other loader versions. */
internal val AppJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

/** Like [AppJson] but pretty-printed, for files humans may read. */
internal val PrettyJson: Json = Json(AppJson) {
    prettyPrint = true
}
