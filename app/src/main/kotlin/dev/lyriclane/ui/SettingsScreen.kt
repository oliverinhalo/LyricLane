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
import dev.lyriclane.media.CarTextSlots
import dev.lyriclane.media.SecondaryLine
import dev.lyriclane.media.SettingsStore
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
        Text("Lyric size on this screen", style = MaterialTheme.typography.titleMedium)
        Text(
            "Does not affect the car: Android Auto chooses its own text size " +
                "and we cannot override it. Now: ${"%.2f".format(settings.lyricTextScale)}\u00d7",
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = settings.lyricTextScale,
            onValueChange = { viewModel.setLyricTextScale((it * 20).toInt() / 20f) },
            valueRange = SettingsStore.MIN_TEXT_SCALE..SettingsStore.MAX_TEXT_SCALE,
            steps = 35,
        )

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("Which line the car shows large", style = MaterialTheme.typography.titleMedium)
        Text(
            "Android Auto draws the subtitle large and the title small above it " +
                "\u2014 the opposite of what the names suggest. If your head unit " +
                "shows the lyric in the small grey line, switch this.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CarTextSlots.entries.forEach { slots ->
                FilterChip(
                    selected = settings.carTextSlots == slots,
                    onClick = { viewModel.setCarTextSlots(slots) },
                    label = { Text(slots.label) },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("Smaller line in the car", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryLine.entries.forEach { mode ->
                FilterChip(
                    selected = settings.secondaryLine == mode,
                    onClick = { viewModel.setSecondaryLine(mode) },
                    label = { Text(mode.label) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Two lines in one slot", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Experimental. Sends the current and next line together so the " +
                        "car may wrap them onto two rows. Whether it does is up to the " +
                        "head unit; if it just truncates, turn this back off.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = settings.packTwoLines,
                onCheckedChange = viewModel::setPackTwoLines,
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Lyric in the title too",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (settings.carTextSlots == CarTextSlots.TITLE_IS_BIG) {
                        "Already on: with the title set as the large line, the " +
                            "lyric is in the title anyway."
                    } else {
                        "Instrument clusters and head-up displays read the title, " +
                            "while the dashboard card draws the subtitle large. " +
                            "This puts the lyric in both, so it reaches the HUD " +
                            "without the card losing it. The card then shows the " +
                            "line twice, once large and once small."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = settings.lyricInTitle ||
                    settings.carTextSlots == CarTextSlots.TITLE_IS_BIG,
                enabled = settings.carTextSlots != CarTextSlots.TITLE_IS_BIG,
                onCheckedChange = viewModel::setLyricInTitle,
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Album art", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The car decides how big the cover is. Turning it off is the " +
                        "only way to give the words more of the card.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = settings.showArtwork,
                onCheckedChange = viewModel::setShowArtwork,
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
            "Lyrics from lrclib.net and music.163.com, contributed by their " +
                "communities. LyricLane plays no audio; Spotify does.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun Long.roundToStep(): Long = (this / SettingsStore.OFFSET_STEP_MS) * SettingsStore.OFFSET_STEP_MS

private fun formatSeconds(ms: Long): String =
    if (ms == 0L) "0 s" else String.format(Locale.US, "%+.2f s", ms / 1000.0)
