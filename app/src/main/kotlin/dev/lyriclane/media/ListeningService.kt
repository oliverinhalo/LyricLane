package dev.lyriclane.media

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.lyriclane.R
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Runs one microphone identification, then stops.
 *
 * Why a service of its own rather than doing this in [LyricLaneService]:
 * recording from the background needs a foreground service declared with the
 * `microphone` type, and that type may only be started by an app holding
 * `RECORD_AUDIO`. Adding it to the media service would make the whole app fail
 * to go foreground for anyone who never grants the microphone — a steep price
 * for an optional feature. Keeping it separate means the media session is
 * untouched when this is unused or refused.
 */
@AndroidEntryPoint
class ListeningService : Service() {

    @Inject lateinit var orchestrator: LyricLaneOrchestrator

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasMicrophonePermission(this)) {
            Log.w(TAG, "asked to listen without microphone permission")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForegroundSafely()
        orchestrator.identifyByEar()
        scope.launch {
            // identifyByEar flips this to true before it records; wait for it
            // to settle back before letting the service go.
            orchestrator.listening.first { it }
            orchestrator.listening.first { !it }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundSafely() {
        val channelId = ensureChannel()
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_lyriclane_notification)
            .setContentTitle("Listening…")
            .setContentText("Identifying what is playing")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat_startForeground(notification)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Android refuses a background foreground-service start in some
            // states. Recording will fail rather than crash the app.
            Log.w(TAG, "could not go foreground to listen", e)
        }
    }

    @Suppress("FunctionName")
    private fun ServiceCompat_startForeground(notification: Notification) {
        androidx.core.app.ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    private fun ensureChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Listening",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = "Shown while LyricLane identifies a song by ear"
                        setShowBadge(false)
                    }
                )
            }
        }
        return CHANNEL_ID
    }

    companion object {
        private const val TAG = "LyricLane"
        private const val CHANNEL_ID = "lyriclane_listening"
        private const val NOTIFICATION_ID = 42

        fun hasMicrophonePermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        /** Starts one identification. Safe to call when already listening. */
        fun start(context: Context) {
            val intent = Intent(context, ListeningService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "could not start listening", it) }
        }
    }
}
