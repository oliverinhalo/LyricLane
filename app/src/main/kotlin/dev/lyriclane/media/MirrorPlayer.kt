package dev.lyriclane.media

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The Android-Auto-facing player.
 *
 * It never produces audio and never takes audio focus: Spotify keeps playing
 * underneath. All it does is *describe* a playback state to the MediaSession so
 * that Android Auto renders a now-playing screen and a dashboard card for us.
 *
 * Phase 0: the described state is synthetic (a fake one-hour track) and the
 * title is driven by [LyricLaneService]'s ticker. Phase 4 replaces the setters
 * with values read from Spotify's own MediaController and routes the transport
 * callbacks to Spotify's TransportControls.
 */
@UnstableApi
class MirrorPlayer(looper: Looper) : SimpleBasePlayer(looper) {

    /** Transport intents from the car. Phase 4 points these at Spotify. */
    interface TransportSink {
        fun onPlayWhenReadyRequested(playWhenReady: Boolean)
        fun onSeekRequested(positionMs: Long)
        fun onSkipToNextRequested()
        fun onSkipToPreviousRequested()
    }

    var transportSink: TransportSink? = null

    private var playWhenReady = true
    private var durationMs = DEFAULT_DURATION_MS
    private var anchorPositionMs = 0L
    private var positionSupplier: PositionSupplier =
        PositionSupplier.getExtrapolating(/* currentPositionMs= */ 0L, /* playbackSpeed= */ 1f)

    private var itemUid: String = NOW_PLAYING_UID
    private var title: String = "LyricLane"
    private var subtitle: String = "Waiting for the first tick"
    private var artworkData: ByteArray? = null

    override fun getState(): State =
        State.Builder()
            .setAvailableCommands(AVAILABLE_COMMANDS)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(
                listOf(
                    MediaItemData.Builder(itemUid)
                        .setMediaItem(buildMediaItem())
                        .setMediaMetadata(buildMetadata())
                        .setDurationUs(durationMs * 1_000L)
                        .setIsSeekable(true)
                        .setIsDynamic(false)
                        .build()
                )
            )
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(positionSupplier)
            .build()

    // --- state the service pushes in -------------------------------------------------

    /**
     * Replaces the text Android Auto shows. Cheap enough to call on every lyric
     * line: it only rebuilds one MediaItem and asks Media3 to diff the state.
     */
    fun setDisplayText(title: String, subtitle: String) {
        if (this.title == title && this.subtitle == subtitle) return
        this.title = title
        this.subtitle = subtitle
        invalidateState()
    }

    fun setArtwork(pngBytes: ByteArray?) {
        if (artworkData.contentEquals(pngBytes)) return
        artworkData = pngBytes
        invalidateState()
    }

    /**
     * Re-anchors the reported position. Called on every resync in later phases;
     * between calls the position extrapolates from the clock, so the car's
     * progress bar moves without us pushing anything.
     */
    fun setPosition(positionMs: Long, durationMs: Long, advancing: Boolean, speed: Float = 1f) {
        anchorPositionMs = positionMs
        this.durationMs = durationMs
        positionSupplier = if (advancing) {
            PositionSupplier.getExtrapolating(positionMs, speed)
        } else {
            PositionSupplier.getConstant(positionMs)
        }
        invalidateState()
    }

    fun setPlaying(playing: Boolean) {
        if (playWhenReady == playing) return
        playWhenReady = playing
        // Freeze or resume the extrapolation so the progress bar matches.
        positionSupplier = if (playing) {
            PositionSupplier.getExtrapolating(positionSupplier.get(), 1f)
        } else {
            PositionSupplier.getConstant(positionSupplier.get())
        }
        invalidateState()
    }

    // --- transport coming back from the car -----------------------------------------

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        transportSink?.onPlayWhenReadyRequested(playWhenReady)
        setPlaying(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        transportSink?.onPlayWhenReadyRequested(false)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            -> transportSink?.onSkipToNextRequested()

            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            -> transportSink?.onSkipToPreviousRequested()

            else -> transportSink?.onSeekRequested(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    /**
     * Android Auto's browse tree sends a play request as "set these items".
     * We have nothing to set — we only ever mirror — so we accept and ignore it
     * rather than let the car surface an error.
     */
    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> = Futures.immediateVoidFuture()

    // --- metadata -------------------------------------------------------------------

    private fun buildMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(itemUid)
            .setMediaMetadata(buildMetadata())
            .build()

    private fun buildMetadata(): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(subtitle)
            .setDisplayTitle(title)
            .setSubtitle(subtitle)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .apply {
                artworkData?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
            }
            .build()

    companion object {
        const val NOW_PLAYING_UID = "now_playing_on_spotify"
        private const val DEFAULT_DURATION_MS = 60 * 60 * 1000L

        private val AVAILABLE_COMMANDS: Player.Commands =
            Player.Commands.Builder()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_PREPARE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SET_MEDIA_ITEM,
                )
                .build()
    }
}
