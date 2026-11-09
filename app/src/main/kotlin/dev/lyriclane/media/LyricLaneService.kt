package dev.lyriclane.media

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.lyriclane.R
import dev.lyriclane.diag.TickLog
import dev.lyriclane.diag.TickerRate
import dev.lyriclane.ui.MainActivity
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The media app Android Auto sees.
 *
 * Phase 0 spike: it publishes a session whose title changes on a timer, so we
 * can establish — before building anything else — that the car's now-playing
 * screen *and* the split-screen dashboard card both re-render live metadata.
 * Everything downstream of that assumption (lyrics as metadata) depends on it.
 */
@OptIn(UnstableApi::class)
class LyricLaneService : MediaLibraryService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var player: MirrorPlayer
    private var librarySession: MediaLibrarySession? = null

    private var rate: TickerRate = TickerRate.DEFAULT
    private var sequence = 0L

    override fun onCreate() {
        super.onCreate()
        player = MirrorPlayer(Looper.getMainLooper()).apply {
            setPosition(positionMs = 0L, durationMs = 60 * 60 * 1000L, advancing = true)
            setArtwork(placeholderArtworkPng())
        }
        librarySession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setId(SESSION_ID)
            .setSessionActivity(sessionActivity())
            .setCustomLayout(ImmutableList.of(cycleRateButton()))
            .build()
        startTicker()
        Log.i(TAG, "session created, ticking every ${rate.intervalMs} ms")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        librarySession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the phone app away must not kill the car session.
    }

    override fun onDestroy() {
        scope.cancel()
        librarySession?.release()
        librarySession = null
        player.release()
        super.onDestroy()
    }

    // --- the spike itself -------------------------------------------------------------

    private fun startTicker() {
        scope.launch {
            while (true) {
                pushTick()
                delay(rate.intervalMs)
            }
        }
    }

    private fun pushTick() {
        sequence += 1
        val now = System.currentTimeMillis()
        val title = String.format(
            Locale.US,
            "Tick %03d  ·  %tT.%03d",
            sequence,
            now,
            now % 1000,
        )
        val subtitle = "Phase 0 spike · pushing every ${rate.label}"
        player.setDisplayText(title, subtitle)
        TickLog.record(
            TickLog.Entry(
                sequence = sequence,
                elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                wallClockMs = now,
                title = title,
            )
        )
        Log.i(TAG, "push #$sequence title=\"$title\"")
    }

    private fun setRate(newRate: TickerRate) {
        if (newRate == rate) return
        rate = newRate
        Log.i(TAG, "push rate -> ${newRate.label}")
        librarySession?.setCustomLayout(ImmutableList.of(cycleRateButton()))
    }

    // --- session plumbing -------------------------------------------------------------

    private fun sessionActivity(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun cycleRateButton(): CommandButton =
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName("Rate: ${rate.label}")
            .setCustomIconResId(R.drawable.ic_speed)
            .setSessionCommand(SessionCommand(COMMAND_CYCLE_RATE, Bundle.EMPTY))
            .setEnabled(true)
            .build()

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            Log.i(TAG, "controller connected: ${controller.packageName}")
            val sessionCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                    .add(SessionCommand(COMMAND_CYCLE_RATE, Bundle.EMPTY))
                    .add(SessionCommand(COMMAND_SET_RATE, Bundle.EMPTY))
                    .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(sessionCommands)
                .setCustomLayout(ImmutableList.of(cycleRateButton()))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = when (customCommand.customAction) {
            COMMAND_CYCLE_RATE -> {
                setRate(rate.next())
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            COMMAND_SET_RATE -> {
                val name = args.getString(ARG_RATE_NAME)
                val requested = TickerRate.entries.firstOrNull { it.name == name }
                if (requested == null) {
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                } else {
                    setRate(requested)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }

            else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val children = if (parentId == ROOT_ID) {
                ImmutableList.of(nowPlayingItem())
            } else {
                ImmutableList.of()
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(children, params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = when (mediaId) {
            ROOT_ID -> Futures.immediateFuture(LibraryResult.ofItem(rootItem(), null))
            MirrorPlayer.NOW_PLAYING_UID ->
                Futures.immediateFuture(LibraryResult.ofItem(nowPlayingItem(), null))

            else -> Futures.immediateFuture(
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        /**
         * Tapping the single browse entry should not change what we show — we
         * only ever mirror Spotify — so resolve it to the item we already have.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mutableListOf(nowPlayingItem()))
    }

    private fun rootItem(): MediaItem = MediaItem.Builder()
        .setMediaId(ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("LyricLane")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()

    private fun nowPlayingItem(): MediaItem = MediaItem.Builder()
        .setMediaId(MirrorPlayer.NOW_PLAYING_UID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Now playing on Spotify")
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
        )
        .build()

    /**
     * A flat placeholder cover so the dashboard card is laid out the same way it
     * will be once Spotify's real artwork is forwarded in Phase 1.
     */
    private fun placeholderArtworkPng(): ByteArray {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(0x12, 0x14, 0x1A))
        val paint = Paint().apply {
            isAntiAlias = true
            color = Color.rgb(0x1D, 0xB9, 0x54)
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 3f, paint)
        paint.color = Color.rgb(0x12, 0x14, 0x1A)
        canvas.drawCircle(size / 2f, size / 2f, size / 9f, paint)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    companion object {
        private const val TAG = "LyricLane"
        private const val SESSION_ID = "LyricLane"
        const val ROOT_ID = "lyriclane_root"
        const val COMMAND_CYCLE_RATE = "dev.lyriclane.CYCLE_RATE"
        const val COMMAND_SET_RATE = "dev.lyriclane.SET_RATE"
        const val ARG_RATE_NAME = "rate_name"
    }
}
