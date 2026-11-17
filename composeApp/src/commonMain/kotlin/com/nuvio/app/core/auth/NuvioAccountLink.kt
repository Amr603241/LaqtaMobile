package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.ServerConfigurationRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
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

internal data class NuvioLinkOwner(val userId: String, val serverUrl: String)

internal sealed interface NuvioLinkState {
    data object Disconnected : NuvioLinkState
    data class Connected(val email: String) : NuvioLinkState
}

internal interface NuvioLinkSession {
    suspend fun signIn(email: String, password: String): String
    suspend fun signOut()
    suspend fun close()
}

internal class NuvioLinkController(
    private val currentOwner: () -> NuvioLinkOwner?,
    private val createSession: () -> NuvioLinkSession,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<NuvioLinkState>(NuvioLinkState.Disconnected)
    val state: StateFlow<NuvioLinkState> = _state.asStateFlow()
    private var session: NuvioLinkSession? = null
    private var owner: NuvioLinkOwner? = null

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
}
