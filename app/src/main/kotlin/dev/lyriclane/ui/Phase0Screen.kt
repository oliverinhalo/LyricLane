package dev.lyriclane.ui

import android.content.ComponentName
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dev.lyriclane.diag.TickLog
import dev.lyriclane.diag.TickerRate
import dev.lyriclane.media.LyricLaneService
import java.util.Locale

/**
 * Phase 0 operator console.
 *
 * It connects to our own MediaLibraryService as a controller, which (a) starts
 * the service, and (b) proves the metadata pipeline end to end on the phone
 * before the car is involved. If the title below ticks but the head unit does
 * not, the problem is Android Auto's rendering, not our session.
 */
@OptIn(UnstableApi::class)
@Composable
fun Phase0Screen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var liveTitle by remember { mutableStateOf("—") }
    var liveSubtitle by remember { mutableStateOf("connecting…") }
    var selectedRate by remember { mutableStateOf(TickerRate.DEFAULT) }

    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, LyricLaneService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val listener = object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                liveTitle = mediaMetadata.title?.toString() ?: "—"
                liveSubtitle = mediaMetadata.artist?.toString() ?: ""
            }
        }
        future.addListener({
            runCatching { future.get() }.onSuccess { connected ->
                controller = connected
                connected.addListener(listener)
                connected.mediaMetadata.let(listener::onMediaMetadataChanged)
            }.onFailure {
                liveSubtitle = "could not connect: ${it.message}"
            }
        }, MoreExecutors.directExecutor())

        onDispose {
            controller?.removeListener(listener)
            controller?.release()
            controller = null
            MediaController.releaseFuture(future)
        }
    }

    val entries by TickLog.entries.collectAsStateWithLifecycle()

    Column(modifier = modifier.padding(16.dp)) {
        Text("LyricLane · Phase 0 spike", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Verifying that Android Auto re-renders live MediaSession metadata, " +
                "on the now-playing screen and the dashboard card.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("What the session is publishing", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Text(liveTitle, style = MaterialTheme.typography.headlineSmall)
                Text(liveSubtitle, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Push rate", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TickerRate.entries.forEach { rate ->
                FilterChip(
                    selected = rate == selectedRate,
                    onClick = {
                        selectedRate = rate
                        controller?.sendCustomCommand(
                            SessionCommand(LyricLaneService.COMMAND_SET_RATE, Bundle.EMPTY),
                            Bundle().apply {
                                putString(LyricLaneService.ARG_RATE_NAME, rate.name)
                            },
                        )
                    },
                    label = { Text(rate.label) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val measured = TickLog.measuredIntervalMs()
            Text(
                text = measured?.let {
                    String.format(Locale.US, "measured push interval: %d ms", it)
                } ?: "measured push interval: —",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { TickLog.clear() }) { Text("Clear") }
        }

        HorizontalDivider()
        LazyColumn(Modifier.fillMaxWidth()) {
            items(entries, key = { it.sequence }) { entry ->
                Text(
                    text = String.format(
                        Locale.US,
                        "%6d  %tT.%03d  %s",
                        entry.sequence,
                        entry.wallClockMs,
                        entry.wallClockMs % 1000,
                        entry.title,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}
