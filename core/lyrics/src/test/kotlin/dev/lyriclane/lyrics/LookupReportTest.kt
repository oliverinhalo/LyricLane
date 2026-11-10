package dev.lyriclane.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LookupReportTest {

    @Test
    fun `nothing found says so`() {
        val report = LookupReport(
            listOf(LookupReport.Attempt(LyricsSourceId.LRCLIB, candidates = 0, accepted = 0))
        )

        assertThat(report.summary()).contains("nothing for this title")
    }

    @Test
    fun `candidates found but rejected names the closest and how far off it was`() {
        val report = LookupReport(
            listOf(
                LookupReport.Attempt(
                    source = LyricsSourceId.LRCLIB,
                    candidates = 20,
                    accepted = 0,
                    bestTitle = "A Song (Live)",
                    closestDurationDeltaMs = 42_000,
                )
            )
        )

        val summary = report.summary()

        assertThat(summary).contains("20 found")
        assertThat(summary).contains("none matched this recording")
        assertThat(summary).contains("42 s off")
        assertThat(summary).contains("A Song (Live)")
    }

    @Test
    fun `an unreachable source reports its error instead`() {
        val report = LookupReport(
            listOf(
                LookupReport.Attempt(
                    source = LyricsSourceId.NETEASE,
                    candidates = 0,
                    accepted = 0,
                    error = "Could not reach NetEase",
                )
            )
        )

        assertThat(report.summary()).isEqualTo("NetEase: Could not reach NetEase")
    }

    @Test
    fun `every source gets its own line`() {
        val report = LookupReport(
            listOf(
                LookupReport.Attempt(LyricsSourceId.LRCLIB, 20, 0, closestDurationDeltaMs = 30_000),
                LookupReport.Attempt(LyricsSourceId.NETEASE, 3, 1),
            )
        )

        assertThat(report.summary().lines()).hasSize(2)
        assertThat(report.summary()).contains("1 of 3 matched")
    }

    @Test
    fun `small differences are shown to a tenth of a second`() {
        assertThat(LookupReport.formatDelta(1_400)).isEqualTo("1.4 s")
        assertThat(LookupReport.formatDelta(42_000)).isEqualTo("42 s")
    }
}
