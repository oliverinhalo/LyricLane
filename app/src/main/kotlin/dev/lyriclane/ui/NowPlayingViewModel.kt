package dev.lyriclane.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lyriclane.lyrics.LyricsCandidate
import dev.lyriclane.media.CarDisplay
import dev.lyriclane.media.LyricLaneOrchestrator
import dev.lyriclane.media.CarLineLength
import dev.lyriclane.recognize.AcrCloudConfig
import dev.lyriclane.media.CarTextSlots
import dev.lyriclane.media.SecondaryLine
import dev.lyriclane.media.SettingsStore
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val orchestrator: LyricLaneOrchestrator,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val spotify = orchestrator.spotify
    val lyrics = orchestrator.lyrics
    val frame = orchestrator.frame
    val identity = orchestrator.identity
    val offsetMs = orchestrator.offsetMs
    val lastLookup = orchestrator.lastLookup

    /** The split timeline the engine runs on; the frame's index points into it. */
    val timeline = orchestrator.timeline
    val listening = orchestrator.listening
    val recognitionFailure = orchestrator.lastRecognitionFailure
    val settings = settingsStore.settings

    /** Exactly what the car is being shown, so the phone can verify it. */
    val display = orchestrator.display.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CarDisplay("—", ""),
    )

    var searchQuery by mutableStateOf("")
        private set
    var searchResults by mutableStateOf<List<LyricsCandidate>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var cachedCount by mutableStateOf(0)
        private set

    init {
        orchestrator.start()
        refreshCachedCount()
    }

    fun onResume() = orchestrator.refresh()

    fun nudgeOffset(deltaMs: Long) = orchestrator.nudgeOffset(deltaMs)
    fun resetOffset() = orchestrator.setOffset(0L)
    fun retry() = orchestrator.retryCurrent()
    fun stopListening() = orchestrator.stopListening()
    fun setRecognition(config: AcrCloudConfig) = settingsStore.setRecognition(config)

    fun setSecondaryLine(mode: SecondaryLine) = settingsStore.setSecondaryLine(mode)
    fun setCarTextSlots(slots: CarTextSlots) = settingsStore.setCarTextSlots(slots)
    fun setShowArtwork(show: Boolean) = settingsStore.setShowArtwork(show)
    fun setLyricInTitle(enabled: Boolean) = settingsStore.setLyricInTitle(enabled)
    fun setCarLineLength(length: CarLineLength) = settingsStore.setCarLineLength(length)
    fun setPackTwoLines(pack: Boolean) = settingsStore.setPackTwoLines(pack)
    fun setLyricTextScale(scale: Float) = settingsStore.setLyricTextScale(scale)
    fun setDefaultOffset(ms: Long) = settingsStore.setDefaultOffsetMs(ms)
    fun setMinHold(ms: Long) = settingsStore.setMinHoldMs(ms)
    fun setLyricsEnabled(enabled: Boolean) = settingsStore.setLyricsEnabled(enabled)

    fun clearCache() {
        viewModelScope.launch {
            orchestrator.clearCache()
            refreshCachedCount()
        }
    }

    fun onSearchQueryChange(value: String) {
        searchQuery = value
    }

    /** Pre-fills the picker with the current song, which is what it is usually for. */
    fun prepareSearch() {
        val current = identity.value
        searchQuery = listOfNotNull(current?.title, current?.artist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        searchResults = emptyList()
    }

    fun runSearch() {
        val query = searchQuery.trim()
        if (query.isEmpty()) return
        searching = true
        viewModelScope.launch {
            searchResults = orchestrator.search(query, artist = null)
            searching = false
        }
    }

    fun apply(candidate: LyricsCandidate) {
        orchestrator.applyCandidate(candidate)
        searchResults = emptyList()
        refreshCachedCount()
    }

    private fun refreshCachedCount() {
        viewModelScope.launch { cachedCount = orchestrator.cachedCount() }
    }

    companion object {
        val OFFSET_STEP_MS = SettingsStore.OFFSET_STEP_MS
    }
}
