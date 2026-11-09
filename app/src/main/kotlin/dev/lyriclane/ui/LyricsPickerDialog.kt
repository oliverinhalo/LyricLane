package dev.lyriclane.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.lyriclane.lyrics.LyricsCandidate
import java.util.Locale

/**
 * Manual lyric picker. Spotify's title often differs from the one LRCLIB files
 * the song under — "(Remastered)", a different single edit — so when the
 * automatic match is wrong, searching by hand is the fix.
 */
@Composable
fun LyricsPickerDialog(viewModel: NowPlayingViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = viewModel::runSearch) { Text("Search") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text("Find the right lyrics") },
        text = {
            Column {
                OutlinedTextField(
                    value = viewModel.searchQuery,
                    onValueChange = viewModel::onSearchQueryChange,
                    label = { Text("Song and artist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                when {
                    viewModel.searching -> CircularProgressIndicator()
                    viewModel.searchResults.isEmpty() -> Text(
                        "No results yet. Edit the text above and tap Search.",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(viewModel.searchResults) { candidate ->
                            CandidateRow(candidate) {
                                viewModel.apply(candidate)
                                onDismiss()
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun CandidateRow(candidate: LyricsCandidate, onPick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 10.dp)
    ) {
        Text(candidate.trackName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        Text(
            text = listOfNotNull(candidate.artistName, candidate.albumName)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(
                onClick = onPick,
                label = { Text(formatDuration(candidate.durationSeconds)) },
            )
            AssistChip(
                onClick = onPick,
                label = {
                    Text(
                        when {
                            candidate.instrumental -> "instrumental"
                            candidate.hasSynced -> "synced"
                            else -> "plain only"
                        }
                    )
                },
            )
        }
    }
}

private fun formatDuration(seconds: Double): String {
    val total = seconds.toInt()
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}
