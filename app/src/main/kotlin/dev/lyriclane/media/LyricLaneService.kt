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
                    // units may not; and a cluster or head-up display reads the
                    // title, which is a third screen with its own opinion.
                    val fields = CarSlotMapping.map(
                        display = display,
                        slots = settings.current.carTextSlots,
                        lyricInTitle = settings.current.lyricInTitle,
                    )
                    player.setDisplayText(fields.title, fields.subtitle)
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
            val children = when (parentId) {
                ROOT_ID -> ImmutableList.of(nowPlayingItem(), settingsFolder())
                SETTINGS_ID -> ImmutableList.copyOf(settingsItems())
                else -> ImmutableList.of()
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(children, params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = when {
            mediaId == ROOT_ID ->
                Futures.immediateFuture(LibraryResult.ofItem(rootItem(), null))

            mediaId == MirrorPlayer.NOW_PLAYING_UID ->
                Futures.immediateFuture(LibraryResult.ofItem(nowPlayingItem(), null))

            mediaId == SETTINGS_ID ->
                Futures.immediateFuture(LibraryResult.ofItem(settingsFolder(), null))

            mediaId.startsWith(SETTING_PREFIX) ->
                settingsItems().firstOrNull { it.mediaId == mediaId }
                    ?.let { Futures.immediateFuture(LibraryResult.ofItem(it, null)) }
                    ?: Futures.immediateFuture(
                        LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                    )

            else -> Futures.immediateFuture(
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        /**
         * A tap in the browse tree is either a setting or "resume Spotify".
         *
         * Android Auto has no way for a media app to show a settings screen, so
         * the settings live in the browse tree as items that toggle when
         * tapped. Tapping one must emphatically *not* start playback.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val settingId = mediaItems.firstOrNull { it.mediaId.startsWith(SETTING_PREFIX) }?.mediaId
            if (settingId != null) {
                applySetting(settingId)
            } else {
                orchestrator.play()
            }
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

    private fun settingsFolder(): MediaItem = browsableItem(
        mediaId = SETTINGS_ID,
        title = "Settings",
        subtitle = "Change these without picking up your phone",
    )

    /**
     * The settings, as browse items labelled with their current value. Tapping
     * one advances it and the list is refreshed in place.
     */
    private fun settingsItems(): List<MediaItem> {
        val current = settings.current
        return listOf(
            settingItem(
                SETTING_LINE_LENGTH,
                "Line length: ${current.carLineLength.label}",
                "${current.carLineLength.maxChars} characters before a line is split",
            ),
            settingItem(
                SETTING_LYRICS,
                "Lyrics: ${onOff(current.lyricsEnabled)}",
                "Off shows the song title and artist instead",
            ),
            settingItem(
                SETTING_SLOTS,
                "Large text: ${current.carTextSlots.label}",
                "Switch if the lyric shows in the small line",
            ),
            settingItem(
                SETTING_TITLE_COPY,
                "Lyric in title too: ${onOff(current.lyricInTitle)}",
                "For instrument clusters and head-up displays",
            ),
            settingItem(
                SETTING_SECONDARY,
                "Smaller line: ${current.secondaryLine.label}",
                "What sits under the lyric",
            ),
            settingItem(
                SETTING_ARTWORK,
                "Album art: ${onOff(current.showArtwork)}",
                "Off gives the words more of the card",
            ),
            settingItem(
                SETTING_RESET_OFFSET,
                "Reset timing for this song",
                "Clears the offset you nudged with Offset ±",
            ),
        )
    }

    /** Advances one setting and tells the car to re-read the list. */
    private fun applySetting(mediaId: String) {
        val current = settings.current
        when (mediaId) {
            SETTING_LINE_LENGTH -> settings.setCarLineLength(current.carLineLength.next())
            SETTING_LYRICS -> settings.setLyricsEnabled(!current.lyricsEnabled)
            SETTING_SLOTS -> settings.setCarTextSlots(
                if (current.carTextSlots == CarTextSlots.SUBTITLE_IS_BIG) {
                    CarTextSlots.TITLE_IS_BIG
                } else {
                    CarTextSlots.SUBTITLE_IS_BIG
                }
            )

            SETTING_TITLE_COPY -> settings.setLyricInTitle(!current.lyricInTitle)
            SETTING_SECONDARY -> settings.setSecondaryLine(
                SecondaryLine.entries[
                    (current.secondaryLine.ordinal + 1) % SecondaryLine.entries.size,
                ]
            )

            SETTING_ARTWORK -> settings.setShowArtwork(!current.showArtwork)
            SETTING_RESET_OFFSET -> orchestrator.setOffset(0L)
            else -> return
        }
        val items = settingsItems()
        librarySession?.notifyChildrenChanged(SETTINGS_ID, items.size, null)
    }

    private fun onOff(enabled: Boolean) = if (enabled) "on" else "off"

    private fun browsableItem(mediaId: String, title: String, subtitle: String) =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()

    private fun settingItem(mediaId: String, title: String, subtitle: String) =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(false)
                    // Playable so the car makes it tappable; the tap is
                    // intercepted in onAddMediaItems and never starts playback.
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
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
        const val SETTINGS_ID = "lyriclane_settings"

        private const val SETTING_PREFIX = "lyriclane_set/"
        private const val SETTING_LINE_LENGTH = SETTING_PREFIX + "line_length"
        private const val SETTING_LYRICS = SETTING_PREFIX + "lyrics"
        private const val SETTING_SLOTS = SETTING_PREFIX + "slots"
        private const val SETTING_TITLE_COPY = SETTING_PREFIX + "title_copy"
        private const val SETTING_SECONDARY = SETTING_PREFIX + "secondary"
        private const val SETTING_ARTWORK = SETTING_PREFIX + "artwork"
        private const val SETTING_RESET_OFFSET = SETTING_PREFIX + "reset_offset"
        const val COMMAND_OFFSET_MINUS = "dev.lyriclane.OFFSET_MINUS"
        const val COMMAND_OFFSET_PLUS = "dev.lyriclane.OFFSET_PLUS"
        const val COMMAND_TOGGLE_LYRICS = "dev.lyriclane.TOGGLE_LYRICS"
    }
}
