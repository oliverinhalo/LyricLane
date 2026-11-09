package dev.lyriclane.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextNormalizerTest {

    @Test
    fun `strips remaster decorations Spotify adds`() {
        assertThat(TextNormalizer.normalize("Bohemian Rhapsody - Remastered 2011"))
            .isEqualTo("bohemian rhapsody")
        assertThat(TextNormalizer.normalize("Come Together (Remastered 2009)"))
            .isEqualTo("come together")
        assertThat(TextNormalizer.normalize("Song (Radio Edit)")).isEqualTo("song")
    }

    @Test
    fun `strips featured artists`() {
        assertThat(TextNormalizer.normalize("Stay (feat. Justin Bieber)")).isEqualTo("stay")
        assertThat(TextNormalizer.normalize("Song ft. Someone")).isEqualTo("song")
    }

    @Test
    fun `folds accents and punctuation`() {
        assertThat(TextNormalizer.normalize("Déjà Vu")).isEqualTo("deja vu")
        assertThat(TextNormalizer.normalize("Don’t Stop Me Now"))
            .isEqualTo("don t stop me now")
    }

    @Test
    fun `primary artist takes the first of a list`() {
        assertThat(TextNormalizer.primaryArtist("The Kid LAROI, Justin Bieber"))
            .isEqualTo("The Kid LAROI")
        assertThat(TextNormalizer.primaryArtist("Simon & Garfunkel")).isEqualTo("Simon")
        assertThat(TextNormalizer.primaryArtist("Queen")).isEqualTo("Queen")
    }

    @Test
    fun `similarity is one for equivalent titles and zero for unrelated ones`() {
        assertThat(TextNormalizer.similarity("Bohemian Rhapsody", "bohemian rhapsody"))
            .isEqualTo(1.0)
        assertThat(
            TextNormalizer.similarity("Bohemian Rhapsody - Remastered 2011", "Bohemian Rhapsody")
        ).isEqualTo(1.0)
        assertThat(TextNormalizer.similarity("Bohemian Rhapsody", "Thriller")).isEqualTo(0.0)
    }
}
