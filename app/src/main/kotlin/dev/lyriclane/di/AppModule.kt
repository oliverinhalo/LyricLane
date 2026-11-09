package dev.lyriclane.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.lyriclane.lyrics.LrclibClient
import dev.lyriclane.lyrics.LrclibSource
import dev.lyriclane.lyrics.LyricsDao
import dev.lyriclane.lyrics.LyricsDatabase
import dev.lyriclane.lyrics.LyricsRepository
import dev.lyriclane.lyrics.LyricsSource
import dev.lyriclane.lyrics.NeteaseSource
import dev.lyriclane.spotify.SpotifyWatcher
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        // A car is often on a weak connection. Fail fast and fall back to the
        // cache rather than leaving the screen on "Finding lyrics…".
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun lrclibClient(client: OkHttpClient): LrclibClient = LrclibClient(client)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): LyricsDatabase =
        LyricsDatabase.create(context)

    @Provides
    fun lyricsDao(database: LyricsDatabase): LyricsDao = database.lyricsDao()

    /**
     * Order matters: LRCLIB first because it is better curated and some of its
     * entries carry word-level timings. NetEase is asked only when LRCLIB has
     * nothing convincing.
     */
    @Provides
    @Singleton
    fun lyricsSources(
        client: LrclibClient,
        http: OkHttpClient,
    ): List<@JvmSuppressWildcards LyricsSource> =
        listOf(LrclibSource(client), NeteaseSource(http))

    @Provides
    @Singleton
    fun lyricsRepository(
        dao: LyricsDao,
        sources: List<@JvmSuppressWildcards LyricsSource>,
    ): LyricsRepository = LyricsRepository(dao, sources)

    @Provides
    @Singleton
    fun spotifyWatcher(@ApplicationContext context: Context): SpotifyWatcher =
        SpotifyWatcher(context)
}
