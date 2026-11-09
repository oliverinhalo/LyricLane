package dev.lyriclane.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyriclane.R

private enum class Tab(val label: String, val icon: Int) {
    NOW_PLAYING("Now playing", R.drawable.ic_lyrics_on),
    SETTINGS("Settings", R.drawable.ic_settings),
}

@Composable
fun LyricLaneRoot(viewModel: NowPlayingViewModel = hiltViewModel()) {
    // Granting notification access happens in system settings, and there is no
    // callback for it; re-checking on resume is the only way to notice.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    val spotify by viewModel.spotify.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.NOW_PLAYING) }

    if (!spotify.permissionGranted) {
        OnboardingScreen()
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {
                            Icon(
                                painter = painterResource(entry.icon),
                                contentDescription = entry.label,
                            )
                        },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets)
        ) {
            when (tab) {
                Tab.NOW_PLAYING -> NowPlayingScreen(viewModel)
                Tab.SETTINGS -> SettingsScreen(viewModel)
            }
        }
    }
}
