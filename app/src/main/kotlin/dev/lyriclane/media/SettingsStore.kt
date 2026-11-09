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
        val subtitleMode: SubtitleMode = SubtitleMode.NEXT_LINE,
        val defaultOffsetMs: Long = 0L,
        val minHoldMs: Long = MetadataPacing.DEFAULT_MIN_INTERVAL_MS,
        val lyricsEnabled: Boolean = true,
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("lyriclane", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    fun setSubtitleMode(mode: SubtitleMode) = edit { putString(KEY_SUBTITLE, mode.name) }

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
        subtitleMode = runCatching {
            SubtitleMode.valueOf(
                prefs.getString(KEY_SUBTITLE, SubtitleMode.NEXT_LINE.name)!!
            )
        }.getOrDefault(SubtitleMode.NEXT_LINE),
        defaultOffsetMs = prefs.getLong(KEY_DEFAULT_OFFSET, 0L),
        minHoldMs = prefs.getLong(KEY_MIN_HOLD, MetadataPacing.DEFAULT_MIN_INTERVAL_MS),
        lyricsEnabled = prefs.getBoolean(KEY_LYRICS_ON, true),
    )

    companion object {
        /** Offsets beyond five seconds mean the lyrics are simply wrong. */
        const val MAX_OFFSET_MS = 5_000L

        /** The step the car's Offset +/- actions move by. */
        const val OFFSET_STEP_MS = 250L

        private const val KEY_SUBTITLE = "subtitle_mode"
        private const val KEY_DEFAULT_OFFSET = "default_offset_ms"
        private const val KEY_MIN_HOLD = "min_hold_ms"
        private const val KEY_LYRICS_ON = "lyrics_enabled"
    }
}
