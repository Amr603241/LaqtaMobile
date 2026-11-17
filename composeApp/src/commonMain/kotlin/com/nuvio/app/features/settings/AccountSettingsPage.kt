package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.GoogleLinkState
import com.nuvio.app.core.auth.NuvioAccountLink
import com.nuvio.app.core.auth.NuvioLinkState
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.compose_settings_page_account
import nuvio.composeapp.generated.resources.auth_account_deletion_failed
import nuvio.composeapp.generated.resources.compose_auth_email
import nuvio.composeapp.generated.resources.compose_auth_password
import nuvio.composeapp.generated.resources.nuvio_link_connect
import nuvio.composeapp.generated.resources.nuvio_link_connected
import nuvio.composeapp.generated.resources.nuvio_link_description
import nuvio.composeapp.generated.resources.nuvio_link_disconnect
import nuvio.composeapp.generated.resources.nuvio_link_error
import nuvio.composeapp.generated.resources.nuvio_link_title
import nuvio.composeapp.generated.resources.settings_account_delete_account
import nuvio.composeapp.generated.resources.settings_account_delete_account_description
import nuvio.composeapp.generated.resources.settings_account_delete_confirm_message
import nuvio.composeapp.generated.resources.settings_account_delete_confirm_title
import nuvio.composeapp.generated.resources.settings_account_email
import nuvio.composeapp.generated.resources.settings_account_google_linked
import nuvio.composeapp.generated.resources.settings_account_google_link_waiting
import nuvio.composeapp.generated.resources.settings_account_link_google
import nuvio.composeapp.generated.resources.settings_account_not_signed_in
import nuvio.composeapp.generated.resources.settings_account_sign_out
import nuvio.composeapp.generated.resources.settings_account_sign_out_confirm_message
import nuvio.composeapp.generated.resources.settings_account_sign_out_confirm_title
import nuvio.composeapp.generated.resources.settings_account_status
import nuvio.composeapp.generated.resources.settings_account_status_anonymous
import nuvio.composeapp.generated.resources.settings_account_status_signed_in
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.accountSettingsContent(
    isTablet: Boolean,
) {
    item {
        AccountSettingsBody(isTablet = isTablet)
    }
}

@Composable
private fun AccountSettingsBody(
    isTablet: Boolean,
) {
    val authState by AuthRepository.state.collectAsStateWithLifecycle()
    val serverConfiguration by ServerConfigurationRepository.active.collectAsStateWithLifecycle()
    val googleLinkState by AuthRepository.googleLinkState.collectAsStateWithLifecycle()
    val authError by AuthRepository.error.collectAsStateWithLifecycle()
    val authenticatedAccount = (authState as? AuthState.Authenticated)
        ?.takeUnless { it.isAnonymous }
    val scope = rememberCoroutineScope()
    var showSignOutConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isDeletingAccount by remember { mutableStateOf(false) }
    var isStartingGoogleLink by remember { mutableStateOf(false) }
    var deleteErrorMessage by remember { mutableStateOf<String?>(null) }
    val deleteAccountFallbackMessage = stringResource(Res.string.auth_account_deletion_failed)
    val canDeleteAccount = AppFeaturePolicy.accountDeletionEnabled && authState is AuthState.Authenticated

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NuvioSurfaceCard {
            Text(
                text = stringResource(Res.string.compose_settings_page_account),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(14.dp))

            when (val state = authState) {
                is AuthState.Authenticated -> {
                    AccountInfoRow(
                        label = stringResource(Res.string.settings_account_status),
                        value = if (state.isAnonymous) {
                            stringResource(Res.string.settings_account_status_anonymous)
                        } else {
                            stringResource(Res.string.settings_account_status_signed_in)
                        },
                        valueColor = MaterialTheme.colorScheme.primary,
                    )
                    state.email?.takeUnless { state.isAnonymous }?.let { email ->
                        Spacer(modifier = Modifier.height(8.dp))
                        AccountInfoRow(
                            label = stringResource(Res.string.settings_account_email),
                            value = email,
                        )
                    }
                }
                else -> {
                    Text(
                        text = stringResource(Res.string.settings_account_not_signed_in),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (
            authenticatedAccount != null &&
            serverConfiguration.hasGoogleOAuth
        ) {
            when (googleLinkState) {
                GoogleLinkState.Linked -> Text(
                    text = stringResource(Res.string.settings_account_google_linked),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                GoogleLinkState.Starting,
                GoogleLinkState.WaitingForCallback -> Text(
                    text = stringResource(Res.string.settings_account_google_link_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GoogleLinkState.Idle -> Unit
            }
            if (googleLinkState != GoogleLinkState.Linked) {
                NuvioPrimaryButton(
                    text = stringResource(Res.string.settings_account_link_google),
                    onClick = {
                        if (isStartingGoogleLink) return@NuvioPrimaryButton
                        AuthRepository.clearError()
                        isStartingGoogleLink = true
                        scope.launch {
                            try {
                                AuthRepository.linkGoogleIdentity()
                            } finally {
                                isStartingGoogleLink = false
                            }
                        }
                    },
                )
            }
            authError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (authenticatedAccount != null && !serverConfiguration.isCustom) {
            NuvioAccountLinkCard()
        }

        NuvioPrimaryButton(
            text = stringResource(Res.string.settings_account_sign_out),
            onClick = { showSignOutConfirm = true },
        )

        if (canDeleteAccount) {
            DeleteAccountCard(
                errorMessage = deleteErrorMessage,
                onDeleteClick = {
                    deleteErrorMessage = null
                    showDeleteConfirm = true
                },
            )
        }
    }

    NuvioStatusModal(
        title = stringResource(Res.string.settings_account_sign_out_confirm_title),
        message = stringResource(Res.string.settings_account_sign_out_confirm_message),
        isVisible = showSignOutConfirm,
        confirmText = stringResource(Res.string.settings_account_sign_out),
        dismissText = stringResource(Res.string.action_cancel),
        onConfirm = {
            showSignOutConfirm = false
            scope.launch { AuthRepository.signOut() }
        },
        onDismiss = { showSignOutConfirm = false },
    )

    NuvioStatusModal(
        title = stringResource(Res.string.settings_account_delete_confirm_title),
        message = stringResource(Res.string.settings_account_delete_confirm_message),
        isVisible = showDeleteConfirm,
        isBusy = isDeletingAccount,
        destructive = true,
        confirmText = stringResource(Res.string.settings_account_delete_account),
        dismissText = stringResource(Res.string.action_cancel),
        onConfirm = {
            if (isDeletingAccount) return@NuvioStatusModal
            isDeletingAccount = true
            scope.launch {
                val result = AuthRepository.deleteAccount()
                isDeletingAccount = false
                showDeleteConfirm = false
                deleteErrorMessage = if (result.isSuccess) {
                    null
                } else {
                    AuthRepository.error.value
                        ?: result.exceptionOrNull()?.message
                        ?: deleteAccountFallbackMessage
                }
            }
        },
        onDismiss = {
            if (!isDeletingAccount) {
                showDeleteConfirm = false
            }
        },
    )
}

@Composable
private fun NuvioAccountLinkCard() {
    val state by NuvioAccountLink.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    NuvioSurfaceCard {
        Text(
            text = stringResource(Res.string.nuvio_link_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.nuvio_link_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        when (val linked = state) {
            is NuvioLinkState.Connected -> {
                Text(stringResource(Res.string.nuvio_link_connected, linked.email))
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        failed = false
                        scope.launch {
                            try {
                                NuvioAccountLink.disconnect()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                failed = true
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(stringResource(Res.string.nuvio_link_disconnect)) }
            }
            NuvioLinkState.Disconnected -> {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; failed = false },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    singleLine = true,
                    label = { Text(stringResource(Res.string.compose_auth_email)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                Spacer(modifier = Modifier.height(8.dp))
                SettingsSecretTextField(
                    value = password,
                    onValueChange = { password = it; failed = false },
                    label = stringResource(Res.string.compose_auth_password),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    enabled = !busy && email.isNotBlank() && password.isNotEmpty(),
                    onClick = {
                        busy = true
                        failed = false
                        scope.launch {
                            try {
                                NuvioAccountLink.connect(email, password)
                                password = ""
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                failed = true
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(stringResource(Res.string.nuvio_link_connect)) }
            }
        }
        if (failed) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.nuvio_link_error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DeleteAccountCard(
    errorMessage: String?,
    onDeleteClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio

    NuvioSurfaceCard {
        Text(
            text = stringResource(Res.string.settings_account_delete_account),
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.settings_account_delete_account_description),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
        errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.danger,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Button(
            onClick = onDeleteClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(NuvioTokens.Space.s48 + NuvioTokens.Space.s4),
            shape = tokens.shapes.button,
            colors = ButtonDefaults.buttonColors(
                containerColor = tokens.colors.danger,
                contentColor = tokens.colors.textInverse,
            ),
        ) {
            Text(
                text = stringResource(Res.string.settings_account_delete_account),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AccountInfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = valueColor,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
