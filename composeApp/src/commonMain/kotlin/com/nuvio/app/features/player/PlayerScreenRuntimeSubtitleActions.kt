package com.nuvio.app.features.player

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.i18n.localizedNoSubtitleLinesFound
import com.nuvio.app.core.i18n.localizedSubtitleLinesLoadError
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.autosync.AutoSyncPlayerController
import com.nuvio.app.features.autosync.AutoSyncSubtitleHttp
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.subtitle_download_failed
import nuvio.composeapp.generated.resources.subtitle_added
import nuvio.composeapp.generated.resources.subtitle_invalid_or_unsupported
import org.jetbrains.compose.resources.getString

internal fun PlayerScreenRuntime.addSubtitleFromUrl(url: String, language: String) {
    if (!isHttpSubtitleUrl(url)) {
        scope.launch { NuvioToastController.show(getString(Res.string.subtitle_invalid_or_unsupported)) }
        return
    }
    scope.launch {
        try {
            val response = withTimeoutOrNull(15_000L) {
                AutoSyncSubtitleHttp.get(
                    url = url.trim(),
                    headers = emptyMap(),
                    maxResponseBodyBytes = MaxImportedSubtitleBytes,
                    languageHint = subtitleCharsetHint(language),
                    failOnTruncated = true,
                )
            } ?: error(getString(Res.string.subtitle_download_failed))
            check(response.status in 200..299) { getString(Res.string.subtitle_download_failed) }
            val text = response.body
            val subtitle = importedSubtitleFromText(
                id = "url:${url.trim()}",
                name = url.substringAfterLast('/').substringBefore('?').ifBlank { "Subtitle" },
                url = url.trim(),
                language = language,
                text = text,
            )
            if (subtitle == null) {
                NuvioToastController.show(getString(Res.string.subtitle_invalid_or_unsupported))
                return@launch
            }
            val extension = PlayerSubtitleCueParser.fileExtension(text, url)
            val fileName = "${subtitle.display.substringBeforeLast('.', subtitle.display)}.$extension"
            val localUri = withContext(Dispatchers.IO) {
                SubtitleImportStorage.save(fileName, text.encodeToByteArray())
            }
            addImportedSubtitle(subtitle.copy(id = localUri, url = localUri))
            NuvioToastController.show(getString(Res.string.subtitle_added))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            NuvioToastController.show(getString(Res.string.subtitle_download_failed))
        }
    }
}

internal fun PlayerScreenRuntime.addImportedSubtitle(subtitle: AddonSubtitle) {
    SubtitleRepository.addImportedSubtitle(subtitle)
    selectAddonSubtitle(subtitle)
}

internal fun PlayerScreenRuntime.fetchAddonSubtitlesForActiveItem() {
    if (activeSourceUrl.startsWith("file:") && externalSubtitles.isNotEmpty()) {
        SubtitleRepository.clear()
        return
    }
    // Without a network the addon lookup can only hang; keep the local state untouched.
    if (isNetworkOffline()) return
    val type = activeAddonSubtitleType.takeIf { it.isNotBlank() } ?: return
    val videoId = activeVideoId?.takeIf { it.isNotBlank() } ?: return
    val extras = activeSubtitleExtras
    // For P2P streams activeSourceUrl is a torrent:// sentinel — the real HTTP URL
    // comes from the local engine once the stream is resolved, mirroring NuvioTV where
    // currentStreamUrl is set to the local engine URL for torrents.
    val hashableUrl = p2pResolvedSourceUrl ?: activeSourceUrl
    if (extras?.videoHash == null && hashableUrl.isNotBlank() &&
        (hashableUrl.startsWith("http://", true) || hashableUrl.startsWith("https://", true))
    ) {
        // Compute hash lazily — same pattern as NuvioTV.
        SubtitleRepository.fetchAddonSubtitlesWithHashComputation(
            type = type,
            videoId = videoId,
            extras = extras,
            sourceUrl = hashableUrl,
            sourceHeaders = if (p2pResolvedSourceUrl != null) emptyMap() else activeSourceHeaders,
            onExtrasUpdated = { enriched ->
                activeSubtitleExtras = enriched
                persistComputedVideoHash(enriched)
            },
        )
    } else {
        SubtitleRepository.fetchAddonSubtitles(type, videoId, extras)
    }
}

/**
 * After lazy hash computation succeeds, patch the *existing* cached stream link
 * entry with the newly computed hash. Uses a read-modify-write so that we never
 * create a new entry (respecting server-stream exclusions and saveForReuse=false)
 * and never reset cachedAtMs.
 */
private fun PlayerScreenRuntime.persistComputedVideoHash(enriched: SubtitleRequestExtras) {
    if (!playerSettingsUiState.streamReuseLastLinkEnabled) return
    val vid = activeVideoId ?: return
    val cacheKey = StreamLinkCacheRepository.contentKey(
        type = contentType ?: parentMetaType,
        videoId = vid,
        parentMetaId = parentMetaId,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
    )
    StreamLinkCacheRepository.updateVideoHash(cacheKey, enriched.videoHash, enriched.videoSize)
}

internal fun PlayerScreenRuntime.setSubtitleDelay(delayMs: Int) {
    (playerController as? AutoSyncPlayerController)?.cancelForManualSubtitleDelay() // AutoSync hook
    val clamped = delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    subtitleDelayMs = clamped
    PlayerTrackPreferenceStorage.saveSubtitleDelayMs(playbackSession.videoId, clamped)
    playerController?.setSubtitleDelayMs(clamped)
}

internal fun PlayerScreenRuntime.loadSubtitleAutoSyncCues(force: Boolean = false) {
    val subtitle = selectedAddonSubtitle ?: return
    if (!force && subtitleAutoSyncState.cues.isNotEmpty()) return
    // Offline: the cue download would only hang, so skip it and leave the state as is.
    if (isNetworkOffline()) return
    subtitleAutoSyncState = subtitleAutoSyncState.copy(isLoading = true, errorMessage = null)
    scope.launch {
        val result = runCatching {
            val body = httpGetTextWithHeaders(
                url = subtitle.url,
                headers = sanitizePlaybackHeaders(activeSourceHeaders),
            )
            PlayerSubtitleCueParser.parse(body, subtitle.url)
        }
        result.fold(
            onSuccess = { cues ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    cues = cues,
                    isLoading = false,
                    errorMessage = if (cues.isEmpty()) localizedNoSubtitleLinesFound() else null,
                )
            },
            onFailure = { error ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    isLoading = false,
                    errorMessage = error.message ?: localizedSubtitleLinesLoadError(),
                )
            },
        )
    }
}

internal fun PlayerScreenRuntime.captureSubtitleAutoSyncTime() {
    subtitleAutoSyncState = subtitleAutoSyncState.copy(
        capturedPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L),
        errorMessage = null,
    )
    loadSubtitleAutoSyncCues()
}

internal fun PlayerScreenRuntime.applySubtitleAutoSyncCue(cue: SubtitleSyncCue) {
    val capturedPositionMs = subtitleAutoSyncState.capturedPositionMs ?: return
    val newDelayMs = (capturedPositionMs - cue.startTimeMs - SUBTITLE_AUTO_SYNC_REACTION_COMPENSATION_MS)
        .toInt()
        .coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    setSubtitleDelay(newDelayMs)
}
