# LyricLane

**Synced lyrics from Spotify, on your Android Auto screen.**

Spotify shows lyrics on your phone but not in the car. LyricLane follows what
Spotify is playing, looks the lyrics up, and publishes the current line as
MediaSession metadata — which is the one thing Android Auto will render for a
media app. Open LyricLane instead of Spotify in the car and the words appear on
the now-playing screen and the split-screen dashboard card, while Spotify keeps
playing underneath.

Personal project. Sideloaded, not on Play. No Spotify login.

---

## Contents

- [How it works](#how-it-works)
- [Install](#install)
- [First run](#first-run)
- [Using it in the car](#using-it-in-the-car)
- [Settings](#settings)
- [Where lyrics come from](#where-lyrics-come-from)
- [When lyrics are wrong or missing](#when-lyrics-are-wrong-or-missing)
- [What Android Auto will and will not let an app do](#what-android-auto-will-and-will-not-let-an-app-do)
- [Building from source](#building-from-source)
- [Testing on the Desktop Head Unit](#testing-on-the-desktop-head-unit)
- [Project layout](#project-layout)
- [Limitations](#limitations)
- [Licence and credits](#licence-and-credits)

---

## How it works

Android Auto does not let a media app draw its own interface. The car renders
the card itself, from the app's MediaSession metadata. So the only way to get a
lyric onto the screen is to put it where the song title normally goes.

```
Spotify's MediaSession          what LyricLane does with it
──────────────────────          ───────────────────────────
track + live position     ──▶   look up lyrics (LRCLIB, then NetEase)
                          ──▶   parse timings, cache in Room
                          ──▶   sync engine sleeps until the next line is due
                          ──▶   publish that line as MediaSession metadata
                                                │
                       play / pause / next ◀────┘  forwarded back to Spotify
```

LyricLane plays no audio and takes no audio focus. It reads Spotify's session
through `MediaSessionManager`, which is why it needs notification-listener
access — that is the only route Android gives one app to another's media
session without a privileged permission.

The sync engine does not poll. It works out the current line, then sleeps until
the exact timestamp of the next one, scaled by playback speed. Paused playback
schedules nothing at all, so a paused phone does no work.

---

## Install

Grab the APK from [Releases](../../releases), or [build it yourself](#building-from-source).

```bash
adb install -r LyricLane-debug.apk
```

Requires Android 8.0 (API 26) or newer, Spotify, and Android Auto.

---

## First run

Three steps, in order. The app will not work until the first one is done, and
will not appear in the car until the second is.

### 1. Notification access

Open LyricLane. It shows a single screen with one button. Tap **Grant
notification access**, enable LyricLane in the system list, and come back — the
screen clears itself as soon as the system binds the listener.

LyricLane does not read your notifications. It asks the system for the active
media session, finds Spotify's, and follows the track and playback position.
The only thing that leaves your phone is the song title and artist, sent to a
lyrics service to look the words up.

### 2. Let Android Auto run a sideloaded app

Android Auto silently hides media apps that did not come from a trusted
source. No warning — the app simply is not in the list.

1. Android Auto settings → scroll to **About** → tap **Version**
2. Tap the **Version and permission info** block **ten times** → **OK**
3. ⋮ → **Developer settings** → turn on **Unknown sources**
4. Force stop Android Auto and reconnect

To check it worked without driving anywhere: Android Auto settings →
**Customise launcher**. LyricLane should be in the list.

### 3. Play something

Press play in Spotify, open LyricLane on the phone. The lyrics should fill the
screen with the current line highlighted and scrolling. The card marked **"On
the car screen"** shows the two lines the car is being sent, verbatim — if that
card is right, the car will be right.

---

## Using it in the car

Open **LyricLane** from the car's media apps, not Spotify. Spotify keeps
playing; LyricLane shows the words and forwards play, pause, next, previous and
seek back to it.

Three custom actions appear on the now-playing screen:

| Action | Does |
|---|---|
| **Offset −** | hold the lyrics back 250 ms |
| **Offset +** | pull the lyrics forward 250 ms |
| **Lyrics on/off** | switch between lyrics and a plain title/artist mirror |

Offsets are saved per song, so a track you corrected once stays corrected.

### What you should see

| Situation | On the car screen |
|---|---|
| Synced lyrics found | current line, with the next line under it |
| Instrumental break | the upcoming line, as a head start |
| Lyrics exist but untimed | "Lyrics not synced" + song and artist |
| No lyrics found | song title + "No lyrics found" |
| Offline, nothing cached | song title + "Lyrics unavailable · offline?" |
| Spotify ad | "Advertisement" |
| Podcast (over 15 minutes) | plain title/artist, no lookup |
| Notification access missing | "Notification access needed" |
| Spotify not playing | "Nothing playing on Spotify" |

It never leaves the previous song's lyric on screen: the timeline is cleared
before a new one loads.

---

## Settings

| Setting | What it does |
|---|---|
| **Lyric size** | Phone screen only. Starts at 1.45×. |
| **Which line the car shows large** | Android Auto draws the *subtitle* large and the title small above it — the opposite of what the names suggest. Switch this if your head unit does it the other way. |
| **Lyric in the title too** | Instrument clusters and head-up displays read the title. This puts the lyric in both slots so the HUD gets it without the card losing it. |
| **Smaller line in the car** | Next line, previous line, or song and artist. Previous line helps when timing drifts. |
| **Two lines in one slot** | Experimental. Sends current and next together with a newline, in case your head unit wraps it. |
| **Album art** | The car decides how big the cover is; turning it off is the only lever for giving words more of the card. |
| **Show lyrics** | Off mirrors plain title and artist. |
| **Default timing offset** | Applied to songs you have not adjusted individually. |
| **Minimum hold between lines** | How long a line stays before the next can replace it. Raise it if fast verses flicker; lower it for tighter sync. |
| **Clear cache** | Drops cached lyrics and saved offsets. |

---

## Where lyrics come from

Sources are tried in order and stop as soon as one has timed lyrics for a
recording of the right length, so the usual path is a single request.

1. **LRCLIB, word-timed.** Some entries carry a structured document with
   per-line *end* times and per-word timings. Preferred, for two reasons.
   Knowing when a line ends is what lets the car show the next line during an
   instrumental break instead of leaving the last one sitting there. And when a
   line is too long for the screen, its pieces hand over on the beat the next
   word is actually sung rather than splitting the window evenly. Coverage is
   partial — common on recent pop, absent on older catalogue.
2. **LRCLIB, ordinary LRC.**
3. **NetEase Cloud Music.** Public endpoints, no key. Often has a well-timed
   file for tracks LRCLIB has missed.

Both are community-contributed, so coverage and timing vary.

### Why not Spotify's own lyrics

Spotify has no lyrics API, official or otherwise. Its lyrics are licensed from
Musixmatch and reachable only through an internal endpoint that expects a token
lifted from a logged-in session and headers spoofed to look like Spotify's own
client. That is circumventing an access control to pull licensed content into
an unlicensed app, so LyricLane does not do it.

Reading them off the screen does not work either, and not only on policy
grounds: Android does not render the interface of a backgrounded app. While you
are driving, Spotify is in the background with nothing drawn, so there would be
nothing to read. It would also mean granting an app permission to read every
screen on the phone.

---

## When lyrics are wrong or missing

The phone screen tells you what each source reported, rather than a bare "no
lyrics found" — how many candidates it saw, and how far off the closest one
was. `adb logcat -s LyricLane` shows the same trail.

| Symptom | Fix |
|---|---|
| Timing slightly off | **Earlier**/**Later** on the phone, or **Offset ±** in the car. Saved per song. |
| Wrong song entirely | **Wrong lyrics?** searches by hand. Each result shows its duration and whether it is timed; picking one stores it for that track. |
| Nothing found, but you are online | **Look again** drops the cached miss and retries. |
| "20 found, none matched this recording" | The lyrics exist but for a different cut — a live or extended version. Use **Wrong lyrics?** |

Cached misses expire after a day, and are discarded immediately when an update
changes how matching works, so a fix takes effect without waiting.

---

## What Android Auto will and will not let an app do

Worth being explicit, because it shapes everything above. A media app supplies
metadata, not a layout.

**Not settable by any app:**

| | |
|---|---|
| Text size in the car | the head unit's choice |
| Text colour | the head unit's choice |
| Album art size | the head unit's choice |
| Number of text lines | two |

**Ours to choose:** which of the two fields each line goes in, what the second
line carries, whether artwork is sent at all, and how often metadata is pushed.

---

## Building from source

Needs JDK 17+ and the Android SDK (platform 36, build-tools 35+).

```bash
git clone https://github.com/oliverinhalo/LyricLane.git
cd LyricLane
echo "sdk.dir=$ANDROID_HOME" > local.properties   # Android Studio writes this for you

./gradlew test            # unit tests
./gradlew assembleDebug   # app/build/outputs/apk/debug/
```

The debug build installs as `dev.lyriclane.debug` so it can sit beside a
release build.

### Tests

```bash
./gradlew :core:lyrics:testDebugUnitTest :core:media:testDebugUnitTest
```

Covering the places where a bug is invisible until you are driving:

- **LRC parser** — centiseconds and milliseconds, minutes past 59, a chorus with
  several timestamps, ID tags, `[offset:]` in both directions, blank lines as
  gaps, unsorted files.
- **Word-timed parser** — line starts and ends, per-word timings, word entries
  kept out of the line list, malformed documents rejected cleanly.
- **Matcher** — rejects a different-length recording, rejects a wrong song that
  shares a runtime, prefers timed over untimed, copes with "- Remastered 2011"
  and "(feat. …)". Runs against **recorded real LRCLIB responses** for sixteen
  tracks, including the twenty near-duplicates a popular song really returns.
- **Repository** — cache hits skip the network, misses expire, a miss from an
  older build is retried at once, an outage serves stale lyrics instead of
  nothing.
- **Sync engine** — driven by a fake clock on virtual time: advances exactly on
  the timestamp, freezes when paused, resyncs on seek in both directions,
  scales with playback speed, does not spin on lines sharing a timestamp.
- **Car display** — every state produces two sensible lines; the lyric always
  lands in the slot the head unit draws large.

Fixture lyrics are replaced with placeholder words. Only the structure matters
to the tests, and song lyrics are not ours to redistribute.

---

## Testing on the Desktop Head Unit

The DHU runs the real Android Auto stack from your phone on your desktop. It is
the only way to see the dashboard card without a car.

1. Android Studio → **SDK Manager → SDK Tools** → install **Android Auto
   Desktop Head Unit Emulator**. It lands in `$ANDROID_HOME/extras/google/auto/`.
2. `chmod +x ./desktop-head-unit` (macOS/Linux). On Linux it also needs GLIBC
   2.32+ and `libc++1 libc++abi1`.
3. On the phone: Android Auto settings → ⋮ → **Start head unit server**, and
   check **Previously connected cars → Add new cars** is on.
4. Plug in over USB, unlock the phone, then:

```bash
adb forward tcp:5277 tcp:5277
cd "$ANDROID_HOME/extras/google/auto"
./desktop-head-unit
```

The split-screen dashboard only appears on a widescreen head unit. Put this in
`~/.android/headunit.ini`:

```ini
[general]
touch = true
resolution = 1920x1080
dpi = 160
framerate = 30

# A separate window printing playback-status messages as they arrive —
# the quickest way to see how often the head unit picks up a metadata change.
playbackstatus = true

# Emulates an instrument cluster, for testing "Lyric in the title too".
instrumentcluster = true
```

Useful console commands: `screenshot shot.png`, `day` / `night`,
`focus video off`, `restrict all`, `help`.

---

## Project layout

```
app/                          Android Auto session, phone UI, DI
  media/LyricLaneService      MediaLibraryService: session, browse tree,
                              custom actions; publishes the lyric
  media/LyricLaneOrchestrator track changes → lyrics → timeline → current line
  media/SettingsStore         persisted settings
  ui/                         Compose: onboarding, now playing, picker, settings
core/spotify/                 reads Spotify's own MediaSession
  SpotifyWatcher              follows track and playback; relays transport back
  SpotifyNotificationListener exists only to be granted listener access
core/lyrics/                  words and time
  LrclibSource, NeteaseSource the two catalogues
  LrcParser                   LRC: multiple stamps, offset tags, gaps
  LyricsFileParser            LRCLIB's word-timed document
  LyricsMatcher               picks the right recording out of 20 candidates
  LyricTimeline               line splitting, current-line lookup
  LyricSyncEngine             wakes at the next line; no polling
  LyricsRepository            cache first, network second, offline-safe
core/media/                   car-facing presentation
  MirrorPlayer                SimpleBasePlayer that describes, never plays
  CarDisplayFormatter         two lines for every possible state
  CarSlotMapping              which metadata field each line goes in
  MetadataPacing              rate limiting without ever showing a stale line
```

---

## Limitations

- **Two lines maximum in the car, never animated.** A platform limit, and a
  deliberate safety choice: no scrolling text, no animation, long lines split at
  word boundaries or truncated.
- **Spotify must be playing on the phone.** LyricLane has no audio of its own.
- **Needs notification-listener access**, and **Unknown sources** in Android
  Auto because it is sideloaded.
- **Lyrics are community-contributed.** Coverage and timing vary, especially
  outside well-known songs.
- **Timing follows Spotify's reported position**, which it publishes only when
  something changes. A head unit a few hundred milliseconds out is normal — that
  is what the offset is for.
- **Podcasts and audiobooks are skipped** — anything over 15 minutes mirrors
  plain title and artist rather than wasting a lookup.
- **Not for Play.** Reading another app's media session and re-presenting it is
  not something Google Play would accept, and it is not meant to be published.

---

## Licence and credits

Source code under the [MIT licence](LICENSE). Lyrics are not covered by it and
remain the property of their rights holders.

Lyrics from [LRCLIB](https://lrclib.net) and
[NetEase Cloud Music](https://music.163.com), contributed by their communities.
Built with [Media3](https://developer.android.com/media/media3).

LyricLane is not affiliated with Spotify, Google or Android Auto.
