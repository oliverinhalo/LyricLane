package dev.lyriclane.ui

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.AndroidEntryPoint
import dev.lyriclane.media.LyricLaneService

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Media3 posts a media-style notification while the session is live.
        // Without this the mirror still works, but there is no on-phone proof.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            LyricLaneTheme {
                // Connecting a controller is what starts the session service, so
                // the car has something to attach to and the phone shows a
                // media notification.
                KeepSessionAlive()
                LyricLaneRoot()
            }
        }
    }
}

@OptIn(UnstableApi::class)
@androidx.compose.runtime.Composable
private fun KeepSessionAlive() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, LyricLaneService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        onDispose { MediaController.releaseFuture(future) }
    }
}
