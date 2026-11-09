package dev.lyriclane.diag

/**
 * Metadata push rates the Phase 0 spike can be driven at.
 *
 * 2 s is the rate the build plan calls for; the faster rates exist to find the
 * point where Android Auto starts coalescing or dropping updates, which decides
 * whether the real sync engine needs a minimum hold time between lines
 * (see README, "Phase 0 findings").
 */
enum class TickerRate(val intervalMs: Long, val label: String) {
    SLOW(2_000, "2 s"),
    MEDIUM(1_000, "1 s"),
    FAST(500, "500 ms"),
    VERY_FAST(250, "250 ms"),
    ;

    fun next(): TickerRate = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = SLOW
    }
}
