package dev.lyriclane.media

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App settings. SharedPreferences rather than DataStore: there are four values,
 * they are read synchronously when the service starts, and avoiding another
 * dependency keeps the sideloaded APK honest.
 */
@Singleton
class SettingsStore @Inject constructor(@ApplicationContext context: Context) {

    data class Settings(
        val secondaryLine: SecondaryLine = SecondaryLine.NEXT_LINE,
        val carTextSlots: CarTextSlots = CarTextSlots.SUBTITLE_IS_BIG,
        val defaultOffsetMs: Long = 0L,
        val minHoldMs: Long = MetadataPacing.DEFAULT_MIN_INTERVAL_MS,
        val lyricsEnabled: Boolean = true,
        val showArtwork: Boolean = true,
        /**
         * Mirror the lyric into the title as well, for instrument clusters and
         * head-up displays, which read the title rather than the subtitle.
         */
        val lyricInTitle: Boolean = false,
        /** How much text goes on one car line before it is split. */
        val carLineLength: CarLineLength = CarLineLength.DEFAULT,
        val packTwoLines: Boolean = false,
        /** Multiplier on the lyric text size on the phone screen. */
        val lyricTextScale: Float = DEFAULT_TEXT_SCALE,
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("lyriclane", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    fun setSecondaryLine(mode: SecondaryLine) = edit { putString(KEY_SECONDARY, mode.name) }

    fun setCarTextSlots(slots: CarTextSlots) = edit { putString(KEY_SLOTS, slots.name) }

    fun setShowArtwork(show: Boolean) = edit { putBoolean(KEY_SHOW_ARTWORK, show) }

    fun setLyricInTitle(enabled: Boolean) = edit { putBoolean(KEY_LYRIC_IN_TITLE, enabled) }

    fun setCarLineLength(length: CarLineLength) = edit { putString(KEY_LINE_LENGTH, length.name) }

    fun setPackTwoLines(pack: Boolean) = edit { putBoolean(KEY_PACK_TWO_LINES, pack) }

    fun setLyricTextScale(scale: Float) =
        edit { putFloat(KEY_TEXT_SCALE, scale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)) }

    fun setDefaultOffsetMs(ms: Long) =
        edit { putLong(KEY_DEFAULT_OFFSET, ms.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)) }

    fun setMinHoldMs(ms: Long) =
        edit { putLong(KEY_MIN_HOLD, ms.coerceIn(0L, 2_000L)) }

    fun setLyricsEnabled(enabled: Boolean) = edit { putBoolean(KEY_LYRICS_ON, enabled) }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _settings.value = read()
    }

    private fun read() = Settings(
        secondaryLine = runCatching {
            SecondaryLine.valueOf(prefs.getString(KEY_SECONDARY, SecondaryLine.NEXT_LINE.name)!!)
        }.getOrDefault(SecondaryLine.NEXT_LINE),
        carTextSlots = runCatching {
            CarTextSlots.valueOf(prefs.getString(KEY_SLOTS, CarTextSlots.SUBTITLE_IS_BIG.name)!!)
        }.getOrDefault(CarTextSlots.SUBTITLE_IS_BIG),
        defaultOffsetMs = prefs.getLong(KEY_DEFAULT_OFFSET, 0L),
        minHoldMs = prefs.getLong(KEY_MIN_HOLD, MetadataPacing.DEFAULT_MIN_INTERVAL_MS),
        lyricsEnabled = prefs.getBoolean(KEY_LYRICS_ON, true),
        showArtwork = prefs.getBoolean(KEY_SHOW_ARTWORK, true),
        lyricInTitle = prefs.getBoolean(KEY_LYRIC_IN_TITLE, false),
        carLineLength = runCatching {
            CarLineLength.valueOf(prefs.getString(KEY_LINE_LENGTH, CarLineLength.DEFAULT.name)!!)
        }.getOrDefault(CarLineLength.DEFAULT),
        packTwoLines = prefs.getBoolean(KEY_PACK_TWO_LINES, false),
        lyricTextScale = prefs.getFloat(KEY_TEXT_SCALE, DEFAULT_TEXT_SCALE),
    )

    companion object {
        /** Offsets beyond five seconds mean the lyrics are simply wrong. */
        const val MAX_OFFSET_MS = 5_000L

        /** The step the car's Offset +/- actions move by. */
        const val OFFSET_STEP_MS = 250L

        /** Lyrics are the point of the app, so they start comfortably large. */
        const val DEFAULT_TEXT_SCALE = 1.45f
        const val MIN_TEXT_SCALE = 0.8f
        const val MAX_TEXT_SCALE = 2.6f

        private const val KEY_SECONDARY = "secondary_line"
        private const val KEY_SLOTS = "car_text_slots"
        private const val KEY_DEFAULT_OFFSET = "default_offset_ms"
        private const val KEY_MIN_HOLD = "min_hold_ms"
        private const val KEY_LYRICS_ON = "lyrics_enabled"
        private const val KEY_SHOW_ARTWORK = "show_artwork"
        private const val KEY_LYRIC_IN_TITLE = "lyric_in_title"
        private const val KEY_LINE_LENGTH = "car_line_length"
        private const val KEY_PACK_TWO_LINES = "pack_two_lines"
        private const val KEY_TEXT_SCALE = "lyric_text_scale"
    }
}
