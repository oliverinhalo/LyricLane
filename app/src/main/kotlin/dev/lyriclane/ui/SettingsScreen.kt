package dev.lyriclane.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyriclane.media.SettingsStore
import dev.lyriclane.media.SubtitleMode
import dev.lyriclane.spotify.NotificationAccess
import java.util.Locale

@Composable
fun SettingsScreen(viewModel: NowPlayingViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Second line in the car", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.subtitleMode == SubtitleMode.NEXT_LINE,
                onClick = { viewModel.setSubtitleMode(SubtitleMode.NEXT_LINE) },
                label = { Text("Next line") },
            )
            FilterChip(
                selected = settings.subtitleMode == SubtitleMode.SONG_INFO,
                onClick = { viewModel.setSubtitleMode(SubtitleMode.SONG_INFO) },
                label = { Text("Song and artist") },
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Show lyrics", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Off mirrors the song title and artist instead.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = settings.lyricsEnabled,
                onCheckedChange = viewModel::setLyricsEnabled,
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("Default timing offset", style = MaterialTheme.typography.titleMedium)
        Text(
            "Used for songs you have not adjusted yet. " +
                "Positive shows lyrics earlier. Now: ${formatSeconds(settings.defaultOffsetMs)}",
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = settings.defaultOffsetMs.toFloat(),
            onValueChange = { viewModel.setDefaultOffset(it.toLong().roundToStep()) },
            valueRange = -SettingsStore.MAX_OFFSET_MS.toFloat()..SettingsStore.MAX_OFFSET_MS.toFloat(),
            steps = 39,
        )

        Spacer(Modifier.height(16.dp))
        Text("Minimum hold between lines", style = MaterialTheme.typography.titleMedium)
        Text(
            "How long a line stays on the car screen before the next one can " +
                "replace it. Raise it if fast verses look like they flicker; lower " +
                "it for tighter sync. Now: ${settings.minHoldMs} ms",
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = settings.minHoldMs.toFloat(),
            onValueChange = { viewModel.setMinHold((it / 50).toLong() * 50) },
            valueRange = 0f..2_000f,
            steps = 39,
        )

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("Cache", style = MaterialTheme.typography.titleMedium)
        Text(
            "${viewModel.cachedCount} songs stored for offline use.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = viewModel::clearCache) {
            Text("Clear cached lyrics and offsets")
        }

        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = { context.startActivity(NotificationAccess.settingsIntent()) },
        ) {
            Text("Notification access settings")
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "Lyrics from lrclib.net, contributed by its community. " +
                "LyricLane plays no audio; Spotify does.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun Long.roundToStep(): Long = (this / SettingsStore.OFFSET_STEP_MS) * SettingsStore.OFFSET_STEP_MS

private fun formatSeconds(ms: Long): String =
    if (ms == 0L) "0 s" else String.format(Locale.US, "%+.2f s", ms / 1000.0)
