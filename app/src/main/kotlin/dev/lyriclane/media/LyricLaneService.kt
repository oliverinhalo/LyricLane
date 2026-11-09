package dev.lyriclane.media

import android.app.PendingIntent
import android.content.Intent
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
import dagger.hilt.android.AndroidEntryPoint
import dev.lyriclane.R
import dev.lyriclane.media.MetadataPacing.pace
import dev.lyriclane.ui.MainActivity
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The media app Android Auto sees.
 *
 * It publishes a session whose *title is the current lyric line*. That is the
 * whole trick: Android Auto renders both the now-playing screen and the
 * split-screen dashboard card from this metadata, and gives media apps no way
 * to draw anything of their own.
 *
 * It plays no audio and takes no audio focus — Spotify keeps playing
 * underneath, and every transport control the car sends is forwarded to it.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class LyricLaneService : MediaLibraryService() {

    @Inject lateinit var orchestrator: LyricLaneOrchestrator

    @Inject lateinit var settings: SettingsStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var player: MirrorPlayer
    private var librarySession: MediaLibrarySession? = null

    /** Key of whatever artwork the player currently holds; see [observeSpotify]. */
    private var artworkSignature: String? = null

    override fun onCreate() {
        super.onCreate()
        player = MirrorPlayer(Looper.getMainLooper())
        player.transportSink = object : MirrorPlayer.TransportSink {
            override fun onPlayWhenReadyRequested(playWhenReady: Boolean) {
                if (playWhenReady) orchestrator.play() else orchestrator.pause()
            }

            override fun onSeekRequested(positionMs: Long) {
                orchestrator.seekTo(positionMs)
            }

            override fun onSkipToNextRequested() {
                orchestrator.skipToNext()
            }

            override fun onSkipToPreviousRequested() {
                orchestrator.skipToPrevious()
            }
        }

        librarySession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setId("LyricLane")
            .setSessionActivity(sessionActivity())
            .setCustomLayout(customLayout())
            .build()

        orchestrator.start()
        observeLyrics()
        observeSpotify()
        observeSettings()
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

    // --- wiring ------------------------------------------------------------------------

    @kotlin.OptIn(ExperimentalCoroutinesApi::class)
    private fun observeLyrics() {
        scope.launch {
            // Re-arm the pacing whenever the user changes the minimum hold.
            settings.settings
                .map { it.minHoldMs }
                .distinctUntilChanged()
                .flatMapLatest { holdMs -> orchestrator.display.pace(holdMs) }
                .collect { display ->
                    // Which field the head unit renders large is a setting,
                    // because Android Auto draws the *subtitle* big and other
                    // units may not.
                    when (settings.current.carTextSlots) {
                        CarTextSlots.SUBTITLE_IS_BIG ->
                            player.setDisplayText(display.secondary, display.primary)

                        CarTextSlots.TITLE_IS_BIG ->
                            player.setDisplayText(display.primary, display.secondary)
                    }
                }
        }
    }

    private fun observeSpotify() {
        scope.launch {
            orchestrator.spotify.collect { state ->
                val track = state.track
                val playback = state.playback
                player.setTrackUid(track?.signature ?: IDLE_UID)
                player.setPosition(
                    positionMs = playback?.positionMsAt(SystemClock.elapsedRealtime()) ?: 0L,
                    durationMs = track?.durationMs ?: 0L,
                    advancing = state.isPlaying,
                    speed = playback?.speed ?: 1f,
                )
                player.setPlaying(state.isPlaying)

                // Re-encoding album art is expensive, so it is only redone
                // when the song changes or the setting is toggled. The key
                // includes the setting so "off" is distinguishable from "no
                // track yet".
                val showArtwork = settings.current.showArtwork
                val artworkKey = if (showArtwork) "art:${track?.signature}" else "off"
                if (artworkKey != artworkSignature) {
                    artworkSignature = artworkKey
                    player.setArtwork(if (showArtwork) Artwork.toBytes(track?.artwork) else null)
                }
            }
        }
    }

    private fun observeSettings() {
        scope.launch {
            settings.settings
                .map { it.lyricsEnabled to it.showArtwork }
                .distinctUntilChanged()
                .collect {
                    librarySession?.setCustomLayout(customLayout())
                    // Force the next Spotify update to re-evaluate artwork so
                    // toggling it takes effect without waiting for a new song.
                    artworkSignature = "stale"
                }
        }
    }

    // --- custom actions shown in the car ----------------------------------------------

    private fun customLayout(): ImmutableList<CommandButton> = ImmutableList.of(
        button(COMMAND_OFFSET_MINUS, "Offset −", R.drawable.ic_offset_minus),
        button(COMMAND_OFFSET_PLUS, "Offset +", R.drawable.ic_offset_plus),
        button(
            COMMAND_TOGGLE_LYRICS,
            if (settings.current.lyricsEnabled) "Lyrics on" else "Lyrics off",
            if (settings.current.lyricsEnabled) R.drawable.ic_lyrics_on else R.drawable.ic_lyrics_off,
        ),
    )

    private fun button(action: String, label: String, iconRes: Int): CommandButton =
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(label)
            .setCustomIconResId(iconRes)
            .setSessionCommand(SessionCommand(action, Bundle.EMPTY))
            .setEnabled(true)
            .build()

    private fun sessionActivity(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            Log.i(TAG, "controller connected: ${controller.packageName}")
            // A controller connecting is also the moment the car opens us, which
            // is a good time to re-check whether we can see Spotify at all.
            orchestrator.refresh()
            val sessionCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                    .add(SessionCommand(COMMAND_OFFSET_MINUS, Bundle.EMPTY))
                    .add(SessionCommand(COMMAND_OFFSET_PLUS, Bundle.EMPTY))
                    .add(SessionCommand(COMMAND_TOGGLE_LYRICS, Bundle.EMPTY))
                    .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(sessionCommands)
                .setCustomLayout(customLayout())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                COMMAND_OFFSET_MINUS -> orchestrator.nudgeOffset(-SettingsStore.OFFSET_STEP_MS)
                COMMAND_OFFSET_PLUS -> orchestrator.nudgeOffset(SettingsStore.OFFSET_STEP_MS)
                COMMAND_TOGGLE_LYRICS -> orchestrator.toggleLyrics()
                else -> return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
                )
            }
            librarySession?.setCustomLayout(customLayout())
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
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
         * The browse tree has one entry and we never change what we mirror, so a
         * tap on it means "resume Spotify" rather than "play this".
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            orchestrator.play()
            return Futures.immediateFuture(mutableListOf(nowPlayingItem()))
        }
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

    companion object {
        private const val TAG = "LyricLane"
        private const val IDLE_UID = "lyriclane_idle"
        const val ROOT_ID = "lyriclane_root"
        const val COMMAND_OFFSET_MINUS = "dev.lyriclane.OFFSET_MINUS"
        const val COMMAND_OFFSET_PLUS = "dev.lyriclane.OFFSET_PLUS"
        const val COMMAND_TOGGLE_LYRICS = "dev.lyriclane.TOGGLE_LYRICS"
    }
}
