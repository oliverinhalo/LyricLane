# LyricLane

Shows the **current lyric line** of whatever Spotify is playing on an Android
Auto screen — including the split-screen dashboard card — by publishing the
lyric as MediaSession metadata.

Personal-use, sideloaded. Not published, no Spotify Web API login.

---

## Why it is built this way

Android Auto does not let a media app draw its own UI. The car renders the
now-playing screen and the dashboard card itself, from the app's MediaSession
metadata and playback state. So the only way to get a lyric onto the car screen
is to **put the lyric where the song title normally goes**:

| MediaSession field | LyricLane uses it for      |
|--------------------|----------------------------|
| `title`            | current lyric line         |
| `artist`           | next lyric line (or song — artist) |
| artwork            | Spotify's album art        |

LyricLane therefore registers as a media app, mirrors Spotify's playback state
without producing any audio of its own, and forwards play/pause/next/prev/seek
back to Spotify. While driving you open **LyricLane** in Android Auto; Spotify
keeps playing on the phone underneath.

---

## Status: working end to end

Phase 0 answered the question everything depended on: Android Auto **does**
re-render MediaSession metadata live, on the now-playing screen and on the
split-screen dashboard card. Phases 1 to 5 are built on top of that.

What happens when you use it:

1. Spotify plays on the phone.
2. LyricLane finds Spotify's media session, reads the track and follows the
   playback position.
3. It looks the lyrics up on LRCLIB, parses the LRC timings, and caches them.
4. A sync engine wakes at the exact timestamp of each line.
5. The line is published as the MediaSession title, so the car shows it.

### First run

```
install the APK  ->  open LyricLane  ->  grant notification access
                 ->  press play in Spotify  ->  lyrics appear
```

The app opens on a single screen with one button if notification access is
missing, because nothing works without it. Once granted, that screen is
replaced by the now-playing view by itself.

## Build and sideload

Requires JDK 17+ and the Android SDK (platform 36, build-tools 35+).

```bash
git clone https://github.com/oliverinhalo/LyricLane.git
cd LyricLane
echo "sdk.dir=$ANDROID_HOME" > local.properties   # or let Android Studio write it

./gradlew :app:testDebugUnitTest     # unit tests
./gradlew :app:assembleDebug         # -> app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build installs as `dev.lyriclane.debug`, so it can sit alongside a
release build.

Launch **LyricLane** on the phone once after installing: it connects to its own
session (which starts the service) and asks for the notification permission that
the media notification needs.

---

## Make a sideloaded app visible in Android Auto

Android Auto refuses apps that did not come from a trusted source unless you
turn that check off. Both steps below are required.

### 1. Enable Android Auto developer mode (once per phone)

1. **Settings → Apps → See all apps → Android Auto → Advanced →
   Additional settings in the app**.
2. Scroll to **About** at the bottom, tap **Version**.
3. Tap the **Version and permission info** block **10 times**.
4. The **Allow development settings?** dialog appears → **OK**.

Developer options now live in the ⋮ overflow menu of Android Auto settings.

### 2. Allow unknown sources

In Android Auto settings → ⋮ → **Developer settings** → enable
**Unknown sources**.

This applies to media, messaging and parked apps — which covers LyricLane.
Without it, LyricLane will not appear in the car launcher no matter what the
manifest says.

---

## Test on the Desktop Head Unit (DHU)

The DHU runs the real Android Auto stack from your phone and projects it onto
your desktop. It is the only way to see the dashboard card without a car.

### Install the DHU

1. In Android Studio: **SDK Manager → SDK Tools** tab → install
   **Android Auto Desktop Head Unit Emulator**.
   It lands in `$ANDROID_HOME/extras/google/auto/`.
2. On macOS/Linux make it executable:

   ```bash
   cd "$ANDROID_HOME/extras/google/auto"
   chmod +x ./desktop-head-unit
   ```

3. **Linux only.** DHU 2.x needs GLIBC ≥ 2.32 (`ldd --version`) plus:

   ```bash
   sudo apt-get install libc++1 libc++abi1
   ```

   Keep the bundled `libusb` next to the executable.

### Connect the phone (ADB tunnelling)

1. Enable Android Auto developer mode (above) and USB debugging.
2. Android Auto settings → ⋮ → **Start head unit server**. A foreground
   notification confirms it is running.
3. Android Auto settings → **Previously connected cars** → make sure
   **Add new cars to Android Auto** is on.
4. Plug the phone in over USB and **unlock the screen**.
5. Forward the port and start the DHU:

   ```bash
   adb forward tcp:5277 tcp:5277
   cd "$ANDROID_HOME/extras/google/auto"
   ./desktop-head-unit
   ```

6. First run only: accept the terms and permission prompts **on the phone**.
   The DHU may exit once while doing this — start it again.

If the DHU shows a blank screen: quit it, toggle **Start head unit server** off
and on, and relaunch.

### Use a wide screen so the dashboard card appears

The split-screen dashboard (media card + navigation) only shows up on a
widescreen head unit. Put this in `~/.android/headunit.ini`, or pass it with
`-c`:

```ini
[general]
touch = true
resolution = 1920x1080
dpi = 160
framerate = 30

# Adds a separate window that prints playback-status messages as they arrive —
# the quickest way to see how often the head unit actually picks up a metadata
# change.
playbackstatus = true
```

```bash
./desktop-head-unit -c ~/.android/headunit.ini
```

Useful DHU console commands while testing:

| Command | Does |
|---|---|
| `keycode media` | jump to the default media app |
| `screenshot shot.png` | save a screenshot |
| `day` / `night` | day / night mode |
| `focus video off` | simulate the head unit hiding Android Auto |
| `restrict all` | simulate driving restrictions |
| `help` | full command set |

---

## Trying it

### The quick check (phone only, no car)

1. Install the APK and open **LyricLane**.
2. Grant notification access when it asks. Come back to the app; the onboarding
   screen disappears on its own.
3. Press play on a well-known song in Spotify.
4. LyricLane's now-playing screen fills with the lyrics, the active line
   highlighted and scrolling itself.
5. The card labelled **"On the car screen"** shows the two lines the car is
   being sent, verbatim. If that card is right, the car will be right.

```bash
adb logcat -s LyricLane
```
shows which lyrics were found for each track and why a lookup failed.

### In the car (or on the DHU)

Open **LyricLane** from the car's media apps instead of Spotify. Spotify keeps
playing underneath; LyricLane shows its lyric and forwards play, pause, next,
previous and seek back to it.

Three custom actions appear on the now-playing screen:

| Action | Does |
|---|---|
| **Offset −** | hold the lyrics back 250 ms |
| **Offset +** | pull the lyrics forward 250 ms |
| **Lyrics on/off** | switch between lyrics and a plain title/artist mirror |

Offsets are saved per song, so a track you corrected once stays corrected.

### What the car lets us change, and what it does not

Android Auto renders the media card itself. A media app supplies metadata, not
a layout, so these are **not ours to set** and no setting can change them:

| | |
|---|---|
| Text size in the car | the head unit's choice |
| Text colour | the head unit's choice |
| Album art size | the head unit's choice |
| Number of text lines | two |

What *is* ours, and is now adjustable in Settings:

- **Which line the car shows large.** Android Auto draws the **subtitle** large
  and white, with the title small and grey above it — the opposite of what the
  field names suggest. Until v0.3.0 the current lyric went in the title, which
  is why it appeared small and grey. It now goes in the large slot by default;
  if your head unit does the opposite, flip "Which line the car shows large".
- **What the smaller line carries** — next line, previous line, or song and
  artist. Previous line helps when the timing is drifting.
- **Album art on or off.** The car decides how big the cover is; turning it off
  is the only lever we have for giving the words more of the card.
- **Two lines in one slot** (experimental). Sends the current and next line
  together separated by a newline, in case the head unit wraps them. If yours
  just truncates, turn it back off.

On the phone, where we *do* control the layout, lyric size is a slider and
starts at 1.45×.

### Where lyrics come from

Tried in order, stopping as soon as one has timed lyrics for a recording of the
right length:

1. **LRCLIB word-timed** — some entries carry a structured document with
   per-line *end* times, not just start times. These are preferred: knowing
   when a line stops is what lets the car show the next line during an
   instrumental break instead of leaving the last one sitting there.
2. **LRCLIB** — ordinary LRC.
3. **NetEase Cloud Music** — public endpoints, no key. Often has a well-timed
   LRC for tracks LRCLIB has missed. Its files open with credit lines timed at
   00:00 (lyricist, composer); those are stripped.

**Not Spotify's own lyrics.** They are licensed from Musixmatch and reachable
only through an internal endpoint that needs a logged-in Spotify session token.
There is no public API for them, they are not in the MediaSession, and using the
private endpoint would mean impersonating the Spotify client. The brief for this
project also ruled out a Spotify login.

### What you should see in each situation

| Situation | On the car screen |
|---|---|
| Synced lyrics found | current line, with the next line under it |
| Instrumental break | the upcoming line, as a head start |
| Lyrics exist but untimed | "Lyrics not synced" + song and artist |
| No lyrics on LRCLIB | song title + "No lyrics found" |
| Offline, nothing cached | song title + "Lyrics unavailable · offline?" |
| Spotify ad | "Advertisement" |
| Podcast (over 15 minutes) | plain title/artist mirror, no lookup |
| Notification access missing | "Notification access needed" |
| Spotify not playing | "Nothing playing on Spotify" |

It never leaves the previous song's lyric on screen: the timeline is cleared
before the new one is loaded.

### When the lyrics are wrong

LRCLIB is community-contributed, and Spotify's track titles do not always match
what a song is filed under there.

- **Timing slightly off** — use Earlier/Later on the phone, or Offset ± in the
  car. Saved per song.
- **Wrong song entirely** — "Wrong lyrics?" on the now-playing screen searches
  LRCLIB by hand. Each result shows its duration and whether it is synced;
  picking one stores it for that track.
- **Nothing found, but you have a connection** — "Look again" drops the cached
  miss and retries.

### Timing design, if you need to tune it

The sync engine does not poll. It computes the current line, then sleeps until
the exact timestamp of the next one, scaled by playback speed. Paused playback
schedules nothing at all, which is what keeps it off the battery.

Metadata pushes are rate-limited by a **minimum hold** (default 500 ms,
adjustable in Settings). While it waits out that interval, newer lines *replace*
the pending one rather than queueing, so a fast verse shows the line that is
actually current — never a late one. Raise it if a head unit looks like it is
flickering; lower it for tighter sync.

## Project layout

```
app/                        the Android Auto session, the phone UI, DI wiring
  media/LyricLaneService    MediaLibraryService: session, browse tree,
                            custom actions; publishes the lyric as the title
  media/LyricLaneOrchestrator  the brain: track changes -> lyrics -> timeline
                            -> current line -> two lines of text
  media/SettingsStore       four persisted settings
  ui/                       Compose: onboarding, now playing, picker, settings
core/spotify/               reads Spotify's own MediaSession
  SpotifyWatcher            follows track + playback; relays transport back
  SpotifyNotificationListener  exists only to be granted listener access
  NotificationAccess        permission check and the settings intent
core/lyrics/                everything about words and time
  LrclibClient              /api/get then /api/search, over plain OkHttp
  LrcParser                 LRC: multiple stamps, offset tags, gaps
  LyricsMatcher             picks the right recording out of 20 candidates
  LyricTimeline             line splitting and the current-line lookup
  LyricSyncEngine           wakes at the next line; no polling
  LyricsRepository          cache first, network second, offline-safe
  LyricsStore               Room: lyrics cache + per-song offsets
core/media/                 the car-facing presentation
  MirrorPlayer              SimpleBasePlayer that describes, never plays
  CarDisplayFormatter       two lines for every possible state
  MetadataPacing            minimum hold without ever showing a stale line
  Artwork                   album art downscaled to survive Binder
```

### Tests

```bash
./gradlew :core:lyrics:testDebugUnitTest
```

Covering the parts where a bug is invisible until you are driving:

- **LRC parser** — centiseconds and milliseconds, minutes past 59, a chorus
  with several timestamps, ID tags, `[offset:]` in both directions, blank lines
  as gaps, unsorted files, untimed junk.
- **Matcher** — rejects a different-length recording, rejects a wrong song that
  happens to share a runtime, prefers synced over plain, prefers the closest
  duration, copes with "- Remastered 2011" and "(feat. ...)".
- **Timeline** — current-line lookup at boundaries and past the end, long-line
  splitting only when there is time to read the pieces, never cutting mid-word.
- **Sync engine** — driven entirely by a fake clock on virtual time: advances
  exactly on the timestamp, freezes when paused (an hour of clock passes and the
  line does not move), resyncs forwards and backwards on seek, scales with
  playback speed, applies offsets, and does not spin on lines that share a
  timestamp.

## Known limitations

- **No custom car UI.** Only MediaSession metadata reaches the screen, so the
  car can show at most the current line plus one more. A platform limit, not a
  design choice.
- **Two lines maximum, never animated.** Deliberate. No scrolling text, no
  animation; long lines are split at word boundaries or truncated.
- **Spotify must be playing on the phone.** LyricLane reads Spotify's
  MediaSession and has no audio of its own. If Spotify is closed, the card says
  so.
- **Needs notification-listener access**, because that is the only way Android
  lets one app read another's media session without a privileged permission.
- **Needs "Unknown sources"** in Android Auto's developer settings, because it
  is sideloaded.
- **Lyrics are community-contributed** (LRCLIB, NetEase): coverage and timing
  vary, especially outside well-known songs. Hence the per-song offset and the
  manual picker.
- **Text size, colour and album art size in the car are not settable.** Android
  Auto draws the card; media apps supply metadata, not a layout.
- **Timing follows Spotify's reported position**, which it publishes only when
  something changes. A head unit a few hundred milliseconds out is normal; that
  is what the offset is for.
- **Podcasts and audiobooks are skipped** — anything over 15 minutes is treated
  as spoken word and mirrors plain title/artist rather than wasting a lookup.
- **Sideload only.** Reading another app's media session and re-presenting it is
  not something Google Play would accept, and it is not meant to be published.
