package dev.lyriclane.media

import android.os.SystemClock
import android.util.Log
import dev.lyriclane.lyrics.LookupReport
import dev.lyriclane.lyrics.LyricLine
import dev.lyriclane.lyrics.LyricSyncEngine
import dev.lyriclane.lyrics.LyricTimeline
import dev.lyriclane.lyrics.LyricsCandidate
import dev.lyriclane.lyrics.LyricsRepository
import dev.lyriclane.lyrics.LyricsResult
import dev.lyriclane.lyrics.PlaybackSnapshot
import dev.lyriclane.lyrics.TimeSource
import dev.lyriclane.lyrics.TrackIdentity
import dev.lyriclane.spotify.SpotifyNotificationListener
import dev.lyriclane.spotify.SpotifyState
import dev.lyriclane.spotify.SpotifyTrack
import dev.lyriclane.spotify.SpotifyWatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The brain. Owns the single source of truth that both the car session and the
 * phone UI read from, so the two can never disagree about what is playing.
 *
 * Flow of work: Spotify's session changes -> if the song changed, look up
 * lyrics and load the timeline -> feed every playback change to the sync engine
 * -> the engine decides the current line -> [display] turns that into two lines
 * of text.
 */
@Singleton
class LyricLaneOrchestrator @Inject constructor(
    private val watcher: SpotifyWatcher,
    private val repository: LyricsRepository,
    private val settingsStore: SettingsStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = LyricSyncEngine(scope, TimeSource { SystemClock.elapsedRealtime() })

    val spotify: StateFlow<SpotifyState> = watcher.state
    val frame: StateFlow<LyricSyncEngine.Frame> = engine.frame
    val settings: StateFlow<SettingsStore.Settings> = settingsStore.settings

    private val _lyrics = MutableStateFlow<LyricsResult>(LyricsResult.Idle)
    val lyrics: StateFlow<LyricsResult> = _lyrics.asStateFlow()

    private val _identity = MutableStateFlow<TrackIdentity?>(null)
    val identity: StateFlow<TrackIdentity?> = _identity.asStateFlow()

    private val _offsetMs = MutableStateFlow(0L)
    val offsetMs: StateFlow<Long> = _offsetMs.asStateFlow()

    /** Why the last lookup ended as it did; shown when no lyrics were found. */
    private val _lastLookup = MutableStateFlow<LookupReport?>(null)
    val lastLookup: StateFlow<LookupReport?> = _lastLookup.asStateFlow()

    /**
     * The lines as the engine sees them, after long ones have been split.
     *
     * The phone must render *these* rather than the raw lyrics: the frame's
     * line index points into this list, so showing the unsplit version would
     * highlight the wrong line from the first split onwards.
     */
    private val _timeline = MutableStateFlow<List<LyricLine>>(emptyList())
    val timeline: StateFlow<List<LyricLine>> = _timeline.asStateFlow()

    /** The two lines destined for the car. Also shown on the phone, for parity. */
    val display: Flow<CarDisplay> =
        combine(spotify, lyrics, frame, settings) { spotifyState, lyricsState, currentFrame, config ->
            CarDisplayFormatter.format(
                spotify = spotifyState,
                lyrics = lyricsState,
                frame = currentFrame,
                secondaryLine = config.secondaryLine,
                lyricsEnabled = config.lyricsEnabled,
                packTwoLines = config.packTwoLines,
                maxChars = config.carLineLength.maxChars,
            )
        }.distinctUntilChanged()

    private var started = false
    private var currentSignature: String? = null
    private var lyricsJob: Job? = null

    /** Kept so the timeline can be re-split when the line length changes. */
    private var armedResult: LyricsResult = LyricsResult.Idle
    private var armedDurationMs: Long = 0L

    fun start() {
        if (started) return
        started = true
        watcher.start()
        scope.launch {
            watcher.state.collect(::onSpotifyState)
        }
        // The system binds our notification listener a moment after the user
        // grants access. Without this, the first thing they see after granting
        // is still "notification access needed" until something else nudges us.
        scope.launch {
            SpotifyNotificationListener.connected.collect { connected ->
                if (connected) watcher.refresh()
            }
        }
        // Changing how much text fits on a car line changes where lines are
        // split, so the timeline has to be rebuilt.
        scope.launch {
            settingsStore.settings
                .map { it.carLineLength }
                .distinctUntilChanged()
                .collect { armTimeline(armedResult, armedDurationMs) }
        }
    }

    /**
     * There is no broadcast for "the user granted notification access", so the
     * only way to notice is to look again when the UI comes back to the front.
     */
    fun refresh() {
        if (!started) start() else watcher.refresh()
    }

    fun positionMs(): Long = engine.currentPositionMs()

    // --- transport, straight through to Spotify --------------------------------------

    fun play() {
        watcher.play()
    }

    fun pause() {
        watcher.pause()
    }

    fun skipToNext() {
        watcher.skipToNext()
    }

    fun skipToPrevious() {
        watcher.skipToPrevious()
    }

    fun seekTo(positionMs: Long) {
        watcher.seekTo(positionMs)
    }

    // --- user corrections -------------------------------------------------------------

    fun nudgeOffset(deltaMs: Long) =
        setOffset(_offsetMs.value + deltaMs)

    fun setOffset(offsetMs: Long) {
        val clamped = offsetMs.coerceIn(-SettingsStore.MAX_OFFSET_MS, SettingsStore.MAX_OFFSET_MS)
        _offsetMs.value = clamped
        engine.setOffsetMs(clamped)
        val identity = _identity.value ?: return
        scope.launch { repository.setOffset(identity, clamped) }
    }

    fun toggleLyrics() = settingsStore.setLyricsEnabled(!settingsStore.current.lyricsEnabled)

    suspend fun search(query: String, artist: String?): List<LyricsCandidate> =
        repository.search(query, artist)

    /** Applies a manual pick from the picker and re-arms the timeline with it. */
    fun applyCandidate(candidate: LyricsCandidate) {
        val identity = _identity.value ?: return
        scope.launch {
            val result = repository.applyChoice(identity, candidate)
            _lyrics.value = result
            armTimeline(result, identity.durationMs)
        }
    }

    /** Drops the cached lyrics for this song and looks again. */
    fun retryCurrent() {
        val track = watcher.state.value.track ?: return
        val identity = _identity.value ?: return
        lyricsJob?.cancel()
        lyricsJob = scope.launch {
            repository.forget(identity)
            _lyrics.value = LyricsResult.Loading
            loadLyrics(track)
        }
    }

    suspend fun clearCache() = repository.clearCache()

    suspend fun cachedCount(): Int = repository.cachedCount()

    // --- internals ---------------------------------------------------------------------

    private fun onSpotifyState(state: SpotifyState) {
        val track = state.track
        val signature = track?.signature
        if (signature != currentSignature) {
            currentSignature = signature
            lyricsJob?.cancel()
            // Clear the timeline first: showing the previous song's lyric over a
            // new track is the worst thing this app could do.
            engine.setTimeline(emptyList())
            _identity.value = track?.toIdentity()
            _lastLookup.value = null
            _lyrics.value = if (track == null) LyricsResult.Idle else LyricsResult.Loading
            if (track != null) {
                lyricsJob = scope.launch { loadLyrics(track) }
            }
        }
        engine.setPlayback(state.playback?.toSnapshot())
    }

    private suspend fun loadLyrics(track: SpotifyTrack) {
        val identity = track.toIdentity()
        if (!track.canLookUpLyrics) {
            _lyrics.value = LyricsResult.Idle
            return
        }
        val offset = repository.offsetFor(identity, settingsStore.current.defaultOffsetMs)
        _offsetMs.value = offset
        engine.setOffsetMs(offset)

        val lookup = repository.lyricsFor(identity)
        // The song may have changed while we were waiting on the network.
        if (identity != _identity.value) return
        _lyrics.value = lookup.result
        _lastLookup.value = lookup.report
        armTimeline(lookup.result, track.durationMs)
        Log.i(TAG, "lyrics for ${track.title}: ${lookup.result.describe()}")
    }

    private fun armTimeline(result: LyricsResult, durationMs: Long) {
        armedResult = result
        armedDurationMs = durationMs
        val prepared = if (result is LyricsResult.Synced) {
            LyricTimeline.prepare(
                lines = result.lines,
                maxChars = settingsStore.current.carLineLength.maxChars,
                trackDurationMs = durationMs,
            )
        } else {
            emptyList()
        }
        _timeline.value = prepared
        engine.setTimeline(prepared)
    }

    private fun SpotifyTrack.toIdentity() = TrackIdentity(
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
    )

    private fun dev.lyriclane.spotify.SpotifyPlayback.toSnapshot() = PlaybackSnapshot(
        isPlaying = isPlaying,
        positionMs = positionMs,
        speed = speed,
        atElapsedRealtimeMs = updatedAtElapsedRealtime,
    )

    private fun LyricsResult.describe(): String = when (this) {
        is LyricsResult.Synced ->
            "synced from ${source.label}, ${lines.size} lines" +
                (if (hasLineEndTimes) ", with line end times" else "") +
                " (cache=$fromCache)"
        is LyricsResult.PlainOnly -> "plain only"
        LyricsResult.Instrumental -> "instrumental"
        LyricsResult.NotFound -> "not found"
        is LyricsResult.Error -> "error: $message"
        LyricsResult.Loading -> "loading"
        LyricsResult.Idle -> "idle"
    }

    companion object {
        private const val TAG = "LyricLane"
    }
}
