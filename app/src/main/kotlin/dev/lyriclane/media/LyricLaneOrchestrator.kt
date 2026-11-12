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
import dev.lyriclane.recognize.MicSampler
import dev.lyriclane.recognize.Recognition
import dev.lyriclane.recognize.RecognitionFailure
import dev.lyriclane.recognize.RecognitionResult
import dev.lyriclane.recognize.SongRecognizer
import dev.lyriclane.spotify.SpotifyNotificationListener
import dev.lyriclane.spotify.SpotifyPlayback
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
import kotlinx.coroutines.delay
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
    private val recognizer: SongRecognizer,
    private val sampler: MicSampler,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = LyricSyncEngine(scope, TimeSource { SystemClock.elapsedRealtime() })

    /**
     * What is playing, from whichever source knows.
     *
     * Spotify's own session wins whenever it has something: it is exact, it
     * keeps correcting the position, and it costs nothing. A microphone
     * identification fills the gap when Spotify is silent — the radio, a
     * passenger's phone, a CD — and lapses once the song it heard would have
     * finished.
     */
    private val _spotify = MutableStateFlow(SpotifyState())
    val spotify: StateFlow<SpotifyState> = _spotify.asStateFlow()
    val frame: StateFlow<LyricSyncEngine.Frame> = engine.frame
    val settings: StateFlow<SettingsStore.Settings> = settingsStore.settings

    private val _lyrics = MutableStateFlow<LyricsResult>(LyricsResult.Idle)
    val lyrics: StateFlow<LyricsResult> = _lyrics.asStateFlow()

    private val _identity = MutableStateFlow<TrackIdentity?>(null)
    val identity: StateFlow<TrackIdentity?> = _identity.asStateFlow()

    private val _offsetMs = MutableStateFlow(0L)
    val offsetMs: StateFlow<Long> = _offsetMs.asStateFlow()

    /** Why the last lookup ended as it did; shown when no lyrics were found. */
    /** State of the last microphone identification, for the UI to report. */
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _lastRecognition = MutableStateFlow<RecognitionFailure?>(null)
    val lastRecognitionFailure: StateFlow<RecognitionFailure?> = _lastRecognition.asStateFlow()

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

    private var heard: HeardSong? = null
    private var heardExpiryJob: Job? = null

    fun start() {
        if (started) return
        started = true
        watcher.start()
        scope.launch {
            watcher.state.collect { publishState() }
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

    /**
     * Picks the source and pushes the result through the pipeline.
     *
     * Spotify wins whenever it has a track: it is exact and it keeps correcting
     * the position. A song heard through the microphone only fills the silence,
     * and is dropped the moment Spotify has something to say.
     */
    private fun publishState() {
        val fromSpotify = watcher.state.value
        val effective = when {
            fromSpotify.track != null -> {
                forgetHeardSong()
                fromSpotify
            }

            else -> heard?.toState(fromSpotify.permissionGranted) ?: fromSpotify
        }
        _spotify.value = effective
        applyState(effective)
    }

    private fun applyState(state: SpotifyState) {
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

    // --- identifying a song by ear ---------------------------------------------------

    /**
     * Records a few seconds and asks the recognition service what it is.
     *
     * For when Spotify is not the source: the car radio, a passenger's phone.
     * The service reports how far into the track the sample was taken, which is
     * what makes synced lyrics possible at all — the position then runs on from
     * that anchor, drifting slowly, with the offset control to correct it.
     */
    fun identifyByEar() {
        if (_listening.value) return
        scope.launch {
            _listening.value = true
            _lastRecognition.value = null
            try {
                if (!recognizer.isConfigured) {
                    _lastRecognition.value = RecognitionFailure.NotConfigured
                    return@launch
                }
                val sample = try {
                    sampler.record(SAMPLE_MS)
                } catch (e: MicSampler.RecordingFailed) {
                    Log.w(TAG, "could not record", e)
                    _lastRecognition.value =
                        RecognitionFailure.CannotRecord(e.message ?: "Could not record")
                    return@launch
                }

                when (val outcome = recognizer.identify(sample)) {
                    is RecognitionResult.Identified -> {
                        Log.i(TAG, "heard \"${outcome.recognition.title}\"")
                        rememberHeardSong(outcome.recognition, sample.startedAtElapsedRealtimeMs)
                    }

                    is RecognitionResult.Failed -> {
                        Log.i(TAG, "not identified: ${outcome.failure}")
                        _lastRecognition.value = outcome.failure
                    }
                }
            } finally {
                _listening.value = false
            }
        }
    }

    private fun rememberHeardSong(recognition: Recognition, anchorElapsedRealtimeMs: Long) {
        heard = HeardSong(recognition, anchorElapsedRealtimeMs)
        heardExpiryJob?.cancel()
        // Let it lapse when the song it heard would have ended, rather than
        // leaving a stale lyric on the car screen indefinitely.
        val remaining = recognition.durationMs - recognition.playOffsetMs
        if (remaining > 0) {
            heardExpiryJob = scope.launch {
                delay(remaining)
                forgetHeardSong()
                publishState()
            }
        }
        publishState()
    }

    private fun forgetHeardSong() {
        if (heard == null) return
        heard = null
        heardExpiryJob?.cancel()
        heardExpiryJob = null
    }

    /** Drops the heard song and goes back to whatever Spotify says. */
    fun stopListening() {
        forgetHeardSong()
        publishState()
    }

    private data class HeardSong(
        val recognition: Recognition,
        val anchorElapsedRealtimeMs: Long,
    ) {
        fun toState(permissionGranted: Boolean) = SpotifyState(
            permissionGranted = permissionGranted,
            spotifyRunning = true,
            track = SpotifyTrack(
                title = recognition.title,
                artist = recognition.artist,
                album = recognition.album,
                durationMs = recognition.durationMs,
                artwork = null,
                isAdvertisement = false,
            ),
            playback = SpotifyPlayback(
                isPlaying = true,
                positionMs = recognition.playOffsetMs,
                speed = 1f,
                // The service's offset describes the moment the clip started,
                // so that is where the clock is anchored.
                updatedAtElapsedRealtime = anchorElapsedRealtimeMs,
            ),
            fromMicrophone = true,
        )
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

        /** Long enough to identify reliably, short enough to upload quickly. */
        const val SAMPLE_MS = 8_000L
    }
}
