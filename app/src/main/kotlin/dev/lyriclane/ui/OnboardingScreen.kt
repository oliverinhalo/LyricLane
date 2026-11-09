package dev.lyriclane.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lyriclane.spotify.NotificationAccess

/**
 * Shown until notification-listener access is granted, because nothing works
 * without it. Deliberately blunt: one obvious button, and an explanation of why
 * an app that shows lyrics is asking to read notifications.
 */
@Composable
fun OnboardingScreen() {
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("LyricLane", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Shows the current line of whatever Spotify is playing on your " +
                "Android Auto screen.",
            style = MaterialTheme.typography.bodyLarge,
        )

        Spacer(Modifier.height(24.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "One permission is needed",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "To read what Spotify is playing, LyricLane has to be an " +
                        "enabled notification listener. That is the only way Android " +
                        "lets one app see another app's media session.\n\n" +
                        "LyricLane does not read your notifications. It asks the system " +
                        "for the active media session, finds Spotify's, and follows the " +
                        "track and the playback position. Nothing leaves your phone " +
                        "except the song title and artist, sent to lrclib.net to look up " +
                        "the lyrics.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { context.startActivity(NotificationAccess.settingsIntent()) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Grant notification access")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Find LyricLane in the list and turn it on, then come back here. " +
                        "This screen disappears by itself once it is granted.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Then, for the car",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Step(1, "Android Auto ignores sideloaded apps until you switch that off.")
                Step(2, "Open Android Auto settings, scroll to About, tap Version.")
                Step(3, "Tap \"Version and permission info\" ten times, then OK.")
                Step(4, "In the ⋮ menu, open Developer settings and turn on \"Unknown sources\".")
                Step(5, "LyricLane now appears in the car's media app list.")
            }
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            "$number.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
