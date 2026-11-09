package dev.lyriclane.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.lyriclane.lyrics.LrclibClient
import dev.lyriclane.lyrics.LyricsDao
import dev.lyriclane.lyrics.LyricsDatabase
import dev.lyriclane.lyrics.LyricsRepository
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

    @Provides
    @Singleton
    fun lyricsRepository(dao: LyricsDao, client: LrclibClient): LyricsRepository =
        LyricsRepository(dao, client)

    @Provides
    @Singleton
    fun spotifyWatcher(@ApplicationContext context: Context): SpotifyWatcher =
        SpotifyWatcher(context)
}
