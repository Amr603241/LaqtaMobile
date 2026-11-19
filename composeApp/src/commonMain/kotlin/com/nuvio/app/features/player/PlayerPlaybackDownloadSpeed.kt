package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_current_download_speed
import nuvio.composeapp.generated.resources.player_download_speed_unavailable
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlayerPlaybackDownloadSpeed(controller: PlayerEngineController?, visible: Boolean) {
    var speed by remember(controller) { mutableStateOf<Long?>(null) }
    LaunchedEffect(controller, visible) {
        speed = null
        if (visible && controller != null) {
            while (isActive) {
                speed = controller.getDownloadSpeedBytesPerSecond()
                delay(1_000)
            }
        }
    }
    val label = speed?.let { bytes ->
        when {
            bytes >= 1_000_000 -> "${(bytes / 100_000L) / 10.0} MB/s"
            bytes >= 1_000 -> "${bytes / 1_000L} kB/s"
            else -> "$bytes B/s"
        }
    } ?: stringResource(Res.string.player_download_speed_unavailable)
    Text(
        text = stringResource(Res.string.player_current_download_speed, label),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.nuvio.colors.textSecondary,
    )
    Spacer(Modifier.height(12.dp))
}
