package com.nuvio.app.features.addons

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.putSyncOriginClientId
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

@Serializable
internal data class AddonRow(
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
internal data class AddonPushItem(
    val url: String,
    val name: String = "",
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
internal data class PendingAddonPush(
    val userId: String?,
    val backendUrl: String,
    val addons: List<AddonPushItem>,
)

private const val ADDON_PUSH_DEBOUNCE_MS = 500L

object AddonRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AddonRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _uiState = MutableStateFlow(AddonsUiState())
    val uiState: StateFlow<AddonsUiState> = _uiState.asStateFlow()
    private val _syncUiState = MutableStateFlow(AddonSyncUiState())
    val syncUiState: StateFlow<AddonSyncUiState> = _syncUiState.asStateFlow()

    private var initialized = false
    private var currentProfileId: Int = 1
    private val activeRefreshJobs = mutableMapOf<String, Job>()
    private val pushJobsByProfile = mutableMapOf<Int, Job>()
    private val serverSyncMutex = Mutex()
    private val pendingPushStorageLock = SynchronizedObject()

    fun initialize() {
        val effectiveProfileId = resolveEffectiveProfileId(ProfileRepository.activeProfileId)
        if (initialized) {
            updateSyncUiState()
            return
        }
        initialized = true
        currentProfileId = effectiveProfileId
        log.d { "initialize() — loading local addons for profile $currentProfileId" }
        updateSyncUiState(isSyncing = false, hasFailed = false)

        val storedUrls = dedupeManifestUrls(AddonStorage.loadInstalledAddonUrls(currentProfileId))
        val enabledByUrl = loadLocalEnabledStates()
        log.d { "initialize() — local addon count: ${storedUrls.size}" }
        if (storedUrls.isEmpty()) return

        val existingByUrl = _uiState.value.addons.associateBy(ManagedAddon::manifestUrl)
        _uiState.value = AddonsUiState(
            addons = storedUrls.map { manifestUrl ->
                existingByUrl[manifestUrl].toPendingAddon(
                    manifestUrl = manifestUrl,
                    enabled = enabledByUrl[manifestUrl],
                )
            },
        )

        storedUrls.forEach { manifestUrl ->
            val existing = existingByUrl[manifestUrl]
            val addon = _uiState.value.addons.firstOrNull { it.manifestUrl == manifestUrl }
            if (addon?.enabled == true && (existing == null || (addon.manifest == null && !addon.isRefreshing))) {
                refreshAddon(manifestUrl)
            }
        }
    }

    fun onProfileChanged(profileId: Int) {
        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        if (effectiveProfileId == currentProfileId && initialized) return
        cancelActiveRefreshes()
        currentProfileId = effectiveProfileId
        initialized = false
        _uiState.value = AddonsUiState()
        updateSyncUiState(isSyncing = false, hasFailed = false)
    }

    fun clearLocalState() {
        cancelActiveRefreshes()
        pushJobsByProfile.values.forEach(Job::cancel)
        pushJobsByProfile.clear()
        currentProfileId = 1
        initialized = false
        _uiState.value = AddonsUiState()
        _syncUiState.value = AddonSyncUiState()
    }

    suspend fun pullFromServer(profileId: Int) {
        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        if (currentProfileId != effectiveProfileId) onProfileChanged(profileId)
        currentProfileId = effectiveProfileId
        log.i { "pullFromServer() — profileId=$profileId, initialized=$initialized" }
        runCatching {
            initialize()
            retryPendingPush(effectiveProfileId)
            if (!ServerConfigurationRepository.active.value.isCustom) {
                pullFromLaqtaServer(currentProfileId)
                return@runCatching
            }
            val rows = SupabaseProvider.client.postgrest.from("addons").select {
                filter { eq("profile_id", currentProfileId) }
                order("sort_order", Order.ASCENDING)
            }.decodeList<AddonRow>()
            applyServerRows(rows)
        }.onFailure { e ->
            if (e is CancellationException) throw e
            log.e(e) { "pullFromServer() — FAILED" }
            throw e
        }
    }

    private suspend fun pullFromLaqtaServer(profileId: Int) = serverSyncMutex.withLock {
        initialize()
        val authState = AuthRepository.state.value
        val server = ServerConfigurationRepository.active.value
        val localAddons = _uiState.value.addons
        val localSignature = localAddons.syncSignature()
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_addons", json.encodeToJsonElement(localAddons.toPushItems()))
            putSyncOriginClientId()
        }
        val rows = SupabaseProvider.client.postgrest
            .rpc("sync_pull_or_seed_addons", params)
            .decodeList<AddonRow>()
        if (currentProfileId != profileId || AuthRepository.state.value != authState ||
            ServerConfigurationRepository.active.value != server
        ) return@withLock
        if (!applyServerRows(rows, localSignature)) {
            log.i { "pullFromServer() — local addons changed during sync; preserving local changes" }
        }
    }

    private fun applyServerRows(
        rows: List<AddonRow>,
        expectedSignature: List<Triple<String, String?, Boolean>>? = null,
    ): Boolean {
        log.i { "pullFromServer() — server returned ${rows.size} addons" }
        var applied = false
        var existingByUrl = emptyMap<String, ManagedAddon>()
        var urls = emptyList<String>()
        _uiState.update { current ->
            val reconciled = reconcileAddonPull(current, rows, expectedSignature)
            applied = reconciled != null
            if (reconciled == null) {
                current
            } else {
                existingByUrl = current.addons.associateBy(ManagedAddon::manifestUrl)
                urls = reconciled.addons.map(ManagedAddon::manifestUrl)
                reconciled
            }
        }
        if (!applied) return false
        persist()
        urls.forEach { url ->
            val existing = existingByUrl[url]
            val addon = _uiState.value.addons.firstOrNull { it.manifestUrl == url }
            if (addon?.enabled == true && (existing == null || (addon.manifest == null && !addon.isRefreshing))) {
                refreshAddon(url)
            }
        }
        initialized = true
        log.i { "pullFromServer() — applied ${urls.size} addons to state" }
        return true
    }

    suspend fun awaitManifestsLoaded() {
        if (!_uiState.value.addons.hasPendingEnabledManifests()) return
        uiState.first { state ->
            !state.addons.hasPendingEnabledManifests()
        }
    }

    private val _pendingTrackingConsent = MutableStateFlow<AddonManifest?>(null)
    val pendingTrackingConsent: StateFlow<AddonManifest?> = _pendingTrackingConsent.asStateFlow()

    fun requestTrackingConsent(manifest: AddonManifest) {
        _pendingTrackingConsent.value = manifest
    }

    fun dismissTrackingConsent() {
        _pendingTrackingConsent.value = null
    }

    suspend fun installWithTrackingConsent(manifest: AddonManifest): AddAddonResult {
        _pendingTrackingConsent.value = null
        if (isUsingPrimaryAddonsFromSecondaryProfile()) {
            return AddAddonResult.Error(getString(Res.string.profile_primary_addons_required))
        }
        if (_uiState.value.addons.any { it.manifestUrl == manifest.transportUrl }) {
            return AddAddonResult.Error(getString(Res.string.addon_already_installed))
        }
        return installManifest(manifest.transportUrl, manifest)
    }

    suspend fun addAddon(rawUrl: String): AddAddonResult {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) {
            return AddAddonResult.Error(getString(Res.string.profile_primary_addons_required))
        }
        log.i { "addAddon() — rawUrl=$rawUrl" }
        val manifestUrl = try {
            normalizeManifestUrl(rawUrl)
        } catch (error: IllegalArgumentException) {
            return AddAddonResult.Error(error.message ?: getString(Res.string.addon_invalid_url))
        }

        if (_uiState.value.addons.any { it.manifestUrl == manifestUrl }) {
            return AddAddonResult.Error(getString(Res.string.addon_already_installed))
        }

        val manifest = try {
            withContext(Dispatchers.Default) {
                val payload = fetchAddonResponseText(manifestUrl)
                AddonManifestParser.parse(
                    manifestUrl = manifestUrl,
                    payload = payload,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return AddAddonResult.Error(error.message ?: getString(Res.string.addon_load_manifest_failed))
        }

        if (manifest.isTrackingAddon) {
            return AddAddonResult.TrackingConsentRequired(manifest)
        }
        return installManifest(manifestUrl, manifest)
    }

    private fun installManifest(manifestUrl: String, manifest: AddonManifest): AddAddonResult {
        _uiState.update { current ->
            current.copy(
                addons = current.addons + ManagedAddon(
                    manifestUrl = manifestUrl,
                    manifest = manifest,
                    isRefreshing = false,
                    errorMessage = null,
                ),
            )
        }
        persistAndPush()
        return AddAddonResult.Success(manifest)
    }

    fun removeAddon(manifestUrl: String) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        log.i { "removeAddon() — $manifestUrl" }
        var changed = false
        _uiState.update { current ->
            val updatedAddons = current.addons.filterNot { it.manifestUrl == manifestUrl }
            changed = updatedAddons.size != current.addons.size
            if (changed) current.copy(addons = updatedAddons) else current
        }
        if (!changed) return
        persistAndPush()
    }

    fun moveAddon(fromIndex: Int, toIndex: Int) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        var changed = false
        _uiState.update { current ->
            val addons = current.addons
            if (
                fromIndex !in addons.indices ||
                toIndex !in addons.indices ||
                fromIndex == toIndex
            ) {
                return@update current
            }

            val reordered = addons.toMutableList()
            val movingAddon = reordered.removeAt(fromIndex)
            reordered.add(toIndex, movingAddon)
            changed = true
            current.copy(addons = reordered)
        }
        if (!changed) return
        persistAndPush()
    }

    fun setAddonEnabled(manifestUrl: String, enabled: Boolean) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        var shouldRefresh = false
        var changed = false
        _uiState.update { current ->
            current.copy(
                addons = current.addons.map { addon ->
                    if (addon.manifestUrl != manifestUrl || addon.enabled == enabled) {
                        addon
                    } else {
                        changed = true
                        shouldRefresh = enabled && addon.manifest == null && !addon.isRefreshing
                        addon.copy(enabled = enabled)
                    }
                },
            )
        }
        if (!changed) return
        persistAndPush()
        if (shouldRefresh) {
            refreshAddon(manifestUrl)
        }
    }

    fun refreshAll() {
        _uiState.value.addons.filter { it.enabled }.distinctBy { it.manifestUrl }.forEach { addon ->
            refreshAddon(
                manifestUrl = addon.manifestUrl,
                forceRefresh = true,
            )
        }
    }

    fun refreshAddon(
        manifestUrl: String,
        forceRefresh: Boolean = false,
    ) {
        val existingJob = activeRefreshJobs[manifestUrl]
        if (existingJob?.isActive == true) return

        markRefreshing(manifestUrl)
        var refreshJob: Job? = null
        refreshJob = scope.launch {
            try {
                val result = runCatching {
                    val payload = fetchAddonResponseText(
                        url = manifestUrl,
                        forceRefresh = forceRefresh,
                    )
                    AddonManifestParser.parse(
                        manifestUrl = manifestUrl,
                        payload = payload,
                    )
                }

                _uiState.update { current ->
                    current.copy(
                        addons = current.addons.map { addon ->
                            if (addon.manifestUrl != manifestUrl) {
                                addon
                            } else {
                                result.fold(
                                    onSuccess = { manifest ->
                                        addon.copy(
                                            manifest = manifest,
                                            isRefreshing = false,
                                            errorMessage = null,
                                        )
                                    },
                                    onFailure = { error ->
                                        addon.copy(
                                            isRefreshing = false,
                                            errorMessage = error.message ?: getString(Res.string.addon_load_manifest_failed),
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            } finally {
                if (activeRefreshJobs[manifestUrl] === refreshJob) {
                    activeRefreshJobs.remove(manifestUrl)
                }
            }
        }
        activeRefreshJobs[manifestUrl] = refreshJob
    }

    fun retryPendingPush() {
        val profileId = currentProfileId
        val pending = pendingPushForCurrentScope(profileId) ?: return
        schedulePush(pending, debounceMs = 0L)
    }

    private fun persistAndPush() {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) {
            persist()
            return
        }
        val profileId = currentProfileId
        val server = ServerConfigurationRepository.active.value
        val authState = AuthRepository.state.value as? AuthState.Authenticated
        val pending = PendingAddonPush(
            userId = authState?.userId,
            backendUrl = server.backendUrl,
            addons = _uiState.value.addons.toPushItems(),
        )
        savePendingPush(profileId, pending)
        persist()
        schedulePush(pending)
    }

    private fun schedulePush(
        pending: PendingAddonPush,
        debounceMs: Long = ADDON_PUSH_DEBOUNCE_MS,
    ) {
        val profileId = currentProfileId
        if (pendingPushForCurrentScope(profileId) != pending) return
        pushJobsByProfile[profileId]?.cancel()
        var pushJob: Job? = null
        pushJob = scope.launch {
            updateSyncUiState(isSyncing = true, hasFailed = false)
            try {
                if (debounceMs > 0) delay(debounceMs)
                pushPending(profileId, pending)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.e(error) { "pushToServer() — FAILED" }
                if (profileId == currentProfileId) {
                    updateSyncUiState(isSyncing = false, hasFailed = true)
                }
            } finally {
                if (pushJobsByProfile[profileId] === pushJob) {
                    pushJobsByProfile.remove(profileId)
                    if (profileId == currentProfileId) {
                        updateSyncUiState(isSyncing = false)
                    }
                }
            }
        }
        pushJobsByProfile[profileId] = pushJob
    }

    private suspend fun retryPendingPush(profileId: Int) {
        if (profileId != currentProfileId) return
        pushJobsByProfile[profileId]?.join()
        val pending = pendingPushForCurrentScope(profileId) ?: run {
            if (profileId == currentProfileId) {
                updateSyncUiState(isSyncing = false, hasFailed = false)
            }
            return
        }
        if (profileId == currentProfileId) {
            updateSyncUiState(isSyncing = true, hasFailed = false)
        }
        try {
            pushPending(profileId, pending)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (profileId == currentProfileId) {
                updateSyncUiState(isSyncing = false, hasFailed = true)
            }
            throw error
        }
    }

    private suspend fun pushPending(profileId: Int, pending: PendingAddonPush) {
        if (profileId != currentProfileId || pendingPushForCurrentScope(profileId) != pending) return
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_addons", json.encodeToJsonElement(pending.addons))
            putSyncOriginClientId()
        }
        val pushed = serverSyncMutex.withLock {
            if (profileId != currentProfileId || pendingPushForCurrentScope(profileId) != pending) {
                false
            } else {
                SupabaseProvider.client.postgrest.rpc("sync_push_addons", params)
                true
            }
        }
        if (!pushed) return
        removePendingPush(profileId, pending)
        log.d { "pushToServer() — success" }
        if (profileId == currentProfileId) {
            updateSyncUiState(isSyncing = false, hasFailed = false)
        }
    }

    private fun pendingPushForCurrentScope(profileId: Int): PendingAddonPush? {
        val server = ServerConfigurationRepository.active.value
        val userId = (AuthRepository.state.value as? AuthState.Authenticated)?.userId
        return loadPendingPushes(profileId).firstOrNull {
            it.backendUrl == server.backendUrl && it.userId == userId
        }
    }

    private fun loadPendingPushes(profileId: Int): List<PendingAddonPush> =
        AddonStorage.loadPendingAddonPushes(profileId)
            ?.let { json.decodeFromString<List<PendingAddonPush>>(it) }
            .orEmpty()

    private fun savePendingPush(profileId: Int, pending: PendingAddonPush) {
        synchronized(pendingPushStorageLock) {
            val updated = replacePendingAddonPush(loadPendingPushes(profileId), pending)
            AddonStorage.savePendingAddonPushes(
                profileId,
                updated.takeIf { it.isNotEmpty() }?.let { json.encodeToString(it) },
            )
        }
        if (profileId == currentProfileId) {
            updateSyncUiState(isSyncing = false, hasFailed = false)
        }
    }

    private fun removePendingPush(profileId: Int, pending: PendingAddonPush) {
        synchronized(pendingPushStorageLock) {
            val updated = removePendingAddonPush(loadPendingPushes(profileId), pending)
            AddonStorage.savePendingAddonPushes(
                profileId,
                updated.takeIf { it.isNotEmpty() }?.let { json.encodeToString(it) },
            )
        }
    }

    private fun updateSyncUiState(
        isSyncing: Boolean? = null,
        hasFailed: Boolean? = null,
    ) {
        val hasPending = pendingPushForCurrentScope(currentProfileId) != null
        val previous = _syncUiState.value
        _syncUiState.value = AddonSyncUiState(
            hasPendingChanges = hasPending,
            isSyncing = isSyncing ?: previous.isSyncing,
            hasFailed = hasPending && (hasFailed ?: previous.hasFailed),
        )
    }

    private fun markRefreshing(manifestUrl: String) {
        _uiState.update { current ->
            current.copy(
                addons = current.addons.map { addon ->
                    if (addon.manifestUrl == manifestUrl) {
                        addon.copy(
                            isRefreshing = true,
                            errorMessage = null,
                        )
                    } else {
                        addon
                    }
                },
            )
        }
    }

    private fun persist() {
        val addons = _uiState.value.addons
        AddonStorage.saveInstalledAddonUrls(
            currentProfileId,
            dedupeManifestUrls(addons.map { it.manifestUrl }),
        )
        AddonStorage.saveAddonEnabledStates(
            currentProfileId,
            addons.associate { it.manifestUrl to it.enabled },
        )
    }

    private fun loadLocalEnabledStates(): Map<String, Boolean> =
        AddonStorage.loadAddonEnabledStates(currentProfileId)
            .mapKeys { (url, _) -> ensureManifestSuffix(url) }

    private fun cancelActiveRefreshes() {
        activeRefreshJobs.values.forEach(Job::cancel)
        activeRefreshJobs.clear()
    }

    private fun resolveEffectiveProfileId(profileId: Int): Int {
        val active = ProfileRepository.state.value.activeProfile
        return if (active != null && active.profileIndex != 1 && active.usesPrimaryAddons) 1 else profileId
    }

    private fun isUsingPrimaryAddonsFromSecondaryProfile(): Boolean {
        val active = ProfileRepository.state.value.activeProfile
        return active != null && active.profileIndex != 1 && active.usesPrimaryAddons
    }
}

internal fun replacePendingAddonPush(
    pendingPushes: List<PendingAddonPush>,
    replacement: PendingAddonPush,
): List<PendingAddonPush> =
    pendingPushes.filterNot {
        it.userId == replacement.userId && it.backendUrl == replacement.backendUrl
    } + replacement

internal fun removePendingAddonPush(
    pendingPushes: List<PendingAddonPush>,
    completed: PendingAddonPush,
): List<PendingAddonPush> =
    pendingPushes.filterNot {
        it.userId == completed.userId &&
            it.backendUrl == completed.backendUrl &&
            it.addons == completed.addons
    }

private fun List<ManagedAddon>.toPushItems(): List<AddonPushItem> =
    distinctBy { it.manifestUrl }.mapIndexed { index, addon ->
        AddonPushItem(
            url = addon.manifestUrl,
            name = addon.userSetName?.takeIf { it.isNotBlank() } ?: addon.manifest?.name ?: "",
            enabled = addon.enabled,
            sortOrder = index,
        )
    }

internal fun List<ManagedAddon>.syncSignature(): List<Triple<String, String?, Boolean>> =
    map { Triple(it.manifestUrl, it.userSetName, it.enabled) }

internal fun reconcileAddonPull(
    current: AddonsUiState,
    rows: List<AddonRow>,
    expectedSignature: List<Triple<String, String?, Boolean>>?,
): AddonsUiState? {
    if (expectedSignature != null && current.addons.syncSignature() != expectedSignature) return null
    val rowsByUrl = linkedMapOf<String, AddonRow>()
    rows.forEach { row ->
        val url = ensureManifestSuffix(row.url)
        if (url !in rowsByUrl) rowsByUrl[url] = row
    }
    val existingByUrl = current.addons.associateBy(ManagedAddon::manifestUrl)
    return AddonsUiState(
        addons = rowsByUrl.map { (url, row) ->
            existingByUrl[url].toPendingAddon(
                manifestUrl = url,
                userSetName = row.name?.takeIf { it.isNotBlank() },
                enabled = row.enabled,
            )
        },
    )
}

private fun ManagedAddon?.toPendingAddon(
    manifestUrl: String,
    userSetName: String? = null,
    enabled: Boolean? = null,
): ManagedAddon =
    when {
        this == null -> ManagedAddon(
            manifestUrl = manifestUrl,
            isRefreshing = enabled ?: true,
            userSetName = userSetName,
            enabled = enabled ?: true,
        )
        manifest != null -> copy(
            manifestUrl = manifestUrl,
            isRefreshing = false,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
        isRefreshing -> copy(
            manifestUrl = manifestUrl,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
        else -> copy(
            manifestUrl = manifestUrl,
            isRefreshing = enabled ?: this.enabled,
            errorMessage = null,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
    }

private fun dedupeManifestUrls(urls: List<String>): List<String> =
    urls.map(::ensureManifestSuffix).distinct()

private fun ensureManifestSuffix(url: String): String {
    val path = url.substringBefore("?").trimEnd('/')
    val query = url.substringAfter("?", "")
    val withSuffix = if (path.endsWith("/manifest.json")) path else "$path/manifest.json"
    return if (query.isEmpty()) withSuffix else "$withSuffix?$query"
}

private fun normalizeManifestUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    require(trimmed.isNotEmpty()) { runBlocking { getString(Res.string.addons_error_enter_url) } }

    val normalizedScheme = when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("stremio://") -> "https://${trimmed.removePrefix("stremio://")}"
        else -> "https://$trimmed"
    }

    val withoutFragment = normalizedScheme.substringBefore("#")
    val query = withoutFragment.substringAfter("?", "")
    val path = withoutFragment.substringBefore("?").trimEnd('/')
    val manifestPath = if (path.endsWith("/manifest.json")) {
        path
    } else {
        "$path/manifest.json"
    }

    return if (query.isEmpty()) manifestPath else "$manifestPath?$query"
}
