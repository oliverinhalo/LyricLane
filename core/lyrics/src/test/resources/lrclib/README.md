# LRCLIB fixtures

Recorded from live `lrclib.net` responses so the matcher and parsers are tested
against the shape of real data: the twenty near-duplicate entries a popular
song returns, real durations, real timestamp formats.

**The lyric text has been replaced with placeholder words.** Only the structure
matters to these tests — timings, line counts, candidate metadata — and song
lyrics are not ours to redistribute. Track titles, artists, albums and
durations are kept as returned, because the matcher is scored on them.

Regenerating these means re-fetching from LRCLIB and scrubbing the text again.
