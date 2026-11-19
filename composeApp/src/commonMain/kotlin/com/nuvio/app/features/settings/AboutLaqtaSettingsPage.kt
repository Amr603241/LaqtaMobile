package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.about_laqta_body
import nuvio.composeapp.generated.resources.about_laqta_repository
import nuvio.composeapp.generated.resources.about_laqta_subtitle
import nuvio.composeapp.generated.resources.about_laqta_title
import nuvio.composeapp.generated.resources.compose_settings_root_about_section
import org.jetbrains.compose.resources.stringResource

private const val LaqtaRepositoryUrl = "https://github.com/Amr603241/LaqtaMobile"

@Composable
fun AboutLaqtaSettingsScreen(
    onBack: () -> Unit,
) {
    NuvioScreen(
        modifier = Modifier.fillMaxSize(),
    ) {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.about_laqta_title),
                onBack = onBack,
            )
        }
        aboutLaqtaContent(isTablet = false)
    }
}

internal fun LazyListScope.aboutLaqtaContent(
    isTablet: Boolean,
) {
    item {
        AboutLaqtaContent(isTablet = isTablet)
    }
    settingsFooterContent(isTablet)
}

@Composable
private fun AboutLaqtaContent(
    isTablet: Boolean,
) {
    val uriHandler = LocalUriHandler.current
    SettingsSection(
        title = stringResource(Res.string.compose_settings_root_about_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            Text(text = stringResource(Res.string.about_laqta_body))
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.about_laqta_repository),
                description = stringResource(Res.string.about_laqta_subtitle),
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                isTablet = isTablet,
                onClick = { uriHandler.openUri(LaqtaRepositoryUrl) },
            )
        }
    }
}
