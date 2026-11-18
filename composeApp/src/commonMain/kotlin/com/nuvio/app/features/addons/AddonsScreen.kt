package com.nuvio.app.features.addons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppFeaturePolicy
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioIconActionButton
import com.nuvio.app.core.ui.NuvioInfoBadge
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioSurfaceCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
fun AddonsScreen(
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
) {
    NuvioScreen(modifier = modifier) {
        stickyHeader {
            NuvioScreenHeader(
                title = title ?: stringResource(Res.string.addon_title),
                onBack = onBack,
            ) {
            }
        }
        item {
            AddonsSettingsPageContent()
        }
    }
}

@Composable
internal fun AddonsSettingsPageContent(
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        AddonRepository.initialize()
    }

    val uiState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val syncUiState by AddonRepository.syncUiState.collectAsStateWithLifecycle()
    val pendingTrackingConsent by AddonRepository.pendingTrackingConsent.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    var addonUrl by rememberSaveable { mutableStateOf("") }
    var formMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var installModalState by remember { mutableStateOf<AddonInstallModalState?>(null) }
    var addonPendingDeletionUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var directoryState by remember { mutableStateOf(AddonDirectoryUiState()) }
    var directoryLoaded by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedFilter by rememberSaveable { mutableStateOf(AddonDirectoryFilter.ALL) }
    var includeAdultAddons by rememberSaveable { mutableStateOf(false) }
    var visibleAddonCount by rememberSaveable { mutableIntStateOf(30) }
    val enterAddonUrlMessage = stringResource(Res.string.addons_error_enter_url)
    val configureErrorMessage = stringResource(Res.string.addons_browse_configure_error)
    val usePersonalMediaCopy = AppFeaturePolicy.personalMediaAddonCopyEnabled

    val overview = remember(uiState.addons) { uiState.addons.toOverview() }

    suspend fun loadDirectory(forceRefresh: Boolean = false) {
        if (directoryState.isLoading) return
        directoryState = directoryState.copy(isLoading = true, errorMessage = null)
        try {
            val result = AddonDirectoryRepository.load(forceRefresh = forceRefresh)
            directoryState = AddonDirectoryUiState(
                addons = result.addons,
                invalidEntryCount = result.invalidEntryCount,
            )
            directoryLoaded = true
        } catch (error: CancellationException) {
            directoryState = directoryState.copy(isLoading = false)
            throw error
        } catch (error: Exception) {
            directoryState = directoryState.copy(
                isLoading = false,
                errorMessage = error.message ?: getString(Res.string.addons_browse_error_unknown),
            )
            directoryLoaded = false
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == ADDONS_BROWSE_TAB && !directoryLoaded) {
            loadDirectory()
        }
    }

    LaunchedEffect(searchQuery, selectedFilter, includeAdultAddons) {
        visibleAddonCount = ADDON_DIRECTORY_PAGE_SIZE
    }

    fun installAddon(manifestUrl: String) {
        installModalState = AddonInstallModalState.Checking
        coroutineScope.launch {
            when (val result = AddonRepository.addAddon(manifestUrl)) {
                is AddAddonResult.Success -> {
                    addonUrl = ""
                    installModalState = AddonInstallModalState.Success(result.manifest.name)
                }

                is AddAddonResult.TrackingConsentRequired -> {
                    AddonRepository.requestTrackingConsent(result.manifest)
                    installModalState = null
                }

                is AddAddonResult.Error -> {
                    installModalState = AddonInstallModalState.Error(result.message)
                }
            }
        }
    }

    fun configureAddon(manifestUrl: String) {
        try {
            uriHandler.openUri(manifestUrl.toConfigureUrl())
        } catch (error: Exception) {
            installModalState = AddonInstallModalState.ConfigureError
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == ADDONS_MANAGE_TAB,
                onClick = { selectedTab = ADDONS_MANAGE_TAB },
                text = { Text(stringResource(Res.string.addons_tab_manage)) },
            )
            Tab(
                selected = selectedTab == ADDONS_BROWSE_TAB,
                onClick = { selectedTab = ADDONS_BROWSE_TAB },
                text = { Text(stringResource(Res.string.addons_tab_browse)) },
            )
        }

        if (selectedTab == ADDONS_MANAGE_TAB) {
            SectionHeader(stringResource(Res.string.addons_section_overview))
            OverviewCard(overview = overview)
            if (syncUiState.hasPendingChanges) {
                AddonSyncStatusCard(syncUiState)
            }

            SectionHeader(stringResource(Res.string.addons_section_add_addon))
            AddAddonCard(
                addonUrl = addonUrl,
                formMessage = formMessage,
                usePersonalMediaCopy = usePersonalMediaCopy,
                onAddonUrlChange = {
                    addonUrl = it
                    formMessage = null
                },
                onAddClick = {
                    val requestedUrl = addonUrl.trim()
                    if (requestedUrl.isBlank()) {
                        formMessage = enterAddonUrlMessage
                    } else {
                        formMessage = null
                        installAddon(requestedUrl)
                    }
                },
            )

            SectionHeader(stringResource(Res.string.addons_section_installed))
            if (uiState.addons.isEmpty()) {
                EmptyStateCard(usePersonalMediaCopy = usePersonalMediaCopy)
            } else {
                val lastIndex = uiState.addons.lastIndex
                uiState.addons.forEachIndexed { index, addon ->
                    val manifest = addon.manifest
                    val behaviorHints = manifest?.behaviorHints
                    val showConfigureAction = behaviorHints?.configurable == true || behaviorHints?.configurationRequired == true
                    InstalledAddonCard(
                        addon = addon,
                        onMoveUpClick = if (index > 0) {
                            { AddonRepository.moveAddon(index, index - 1) }
                        } else {
                            null
                        },
                        onMoveDownClick = if (index < lastIndex) {
                            { AddonRepository.moveAddon(index, index + 1) }
                        } else {
                            null
                        },
                        onRefreshClick = {
                            AddonRepository.refreshAddon(
                                manifestUrl = addon.manifestUrl,
                                forceRefresh = true,
                            )
                        },
                        onEnabledChange = { enabled ->
                            AddonRepository.setAddonEnabled(addon.manifestUrl, enabled)
                        },
                        onConfigureClick = if (showConfigureAction) {
                            { configureAddon(addon.manifestUrl) }
                        } else {
                            null
                        },
                        onDeleteClick = { addonPendingDeletionUrl = addon.manifestUrl },
                    )
                }
            }
        } else {
            val filteredAddons = remember(
                directoryState.addons,
                searchQuery,
                selectedFilter,
                includeAdultAddons,
            ) {
                filterAddonDirectory(
                    addons = directoryState.addons,
                    query = searchQuery,
                    filter = selectedFilter,
                    includeAdult = includeAdultAddons,
                )
            }
            AddonDirectoryContent(
                state = directoryState,
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                selectedFilter = selectedFilter,
                onFilterChange = { selectedFilter = it },
                includeAdultAddons = includeAdultAddons,
                onIncludeAdultAddonsChange = { includeAdultAddons = it },
                visibleAddonCount = visibleAddonCount,
                onLoadMore = { visibleAddonCount += ADDON_DIRECTORY_PAGE_SIZE },
                onRefresh = {
                    coroutineScope.launch { loadDirectory(forceRefresh = true) }
                },
                installedManifestUrls = remember(uiState.addons) {
                    uiState.addons.mapTo(mutableSetOf()) { it.manifestUrl }
                },
                filteredAddons = filteredAddons,
                onInstall = ::installAddon,
                onConfigure = ::configureAddon,
            )
        }
    }

    val pendingDeletionUrl = addonPendingDeletionUrl
    if (pendingDeletionUrl != null) {
        NuvioStatusModal(
            title = stringResource(Res.string.addons_delete_confirm_title),
            message = stringResource(Res.string.action_delete_confirm_message),
            isVisible = true,
            destructive = true,
            confirmText = stringResource(Res.string.action_yes),
            dismissText = stringResource(Res.string.action_no),
            onConfirm = {
                AddonRepository.removeAddon(pendingDeletionUrl)
                addonPendingDeletionUrl = null
            },
            onDismiss = { addonPendingDeletionUrl = null },
        )
    }

    val trackingConsentManifest = pendingTrackingConsent
    if (trackingConsentManifest != null) {
        TrackingAddonConsentDialog(
            manifest = trackingConsentManifest,
            onInstall = {
                installModalState = AddonInstallModalState.Checking
                coroutineScope.launch {
                    installModalState = when (val result = AddonRepository.installWithTrackingConsent(trackingConsentManifest)) {
                        is AddAddonResult.Success -> {
                            addonUrl = ""
                            AddonInstallModalState.Success(result.manifest.name)
                        }

                        is AddAddonResult.TrackingConsentRequired -> null

                        is AddAddonResult.Error -> AddonInstallModalState.Error(result.message)
                    }
                }
            },
            onDismiss = AddonRepository::dismissTrackingConsent,
        )
    }

    val modalState = installModalState
    if (modalState != null) {
        val modalTitle = when (modalState) {
            AddonInstallModalState.Checking -> stringResource(Res.string.addons_modal_checking_title)
            is AddonInstallModalState.Success -> stringResource(Res.string.addons_modal_success_title)
            is AddonInstallModalState.Error -> stringResource(Res.string.addons_modal_failure_title)
            AddonInstallModalState.ConfigureError -> stringResource(Res.string.addons_configure)
        }
        val modalMessage = when (modalState) {
            AddonInstallModalState.Checking -> stringResource(Res.string.addons_modal_checking_message)
            is AddonInstallModalState.Success -> stringResource(
                Res.string.addons_modal_success_message,
                modalState.addonName,
            )
            is AddonInstallModalState.Error -> modalState.reason
            AddonInstallModalState.ConfigureError -> configureErrorMessage
        }
        val modalConfirmText = when (modalState) {
            AddonInstallModalState.Checking -> stringResource(Res.string.addon_installing)
            is AddonInstallModalState.Success -> stringResource(Res.string.action_done)
            is AddonInstallModalState.Error -> stringResource(Res.string.action_close)
            AddonInstallModalState.ConfigureError -> stringResource(Res.string.action_close)
        }
        NuvioStatusModal(
            title = modalTitle,
            message = modalMessage,
            isVisible = true,
            isBusy = modalState.isBusy,
            confirmText = modalConfirmText,
            onConfirm = {
                if (!modalState.isBusy) {
                    installModalState = null
                }
            },
        )
    }
}

private const val ADDONS_MANAGE_TAB = 0
private const val ADDONS_BROWSE_TAB = 1
private const val ADDON_DIRECTORY_PAGE_SIZE = 30

private data class AddonDirectoryUiState(
    val addons: List<AddonManifest> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val invalidEntryCount: Int = 0,
)

@Composable
private fun AddonDirectoryContent(
    state: AddonDirectoryUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedFilter: AddonDirectoryFilter,
    onFilterChange: (AddonDirectoryFilter) -> Unit,
    includeAdultAddons: Boolean,
    onIncludeAdultAddonsChange: (Boolean) -> Unit,
    visibleAddonCount: Int,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    installedManifestUrls: Set<String>,
    filteredAddons: List<AddonManifest>,
    onInstall: (String) -> Unit,
    onConfigure: (String) -> Unit,
) {
    SectionHeader(stringResource(Res.string.addons_browse_title))
    NuvioSurfaceCard {
        Text(
            text = stringResource(Res.string.addons_browse_disclaimer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.addons_browse_configure_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        NuvioInputField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = stringResource(Res.string.addons_browse_search),
        )
        Spacer(modifier = Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AddonDirectoryFilter.entries.forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { onFilterChange(filter) },
                    label = {
                        Text(
                            stringResource(
                                when (filter) {
                                    AddonDirectoryFilter.ALL -> Res.string.addons_browse_filter_all
                                    AddonDirectoryFilter.CATALOGS -> Res.string.addons_browse_filter_catalogs
                                    AddonDirectoryFilter.STREAMS -> Res.string.addons_browse_filter_streams
                                    AddonDirectoryFilter.SUBTITLES -> Res.string.addons_browse_filter_subtitles
                                },
                            ),
                        )
                    },
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = includeAdultAddons,
                onCheckedChange = onIncludeAdultAddonsChange,
            )
            Text(
                text = stringResource(Res.string.addons_browse_include_adult),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(
                    Res.string.addons_browse_count,
                    minOf(visibleAddonCount, filteredAddons.size),
                    filteredAddons.size,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NuvioIconActionButton(
                icon = Icons.Rounded.Refresh,
                contentDescription = stringResource(Res.string.addons_browse_refresh),
                tint = MaterialTheme.colorScheme.primary,
                onClick = onRefresh,
            )
        }
    }

    when {
        state.isLoading -> {
            NuvioSurfaceCard {
                Text(
                    text = stringResource(Res.string.addons_browse_loading),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.errorMessage != null -> {
            NuvioSurfaceCard {
                Text(
                    text = state.errorMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(12.dp))
                NuvioPrimaryButton(
                    text = stringResource(Res.string.action_retry),
                    onClick = onRefresh,
                )
            }
        }

        filteredAddons.isEmpty() -> {
            NuvioSurfaceCard {
                Text(
                    text = stringResource(Res.string.addons_browse_no_results),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        else -> {
            filteredAddons.take(visibleAddonCount).forEach { addon ->
                AddonDirectoryCard(
                    manifest = addon,
                    isInstalled = addon.transportUrl in installedManifestUrls,
                    onInstall = { onInstall(addon.transportUrl) },
                    onConfigure = { onConfigure(addon.transportUrl) },
                )
            }
            if (filteredAddons.size > visibleAddonCount) {
                NuvioPrimaryButton(
                    text = stringResource(Res.string.addons_browse_load_more),
                    onClick = onLoadMore,
                )
            }
        }
    }

    if (state.invalidEntryCount > 0) {
        Text(
            text = stringResource(Res.string.addons_browse_invalid_entries, state.invalidEntryCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddonDirectoryCard(
    manifest: AddonManifest,
    isInstalled: Boolean,
    onInstall: () -> Unit,
    onConfigure: () -> Unit,
) {
    NuvioSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            AddonIconBadge(
                imageUrl = manifest.logoUrl,
                icon = Icons.Rounded.Extension,
                tint = Color(0xFF71BDE8),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = manifest.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = manifest.description.ifBlank {
                        stringResource(Res.string.addons_browse_no_description)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = manifestSummary(manifest),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (manifest.behaviorHints.adult) {
                NuvioInfoBadge(text = stringResource(Res.string.addons_browse_adult_badge))
            }
            if (manifest.behaviorHints.p2p) {
                NuvioInfoBadge(text = "P2P")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (manifest.canConfigure) {
            NuvioPrimaryButton(
                text = stringResource(Res.string.addons_configure),
                onClick = onConfigure,
            )
        }
        if (!manifest.behaviorHints.configurationRequired || isInstalled) {
            if (manifest.canConfigure) Spacer(modifier = Modifier.height(8.dp))
            NuvioPrimaryButton(
                text = stringResource(
                    if (isInstalled) {
                        Res.string.addons_browse_installed
                    } else {
                        Res.string.addons_browse_add
                    },
                ),
                enabled = !isInstalled,
                onClick = onInstall,
            )
        }
    }
}
@Composable
private fun SectionHeader(text: String) {
    NuvioSectionLabel(text = text)
}

@Composable
private fun OverviewCard(overview: AddonOverview) {
    NuvioSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverviewStat(
                value = overview.totalAddons.toString(),
                label = stringResource(Res.string.addons_overview_addons),
                modifier = Modifier.weight(1f),
            )
            VerticalSeparator()
            OverviewStat(
                value = overview.activeAddons.toString(),
                label = stringResource(Res.string.addons_overview_active),
                modifier = Modifier.weight(1f),
            )
            VerticalSeparator()
            OverviewStat(
                value = overview.totalCatalogs.toString(),
                label = stringResource(Res.string.addons_overview_catalogs),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AddonSyncStatusCard(state: AddonSyncUiState) {
    NuvioSurfaceCard {
        Text(
            text = stringResource(
                when {
                    state.hasFailed -> Res.string.addons_sync_failed
                    state.isSyncing -> Res.string.addons_sync_in_progress
                    else -> Res.string.addons_sync_pending
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.hasFailed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (!state.isSyncing) {
            Spacer(modifier = Modifier.height(12.dp))
            NuvioPrimaryButton(
                text = stringResource(Res.string.action_retry),
                onClick = AddonRepository::retryPendingPush,
            )
        }
    }
}

@Composable
private fun OverviewStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VerticalSeparator() {
    Box(
        modifier = Modifier
            .padding(horizontal = 10.dp)
            .width(1.dp)
            .height(64.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

@Composable
private fun AddAddonCard(
    addonUrl: String,
    formMessage: String?,
    usePersonalMediaCopy: Boolean,
    onAddonUrlChange: (String) -> Unit,
    onAddClick: () -> Unit,
) {
    NuvioSurfaceCard {
        NuvioInputField(
            value = addonUrl,
            onValueChange = onAddonUrlChange,
            placeholder = stringResource(
                if (usePersonalMediaCopy) {
                    Res.string.addons_appstore_input_placeholder
                } else {
                    Res.string.addons_input_placeholder
                },
            ),
        )
        Spacer(modifier = Modifier.height(18.dp))
        NuvioPrimaryButton(
            text = stringResource(
                if (usePersonalMediaCopy) {
                    Res.string.addons_appstore_install_button
                } else {
                    Res.string.addons_install_button
                },
            ),
            enabled = addonUrl.isNotBlank(),
            onClick = onAddClick,
        )
        if (usePersonalMediaCopy) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(Res.string.addons_appstore_add_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        formMessage?.let { message ->
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private sealed interface AddonInstallModalState {
    val isBusy: Boolean

    data object Checking : AddonInstallModalState {
        override val isBusy: Boolean = true
    }

    data class Success(
        val addonName: String,
    ) : AddonInstallModalState {
        override val isBusy: Boolean = false
    }

    data class Error(
        val reason: String,
    ) : AddonInstallModalState {
        override val isBusy: Boolean = false
    }

    data object ConfigureError : AddonInstallModalState {
        override val isBusy: Boolean = false
    }
}

@Composable
private fun EmptyStateCard(
    usePersonalMediaCopy: Boolean,
) {
    NuvioSurfaceCard {
        Text(
            text = stringResource(
                if (usePersonalMediaCopy) {
                    Res.string.addons_appstore_empty_title
                } else {
                    Res.string.addons_empty_title
                },
            ),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(
                if (usePersonalMediaCopy) {
                    Res.string.addons_appstore_empty_subtitle
                } else {
                    Res.string.addons_empty_subtitle
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InstalledAddonCard(
    addon: ManagedAddon,
    onMoveUpClick: (() -> Unit)?,
    onMoveDownClick: (() -> Unit)?,
    onRefreshClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onConfigureClick: (() -> Unit)?,
    onDeleteClick: () -> Unit,
) {
    val manifest = addon.manifest

    NuvioSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            AddonIconBadge(
                imageUrl = manifest?.logoUrl,
                icon = Icons.Rounded.Extension,
                tint = if (manifest != null) Color(0xFF71BDE8) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = addon.displayTitle,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                manifest?.version?.let { version ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(Res.string.addons_version_format, version),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = addon.enabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onMoveUpClick?.let { onMoveUp ->
                NuvioIconActionButton(
                    icon = Icons.Rounded.ArrowUpward,
                    contentDescription = stringResource(Res.string.addons_move_up),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onMoveUp,
                )
            }
            onMoveDownClick?.let { onMoveDown ->
                NuvioIconActionButton(
                    icon = Icons.Rounded.ArrowDownward,
                    contentDescription = stringResource(Res.string.addons_move_down),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onMoveDown,
                )
            }
            NuvioIconActionButton(
                icon = Icons.Rounded.Refresh,
                contentDescription = stringResource(Res.string.addons_refresh),
                tint = MaterialTheme.colorScheme.primary,
                onClick = onRefreshClick,
            )
            onConfigureClick?.let { onConfigure ->
                NuvioIconActionButton(
                    icon = Icons.Rounded.Settings,
                    contentDescription = stringResource(Res.string.addons_configure),
                    tint = MaterialTheme.colorScheme.tertiary,
                    onClick = onConfigure,
                )
            }
            NuvioIconActionButton(
                icon = Icons.Rounded.Delete,
                contentDescription = stringResource(Res.string.addons_delete),
                tint = MaterialTheme.colorScheme.error,
                onClick = onDeleteClick,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(18.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NuvioInfoBadge(
                text = when {
                    !addon.enabled -> stringResource(Res.string.addons_badge_disabled)
                    addon.isRefreshing -> stringResource(Res.string.addons_badge_refreshing)
                    manifest != null -> stringResource(Res.string.addons_badge_active)
                    else -> stringResource(Res.string.addons_badge_unavailable)
                },
            )
            manifest?.let {
                NuvioInfoBadge(text = stringResource(Res.string.addons_badge_resources, it.resources.size))
                NuvioInfoBadge(text = stringResource(Res.string.addons_badge_catalogs, it.catalogs.size))
                if (it.behaviorHints.configurable) {
                    NuvioInfoBadge(text = stringResource(Res.string.addons_badge_configurable))
                }
            }
        }

        when {
            addon.isRefreshing -> {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(Res.string.addons_loading_manifest_details),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            addon.errorMessage != null && manifest == null -> {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = addon.errorMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            manifest != null -> {
                Spacer(modifier = Modifier.height(16.dp))
                manifest.description.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                }

                Text(
                    text = manifestSummary(manifest),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                addon.errorMessage?.let { staleError ->
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = staleError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddonIconBadge(
    imageUrl: String?,
    icon: ImageVector,
    tint: Color,
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

@Composable
private fun manifestSummary(manifest: AddonManifest): String {
    val resources = manifest.resources.joinToString(separator = ", ") { it.name }
    val types = manifest.types.joinToString(separator = " / ") { it.replaceFirstChar(Char::uppercase) }
    return buildString {
        append(types)
        append(" • ")
        append(resources)
        if (manifest.idPrefixes.isNotEmpty()) {
            append(" • ")
            append(stringResource(Res.string.addons_summary_id_rules, manifest.idPrefixes.size))
        }
        if (manifest.behaviorHints.p2p) {
            append(" • P2P")
        }
    }
}

private fun String.toConfigureUrl(): String {
    val base = substringBefore("?").trimEnd('/')
    return if (base.endsWith("/manifest.json")) {
        base.removeSuffix("/manifest.json") + "/configure"
    } else {
        "$base/configure"
    }
}
