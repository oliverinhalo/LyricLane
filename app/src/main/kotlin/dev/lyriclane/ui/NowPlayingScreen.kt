package dev.lyriclane.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyriclane.lyrics.LyricsResult
import java.util.Locale

@Composable
fun NowPlayingScreen(viewModel: NowPlayingViewModel) {
    val spotify by viewModel.spotify.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    val display by viewModel.display.collectAsStateWithLifecycle()
    val offsetMs by viewModel.offsetMs.collectAsStateWithLifecycle()
    var pickerOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            val track = spotify.track
            Text(
                text = track?.title ?: "Nothing playing on Spotify",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
            )
            Text(
                text = track?.artist?.takeIf { it.isNotBlank() }
                    ?: "Press play in Spotify and come back",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = statusLine(lyrics, spotify.isPlaying, frame.lineCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // What the car is showing, verbatim. The single most useful thing to be
        // able to see while sitting in a driveway working out why the head unit
        // looks wrong.
        Card(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("On the car screen", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    display.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Text(
                    display.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        OffsetRow(
            offsetMs = offsetMs,
            onNudge = viewModel::nudgeOffset,
            onReset = viewModel::resetOffset,
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    viewModel.prepareSearch()
                    pickerOpen = true
                },
                modifier = Modifier.weight(1f),
            ) { Text("Wrong lyrics?") }
            OutlinedButton(
                onClick = viewModel::retry,
                modifier = Modifier.weight(1f),
            ) { Text("Look again") }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        LyricsPane(
            lyrics = lyrics,
            activeIndex = frame.lineIndex,
            modifier = Modifier.weight(1f),
        )
    }

    if (pickerOpen) {
        LyricsPickerDialog(
            viewModel = viewModel,
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun OffsetRow(offsetMs: Long, onNudge: (Long) -> Unit, onReset: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(onClick = { onNudge(-NowPlayingViewModel.OFFSET_STEP_MS) }) {
            Text("Later")
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = formatOffset(offsetMs),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "timing for this song",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FilledTonalButton(onClick = { onNudge(NowPlayingViewModel.OFFSET_STEP_MS) }) {
            Text("Earlier")
        }
        if (offsetMs != 0L) {
            TextButton(onClick = onReset) { Text("Reset") }
        }
    }
}

@Composable
private fun LyricsPane(lyrics: LyricsResult, activeIndex: Int, modifier: Modifier = Modifier) {
    when (lyrics) {
        is LyricsResult.Synced -> {
            val listState = rememberLazyListState()
            LaunchedEffect(activeIndex) {
                if (activeIndex >= 0) {
                    // Keep the active line a couple of rows down from the top so
                    // the lines ahead are readable.
                    listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
                }
            }
            LazyColumn(
                state = listState,
                modifier = modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 12.dp,
                ),
            ) {
                itemsIndexed(lyrics.lines) { index, line ->
                    val isActive = index == activeIndex
                    val color by animateColorAsState(
                        if (isActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        label = "lyricColor",
                    )
                    Text(
                        text = if (line.isGap) "♪" else line.text,
                        style = if (isActive) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        color = color,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
        }

        is LyricsResult.PlainOnly -> Message(
            modifier = modifier,
            heading = "Lyrics are not synced",
            body = "LRCLIB has words for this track but no timestamps, so the car " +
                "shows the song title instead.\n\n${lyrics.text}",
        )

        LyricsResult.Instrumental -> Message(modifier, "Instrumental", "No words to show.")
        LyricsResult.NotFound -> Message(
            modifier = modifier,
            heading = "No lyrics found",
            body = "LRCLIB does not have this track. Try \"Wrong lyrics?\" to search " +
                "for it by hand — Spotify's title sometimes differs from the one " +
                "the lyrics are filed under.",
        )

        is LyricsResult.Error -> Message(
            modifier = modifier,
            heading = "Could not reach LRCLIB",
            body = "${lyrics.message}\n\nCached songs still work offline. " +
                "Tap \"Look again\" when you have a connection.",
        )

        LyricsResult.Loading -> Message(modifier, "Finding lyrics…", "")
        LyricsResult.Idle -> Message(
            modifier = modifier,
            heading = "Waiting for Spotify",
            body = "Start a song in Spotify. Ads and podcasts are skipped.",
        )
    }
}

@Composable
private fun Message(modifier: Modifier = Modifier, heading: String, body: String) {
    Column(modifier.padding(16.dp)) {
        Text(heading, style = MaterialTheme.typography.titleMedium)
        if (body.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun statusLine(lyrics: LyricsResult, playing: Boolean, lineCount: Int): String {
    val state = when (lyrics) {
        is LyricsResult.Synced ->
            "synced · $lineCount lines" + if (lyrics.fromCache) " · cached" else ""

        is LyricsResult.PlainOnly -> "not synced"
        LyricsResult.Instrumental -> "instrumental"
        LyricsResult.NotFound -> "no lyrics"
        is LyricsResult.Error -> "offline"
        LyricsResult.Loading -> "searching"
        LyricsResult.Idle -> "idle"
    }
    return if (playing) "playing · $state" else "paused · $state"
}

private fun formatOffset(offsetMs: Long): String = when {
    offsetMs == 0L -> "in sync"
    else -> String.format(Locale.US, "%+.2f s", offsetMs / 1000.0)
}
