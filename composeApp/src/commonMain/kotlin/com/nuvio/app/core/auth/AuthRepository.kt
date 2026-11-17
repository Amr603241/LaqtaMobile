package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.storage.LocalAccountDataCleaner
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ktor.http.Url
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

private const val KEY_HAD_ACCOUNT_SESSION = "had_account_session"
private const val KEY_SIGNED_OUT_NOTICE = "signed_out_notice"
private const val GOOGLE_AUTH_REDIRECT_URL = "laqta://auth/callback"

enum class GoogleLinkState {
    Idle,
    Starting,
    WaitingForCallback,
    Linked,
}

object AuthRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AuthRepository")

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _googleLinkState = MutableStateFlow(GoogleLinkState.Idle)
    val googleLinkState: StateFlow<GoogleLinkState> = _googleLinkState.asStateFlow()

    private var initialized = false
    private var sessionStatusJob: Job? = null
    private var validatedRemoteUserId: String? = null
    private var pendingGoogleLink = false
    private val refreshMutex = Mutex()

    fun initialize() {
        if (initialized) return
        initialized = true

        val savedAnonId = AuthStorage.loadAnonymousUserId()
        if (savedAnonId != null) {
            _state.value = AuthState.Authenticated(
                userId = savedAnonId,
                email = null,
                isAnonymous = true,
            )
        }

        if (!ServerConfigurationRepository.active.value.hasEmailPasswordAuth) {
            if (savedAnonId == null) _state.value = AuthState.Unauthenticated
            return
        }

        sessionStatusJob = scope.launch {
            if (AuthStorage.loadFlag(KEY_SIGNED_OUT_NOTICE)) {
                _error.value = getString(Res.string.auth_notice_signed_out)
            }
            SupabaseProvider.client.auth.sessionStatus.collect { status ->
                if (AuthStorage.loadAnonymousUserId() != null) return@collect
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val user = status.session.user
                        val userId = user?.id.orEmpty()
                        if (!validateRemoteSession(userId)) return@collect
                        AuthStorage.saveFlag(KEY_HAD_ACCOUNT_SESSION, true)
                        AuthStorage.saveFlag(KEY_SIGNED_OUT_NOTICE, false)
                        _state.value = AuthState.Authenticated(
                            userId = userId,
                            email = user?.email,
                            isAnonymous = false,
                        )
                    }
                    is SessionStatus.NotAuthenticated -> {
                        if (AuthStorage.loadFlag(KEY_HAD_ACCOUNT_SESSION)) {
                            resetAfterLostSession()
                        } else {
                            _state.value = AuthState.Unauthenticated
                        }
                    }
                    is SessionStatus.Initializing -> {
                        if (AuthStorage.loadAnonymousUserId() == null) {
                            _state.value = AuthState.Loading
                        }
                    }
                    is SessionStatus.RefreshFailure -> Unit
                }
            }
        }
    }

    private suspend fun validateRemoteSession(userId: String): Boolean {
        if (userId.isBlank() || validatedRemoteUserId == userId) return true

        return runCatching {
            SupabaseProvider.client.auth.retrieveUserForCurrentSession(false)
            validatedRemoteUserId = userId
            true
        }.getOrElse { e ->
            val invalid = if (e.isJwtExpiredError()) refreshRejected() else e.isInvalidSessionError()
            if (invalid) {
                log.w(e) { "Stored Supabase session no longer belongs to an active account; clearing local auth" }
                clearLocalSessionAfterRemoteInvalidation()
                false
            } else {
                log.w(e) { "Unable to validate stored Supabase session; keeping cached auth state" }
                true
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun signInAnonymously() {
        _error.value = null
        val userId = Uuid.random().toString()
        AuthStorage.saveAnonymousUserId(userId)
        _state.value = AuthState.Authenticated(
            userId = userId,
            email = null,
            isAnonymous = true,
        )
    }

    suspend fun signUpWithEmail(email: String, password: String): Result<Unit> {
        _error.value = null
        val validationFailure = validateEmailPassword(email, password)
        if (validationFailure != null) return validationFailure
        return runCatching {
            SupabaseProvider.client.auth.signUpWith(Email) {
                this.email = email.trim()
                this.password = password
            }
            Unit
        }.onFailure { e ->
            if (e is CancellationException) throw e
            log.e(e) { "Email sign-up failed" }
            _error.value = e.safeAuthErrorDescription()
                ?: getString(Res.string.auth_sign_up_failed)
        }
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> {
        _error.value = null
        val validationFailure = validateEmailPassword(email, password)
        if (validationFailure != null) return validationFailure
        return runCatching {
            SupabaseProvider.client.auth.signInWith(Email) {
                this.email = email.trim()
                this.password = password
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            log.e(e) { "Email sign-in failed" }
            _error.value = e.safeAuthErrorDescription()
                ?: getString(Res.string.auth_sign_in_failed)
        }
    }

    suspend fun signInWithGoogle(): Result<Unit> {
        _error.value = null
        if (!ServerConfigurationRepository.active.value.hasGoogleOAuth) {
            val message = getString(Res.string.auth_google_unavailable)
            _error.value = message
            return Result.failure(IllegalStateException(message))
        }
        return runCatching {
            SupabaseProvider.client.auth.signInWith(
                provider = Google,
                redirectUrl = GOOGLE_AUTH_REDIRECT_URL,
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.e(error) { "Google sign-in failed to start" }
            _error.value = error.safeAuthErrorDescription()
                ?: getString(Res.string.auth_google_failed)
        }
    }

    suspend fun linkGoogleIdentity(): Result<Unit> {
        _error.value = null
        val authState = state.value
        val message = when {
            !ServerConfigurationRepository.active.value.hasGoogleOAuth ->
                getString(Res.string.auth_google_unavailable)
            authState !is AuthState.Authenticated || authState.isAnonymous ->
                getString(Res.string.auth_google_link_requires_account)
            else -> null
        }
        if (message != null) {
            _error.value = message
            _googleLinkState.value = GoogleLinkState.Idle
            return Result.failure(IllegalStateException(message))
        }

        pendingGoogleLink = true
        _googleLinkState.value = GoogleLinkState.Starting
        return runCatching {
            SupabaseProvider.client.auth.linkIdentity(
                provider = Google,
                redirectUrl = GOOGLE_AUTH_REDIRECT_URL,
            )
            _googleLinkState.value = GoogleLinkState.WaitingForCallback
        }.onFailure { error ->
            if (error is CancellationException) throw error
            pendingGoogleLink = false
            _googleLinkState.value = GoogleLinkState.Idle
            log.e(error) { "Google identity linking failed to start" }
            _error.value = error.safeAuthErrorDescription()
                ?: getString(Res.string.auth_google_failed)
        }
    }

    fun handleAuthCallback(url: String): Boolean {
        val parsedUrl = runCatching { Url(url) }.getOrNull() ?: return false
        val path = parsedUrl.pathSegments.map(String::trim).filter(String::isNotBlank)
        if (
            parsedUrl.protocol.name != "laqta" ||
            parsedUrl.host != "auth" ||
            path != listOf("callback")
        ) {
            return false
        }

        val callbackError = parsedUrl.parameters["error_description"]
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: parsedUrl.parameters["error"]
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        val code = parsedUrl.parameters["code"]
            ?.trim()
            ?.takeIf(String::isNotEmpty)

        scope.launch {
            try {
                if (callbackError != null) {
                    throw IllegalStateException(callbackError)
                }
                val authorizationCode = code
                    ?: throw IllegalStateException("OAuth callback did not include an authorization code")
                SupabaseProvider.client.auth.exchangeCodeForSession(authorizationCode)
                _error.value = null
                if (pendingGoogleLink) {
                    pendingGoogleLink = false
                    _googleLinkState.value = GoogleLinkState.Linked
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                pendingGoogleLink = false
                _googleLinkState.value = GoogleLinkState.Idle
                log.e(error) { "Supabase auth callback failed" }
                _error.value = callbackError
                    ?: error.safeAuthErrorDescription()
                    ?: getString(Res.string.auth_google_failed)
            }
        }
        return true
    }

    private suspend fun validateEmailPassword(email: String, password: String): Result<Unit>? {
        val configuration = ServerConfigurationRepository.active.value
        val failureMessage = when {
            !configuration.hasEmailPasswordAuth ->
                getString(Res.string.auth_backend_not_configured)
            email.isBlank() ->
                getString(Res.string.auth_email_required)
            password.length < 6 ->
                getString(Res.string.auth_password_too_short)
            else -> return null
        }
        _error.value = failureMessage
        return Result.failure(IllegalArgumentException(failureMessage))
    }

    suspend fun signOut(): Result<Unit> {
        _error.value = null
        val anonymousRead = runCatching { AuthStorage.loadAnonymousUserId() }
        val wasAnonymous = anonymousRead.getOrNull() != null
        val anonymousClear = runCatching { AuthStorage.clearAnonymousUserId() }
        AuthStorage.saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
        AuthStorage.saveFlag(KEY_SIGNED_OUT_NOTICE, false)
        validatedRemoteUserId = null
        pendingGoogleLink = false
        _googleLinkState.value = GoogleLinkState.Idle
        val remoteSignOut = if (wasAnonymous || !ServerConfigurationRepository.active.value.hasEmailPasswordAuth) {
            Result.success(Unit)
        } else {
            runCatching { SupabaseProvider.client.auth.signOut() }
        }

        val fallbackSessionClear = if (remoteSignOut.isFailure) {
            runCatching { SupabaseProvider.client.auth.clearSession() }
                .onFailure { error -> log.w(error) { "Failed to clear Supabase session after sign-out failure" } }
        } else {
            Result.success(Unit)
        }
        val localCleanup = runCatching { LocalAccountDataCleaner.wipe() }
        _state.value = AuthState.Unauthenticated

        val failure = anonymousRead.exceptionOrNull()
            ?: anonymousClear.exceptionOrNull()
            ?: remoteSignOut.exceptionOrNull()
            ?: fallbackSessionClear.exceptionOrNull()
            ?: localCleanup.exceptionOrNull()
        val cancellation = remoteSignOut.exceptionOrNull() as? CancellationException
            ?: fallbackSessionClear.exceptionOrNull() as? CancellationException
        if (cancellation != null) throw cancellation
        return if (failure == null) {
            Result.success(Unit)
        } else {
            log.e(failure) { "Sign-out did not complete cleanly; all local cleanup steps were attempted" }
            _error.value = failure.message ?: runCatching {
                getString(Res.string.auth_sign_out_failed)
            }.getOrDefault("Sign out failed")
            Result.failure(failure)
        }
    }

    suspend fun prepareForServerSwitch(): Result<Unit> {
        _error.value = null
        val anonymousClear = runCatching { AuthStorage.clearAnonymousUserId() }
        AuthStorage.saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
        validatedRemoteUserId = null
        pendingGoogleLink = false
        _googleLinkState.value = GoogleLinkState.Idle
        val sessionClear = if (ServerConfigurationRepository.active.value.hasEmailPasswordAuth) {
            runCatching { SupabaseProvider.client.auth.clearSession() }
        } else {
            Result.success(Unit)
        }
        _state.value = AuthState.Unauthenticated
        val failure = anonymousClear.exceptionOrNull() ?: sessionClear.exceptionOrNull()
        val cancellation = sessionClear.exceptionOrNull() as? CancellationException
        if (cancellation != null) throw cancellation
        return if (failure == null) Result.success(Unit) else Result.failure(failure)
    }

    fun reinitialize() {
        sessionStatusJob?.cancel()
        sessionStatusJob = null
        initialized = false
        validatedRemoteUserId = null
        _state.value = AuthState.Loading
        initialize()
    }

    suspend fun signOutIfSessionInvalid(error: Throwable, source: String): Boolean {
        val invalid = if (error.isJwtExpiredError()) refreshRejected() else error.isDeletedAccountError()
        if (!invalid) return false

        log.w(error) { "$source failed because the current Supabase account/session is no longer valid; clearing local auth" }
        clearLocalSessionAfterRemoteInvalidation()
        return true
    }

    private suspend fun refreshRejected(): Boolean = refreshMutex.withLock {
        try {
            SupabaseProvider.client.auth.refreshCurrentSession()
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.w(e) { "Supabase session refresh failed" }
            e.isInvalidRefreshError()
        }
    }

    private suspend fun clearLocalSessionAfterRemoteInvalidation() {
        AuthStorage.saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
        runCatching {
            SupabaseProvider.client.auth.clearSession()
        }.onFailure { e ->
            log.w(e) { "Failed to clear Supabase session after remote invalidation; continuing local reset" }
        }
        resetAfterLostSession()
    }

    private suspend fun resetAfterLostSession() {
        val notice = runCatching { getString(Res.string.auth_notice_signed_out) }.getOrNull()
        AuthStorage.clearAnonymousUserId()
        validatedRemoteUserId = null
        val localCleanup = runCatching { LocalAccountDataCleaner.wipe() }
        AuthStorage.saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
        AuthStorage.saveFlag(KEY_SIGNED_OUT_NOTICE, true)
        _error.value = notice
        _state.value = AuthState.Unauthenticated
        localCleanup.onFailure { error ->
            log.e(error) { "Local account cleanup failed after losing the session" }
        }
    }

    suspend fun deleteAccount(): Result<Unit> = runCatching {
        _error.value = null
        SupabaseProvider.client.functions.invoke("delete-account")
        AuthStorage.saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
        SupabaseProvider.client.auth.signOut()
        validatedRemoteUserId = null
        try {
            LocalAccountDataCleaner.wipe()
        } finally {
            _state.value = AuthState.Unauthenticated
        }
    }.onFailure { e ->
        log.e(e) { "Account deletion failed" }
        _error.value = e.message ?: getString(Res.string.auth_account_deletion_failed)
    }

    fun clearError() {
        _error.value = null
    }

    private fun Throwable.safeAuthErrorDescription(): String? =
        findCause<AuthRestException>()
            ?.errorDescription
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: findCause<RestException>()
                ?.description
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
}
