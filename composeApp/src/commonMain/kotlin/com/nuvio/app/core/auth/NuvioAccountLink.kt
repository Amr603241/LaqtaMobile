package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.libraryItemKey
import com.nuvio.app.features.library.sync.LibrarySyncItem
import com.nuvio.app.features.library.sync.collectOffsetPages
import com.nuvio.app.features.library.sync.forEachMutationBatch
import com.nuvio.app.features.library.sync.libraryMutationBatchSize
import com.nuvio.app.features.library.sync.librarySnapshotPageSize
import com.nuvio.app.features.library.sync.toLibraryItem
import com.nuvio.app.features.library.sync.toSyncItem
import com.nuvio.app.features.servers.ServerItemRef
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

internal data class NuvioLinkOwner(val userId: String, val serverUrl: String)

internal sealed interface NuvioLinkState {
    data object Disconnected : NuvioLinkState
    data class Connected(val email: String) : NuvioLinkState
}

internal interface NuvioLinkSession {
    suspend fun signIn(email: String, password: String): String
    suspend fun signOut()
    suspend fun close()
    suspend fun pullProfiles(): List<NuvioRemoteProfile>
    suspend fun pullLibrary(profileId: Int): List<LibraryItem>
    suspend fun pushLibrary(profileId: Int, items: List<LibraryItem>)
}

@Serializable
internal data class NuvioRemoteProfile(
    @SerialName("profile_index") val index: Int,
    val name: String,
    @SerialName("pin_enabled") val pinEnabled: Boolean,
)

private val nuvioJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun decodeNuvioProfiles(body: String): List<NuvioRemoteProfile> =
    nuvioJson.decodeFromString(body)

internal data class NuvioLibrarySyncResult(val imported: Int, val exported: Int)

internal class NuvioLinkController(
    private val currentOwner: () -> NuvioLinkOwner?,
    private val createSession: () -> NuvioLinkSession,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<NuvioLinkState>(NuvioLinkState.Disconnected)
    val state: StateFlow<NuvioLinkState> = _state.asStateFlow()
    private var session: NuvioLinkSession? = null
    private var owner: NuvioLinkOwner? = null

    suspend fun profiles(): List<NuvioRemoteProfile> = mutex.withLock {
        val active = checkNotNull(session) { "Nuvio is not connected" }
        check(owner == currentOwner()) { "The Laqta account changed" }
        val profiles = active.pullProfiles()
        check(owner == currentOwner()) { "The Laqta account changed" }
        profiles.sortedBy { it.index }
    }

    suspend fun syncLibrary(
        profileId: Int,
        localItems: () -> List<LibraryItem>,
        checkLocal: () -> Boolean,
        importItems: (List<LibraryItem>) -> Int,
    ): NuvioLibrarySyncResult = mutex.withLock {
        val active = checkNotNull(session) { "Nuvio is not connected" }
        check(owner == currentOwner()) { "The Laqta account changed" }
        val profile = active.pullProfiles().singleOrNull { it.index == profileId }
        check(profile != null && !profile.pinEnabled) { "Nuvio profile is unavailable or PIN-protected" }
        check(owner == currentOwner() && checkLocal()) { "Laqta account or library changed" }
        val remote = active.pullLibrary(profileId)
        check(owner == currentOwner() && checkLocal()) { "Laqta account or library changed" }
        val remoteKeys = remote.mapTo(HashSet()) { libraryItemKey(it.id, it.type) }
        val toExport = localItems().filter { libraryItemKey(it.id, it.type) !in remoteKeys }
        if (toExport.isNotEmpty()) active.pushLibrary(profileId, toExport)
        check(owner == currentOwner() && checkLocal()) { "Laqta account or library changed" }
        NuvioLibrarySyncResult(imported = importItems(remote), exported = toExport.size)
    }

    suspend fun connect(email: String, password: String) = mutex.withLock {
        require(email.isNotBlank() && password.isNotEmpty())
        val expectedOwner = checkNotNull(currentOwner()) { "A Laqta account is required" }
        check(session == null) { "A Nuvio account is already connected" }
        val candidate = createSession()
        var accepted = false
        var signedIn = false
        try {
            val connectedEmail = candidate.signIn(email.trim(), password)
            signedIn = true
            check(currentOwner() == expectedOwner) { "The Laqta account changed while connecting" }
            session = candidate
            owner = expectedOwner
            _state.value = NuvioLinkState.Connected(connectedEmail)
            accepted = true
        } finally {
            if (!accepted) {
                try {
                    if (signedIn) candidate.signOut()
                } finally {
                    candidate.close()
                }
            }
        }
    }

    suspend fun disconnect() = mutex.withLock {
        disconnectLocked()
    }

    suspend fun disconnectIfOwnerChanged() = mutex.withLock {
        if (owner != null && owner != currentOwner()) disconnectLocked()
    }

    private suspend fun disconnectLocked() {
        val previous = session ?: return
        session = null
        owner = null
        _state.value = NuvioLinkState.Disconnected
        try {
            previous.signOut()
        } finally {
            previous.close()
        }
    }
}

internal object NuvioAccountLink {
    private val log = Logger.withTag("NuvioAccountLink")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controller = NuvioLinkController(
        currentOwner = ::currentOwner,
        createSession = ::SupabaseNuvioLinkSession,
    )
    val state = controller.state

    init {
        scope.launch {
            combine(AuthRepository.state, ServerConfigurationRepository.active) { _, _ -> Unit }
                .collect {
                    try {
                        controller.disconnectIfOwnerChanged()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        log.e { "Failed to revoke the Nuvio session after account change: ${error::class.simpleName}" }
                    }
                }
        }
    }

    suspend fun connect(email: String, password: String) = controller.connect(email, password)

    suspend fun disconnect() = controller.disconnect()

    suspend fun profiles(): List<NuvioRemoteProfile> = controller.profiles()

    suspend fun syncLibrary(nuvioProfileId: Int): NuvioLibrarySyncResult {
        val snapshot = LibraryRepository.nuvioSyncSnapshot()
        return controller.syncLibrary(
            profileId = nuvioProfileId,
            localItems = { snapshot.items.filterNot { ServerItemRef.isServerId(it.id) } },
            checkLocal = { LibraryRepository.isNuvioSyncSnapshotCurrent(snapshot) },
            importItems = { LibraryRepository.importMissingItems(snapshot, it) },
        )
    }

    private fun currentOwner(): NuvioLinkOwner? {
        val account = AuthRepository.state.value as? AuthState.Authenticated ?: return null
        if (account.isAnonymous || account.userId.isBlank()) return null
        return NuvioLinkOwner(account.userId, ServerConfigurationRepository.active.value.backendUrl)
    }
}

private class SupabaseNuvioLinkSession : NuvioLinkSession {
    // Public client configuration published by Nuvio's self-host account importer.
    private val client: SupabaseClient = createSupabaseClient(
        supabaseUrl = "https://api.nuvio.tv",
        supabaseKey = "sb_publishable_1Clq8rlTVACkdcZuqr6_AD__xUUC_EN",
    ) {
        install(Auth) {
            sessionManager = MemorySessionManager()
            autoLoadFromStorage = false
            autoSaveToStorage = false
        }
        install(Postgrest)
    }

    override suspend fun signIn(email: String, password: String): String {
        client.auth.awaitInitialization()
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        val user = checkNotNull(client.auth.currentUserOrNull()) { "Nuvio did not return an authenticated user" }
        return user.email ?: email
    }

    override suspend fun signOut() {
        client.auth.signOut()
    }

    override suspend fun close() {
        client.close()
    }

    override suspend fun pullProfiles(): List<NuvioRemoteProfile> =
        decodeNuvioProfiles(client.postgrest.rpc("sync_pull_profiles").data)

    override suspend fun pullLibrary(profileId: Int): List<LibraryItem> =
        collectOffsetPages(librarySnapshotPageSize) { limit, offset ->
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_limit", limit)
                put("p_offset", offset)
            }
            nuvioJson.decodeFromString<List<LibrarySyncItem>>(client.postgrest.rpc("sync_pull_library", params).data)
        }.map(LibrarySyncItem::toLibraryItem)

    override suspend fun pushLibrary(profileId: Int, items: List<LibraryItem>) {
        forEachMutationBatch(items, libraryMutationBatchSize) { batch ->
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_items", nuvioJson.encodeToJsonElement(batch.map(LibraryItem::toSyncItem)))
            }
            client.postgrest.rpc("sync_push_library_items", params)
        }
    }
}
