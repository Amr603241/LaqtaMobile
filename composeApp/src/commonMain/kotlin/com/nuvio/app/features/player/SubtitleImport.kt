package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import com.nuvio.app.features.autosync.SubtitleCharsetDetector
import io.ktor.http.Url

internal const val MaxImportedSubtitleBytes = 10 * 1024 * 1024

internal data class ImportedSubtitleFile(
    val name: String,
    val bytes: ByteArray,
)

internal fun safeSubtitleExtension(name: String): String =
    requireNotNull(safeSubtitleExtensionOrNull(name)) { "Unsupported subtitle file type" }

internal fun safeSubtitleExtensionOrNull(name: String): String? =
    name.substringAfterLast('.', "").lowercase()
        .takeIf { it in setOf("srt", "vtt", "webvtt", "ass", "ssa", "ttml", "dfxp", "xml") }

internal fun isHttpSubtitleUrl(value: String): Boolean {
    val url = value.trim()
    if (url.any(Char::isWhitespace)) return false
    val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
    if (scheme != "http" && scheme != "https") return false
    val authority = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.isBlank() || '@' in authority) return false
    val parsed = runCatching { Url(url) }.getOrNull() ?: return false
    return parsed.host.isNotBlank() && parsed.port in 1..65535
}

internal fun subtitleCharsetHint(language: String): String? =
    language.trim().takeUnless {
        it.isBlank() || it.equals("unknown", ignoreCase = true) ||
            it.equals("en", ignoreCase = true) || it.equals("eng", ignoreCase = true)
    }

internal fun normalizeImportedSubtitle(bytes: ByteArray, language: String): ByteArray =
    SubtitleCharsetDetector.normalizeToUtf8(bytes, languageHint = subtitleCharsetHint(language))

internal expect object SubtitleImportStorage {
    fun save(name: String, bytes: ByteArray): String
}

@Composable
internal expect fun rememberSubtitleFileImporter(
    onResult: (Result<ImportedSubtitleFile>) -> Unit,
): () -> Unit

internal fun importedSubtitleFromText(
    id: String,
    name: String,
    url: String,
    language: String,
    text: String,
): AddonSubtitle? {
    if (text.encodeToByteArray().size > MaxImportedSubtitleBytes) return null
    if (PlayerSubtitleCueParser.parse(text, url).isEmpty()) return null
    val displayName = name.trim().take(100).ifBlank { "Subtitle" }
    return AddonSubtitle(
        id = id,
        url = url,
        language = normalizeLanguageCode(language).orEmpty().ifBlank { "unknown" },
        display = displayName,
        addonName = "Laqta",
    )
}
