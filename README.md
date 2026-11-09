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

## Status: Phase 0 (risk spike) — ready to test

Everything above rests on one unverified assumption: **that Android Auto
re-renders MediaSession metadata live, on both the now-playing screen and the
split-screen dashboard card.** Phase 0 exists only to prove or disprove that,
before any lyric code is written.

What is in the build right now:

- A Media3 `MediaLibraryService` (`LyricLaneService`) that appears in the
  Android Auto launcher as a media app.
- A `MirrorPlayer` (`SimpleBasePlayer`) that **describes** a playback state
  without playing audio and without taking audio focus — the shape the real
  Spotify mirror will have in Phase 4.
- A ticker that rewrites the session title on a timer and logs the exact
  moment of every push.
- A selectable push rate — **2 s / 1 s / 500 ms / 250 ms** — to find the point
  where the car starts coalescing updates. That answers whether the sync engine
  needs a minimum hold time between lyric lines.
- A browse tree with one root child, "Now playing on Spotify", so the car's
  browser is not empty.
- One custom action in the car ("Rate: …") to check whether Android Auto
  renders custom session commands at all — Phase 4 needs three of them.
- A phone screen showing what the session is publishing, the measured push
  interval, and the push trail.

See [Phase 0 findings](#phase-0-findings) for where to record the result.

---

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

## How to test Phase 0

Run these in order. Step 3 is the one that decides whether this project is
possible at all.

**1. Phone only — is the session publishing?**

Open LyricLane on the phone. The card under *"What the session is publishing"*
must change roughly every 2 s, and the trail below it must fill with
timestamps. If this does not happen, the problem is in our session, not in the
car, and nothing below is meaningful.

```bash
adb logcat -s LyricLane      # same trail, with system timestamps
```

**2. Car — now-playing screen.**

Start the DHU. Open the media app list (`keycode media`, then the app switcher)
and pick **LyricLane**. Expected: the title line counts up — `Tick 001`,
`Tick 002`, … — about every 2 s, the subtitle reads
*"Phase 0 spike · pushing every 2 s"*, a green placeholder cover is shown, and
the progress bar advances.

**3. Car — split-screen dashboard card. ← the actual question.**

Go to the Android Auto home/dashboard (the split screen with navigation on one
side and a media card on the other). Expected: the media card's title counts up
live, without being touched.

Take a screenshot a few seconds apart and compare:

```bash
# in the DHU console
screenshot dash-1.png ; sleep 5 ; screenshot dash-2.png
```

**4. How fast can we push?**

Tap 1 s, then 500 ms, then 250 ms on the phone (or the "Rate: …" custom action
in the car, which cycles through them). At each rate, watch the DHU and the
`playbackstatus` window and note the fastest rate at which **every** tick number
is still rendered. Record it below — it becomes the minimum hold time for lyric
lines in Phase 3.

**5. Custom actions.**

Confirm the "Rate: …" button appears in the car's now-playing screen and that
tapping it changes the rate. Phase 4 needs three such actions
(Offset −, Offset +, Lyrics on/off).

**6. Does the card survive?**

- `focus video off` then on — does the card come back?
- Swipe the phone app away — the card should stay (the service ignores
  `onTaskRemoved`).

### Phase 0 findings

To be filled in from a real DHU run:

| Question | Result |
|---|---|
| Now-playing screen updates live | _unverified_ |
| Dashboard card updates live | _unverified_ |
| Fastest rate with no dropped ticks | _unverified_ |
| Minimum hold time needed | _unverified_ |
| Custom action rendered in car | _unverified_ |
| Artwork rendered on card | _unverified_ |
| Card survives losing video focus | _unverified_ |

**If step 3 fails**, stop — mirroring lyrics into metadata cannot work, and the
fallbacks are: a notification-only lyric line on the phone, an Android for Cars
App Library *parked-only* app (no driving use), or accepting the now-playing
screen alone without the dashboard card.

---

## Project layout

```
app/
  src/main/kotlin/dev/lyriclane/
    media/LyricLaneService.kt   MediaLibraryService: session, browse tree,
                                custom actions, the Phase 0 ticker
    media/MirrorPlayer.kt       SimpleBasePlayer that describes playback
                                without audio; Phase 4 points it at Spotify
    diag/TickLog.kt             push trail + measured push interval
    diag/TickerRate.kt          2 s / 1 s / 500 ms / 250 ms
    ui/                         Compose operator console (Material 3,
                                dynamic colour)
  src/main/res/xml/automotive_app_desc.xml   <uses name="media"/>
  src/test/                     unit tests
gradle/libs.versions.toml       single source of versions for every phase
```

The modular split (`:core:lyrics`, `:core:spotify`, `:core:media`) arrives with
the phases that need it; Phase 0 is deliberately one module so the spike has as
few moving parts as possible.

---

## Build plan

| Phase | Scope | State |
|---|---|---|
| 0 | MediaLibraryService spike; prove live metadata on car + dashboard | **ready to test** |
| 1 | Spotify tracking via `NotificationListenerService` + `MediaSessionManager` | not started |
| 2 | LRCLIB fetch, LRC parser, Room cache (+ unit tests) | not started |
| 3 | Sync engine scheduled to the next line, fake-clock tests | not started |
| 4 | Mirror player, control forwarding, custom actions in the car | not started |
| 5 | Phone Compose UI: onboarding, lyrics view, settings | not started |
| 6 | Edge cases, battery check, docs | not started |

---

## Known limitations

- **No custom car UI.** Only MediaSession metadata reaches the screen, so the
  car can show at most the current line plus one more. This is a platform
  limit, not a design choice.
- **Two lines maximum, never animated.** Deliberate: no scrolling text, no
  animation, long lines split or truncated.
- **Spotify must be playing on the phone.** LyricLane reads Spotify's
  MediaSession; it has no audio of its own.
- **Requires notification-listener access** (Phase 1) to read Spotify's
  session, and **Unknown sources** in Android Auto because it is sideloaded.
- **Lyrics come from LRCLIB** and are community-contributed: coverage and
  timing vary. Hence the per-song offset and manual re-pick.
- **Sideload only.** Reading another app's media session and re-presenting it
  is not something Google Play would accept.
- Phase 0's playback state is synthetic — a fake one-hour track — so the
  progress bar in the car means nothing yet.
