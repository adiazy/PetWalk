# Karaoke TV

An Android TV karaoke app: plays your music with time-synced, highlighted lyrics.

## Where songs and lyrics come from

| Source | What it gives you |
| --- | --- |
| **My music**: songs on the TV's storage and USB drives | Audio + lyrics |
| **Sidecar `.lrc` files**: `Song.mp3` + `Song.lrc` in the same folder | Synced lyrics, offline |
| **[LRCLIB](https://lrclib.net)**: free, open lyrics database, no API key | Synced lyrics for millions of songs, cached on the device after the first play |
| **Play from URL**: any direct MP3/AAC/FLAC/HLS link (NAS, Jellyfin/Plex direct links, radio) | Audio; lyrics looked up from the title you enter or from the stream's metadata |
| **Find lyrics online, lyrics-only mode** | Timed lyrics on the TV while the backing track plays from a phone or speaker |

Streaming services (YouTube, Spotify, Apple Music) don't allow third-party apps to pull out their
audio, so they aren't built in. Use lyrics-only mode with them instead.

## Features

- Gold fill sweeping across the current line, word by word when the LRC has word timing (`<mm:ss.xx>`)
- Countdown dots before the first line
- **Vocal reducer** (center-channel cancellation that keeps the bass) for songs that don't have a karaoke version
- Lyrics sync adjustment in ±0.25 s steps
- Navigable with the D-pad; supports remote media keys (play/pause, fast-forward, rewind)

## Build and run

Requirements: Android Studio (Ladybug or newer) or the Android SDK command-line tools with platform 35, and JDK 17+.

```bash
cd karaoke-tv
./gradlew :core:test          # lyrics engine unit tests (plain JVM)
./gradlew :app:assembleDebug  # APK at app/build/outputs/apk/debug/app-debug.apk
```

Or open the `karaoke-tv` folder in Android Studio and run it on an **Android TV emulator**
(Device Manager → Television → 1080p) or a real TV.

### Install on a real Android TV / Google TV

1. On the TV: Settings → System → About → click **Android TV OS build** 7 times to enable developer options.
   Then Settings → System → Developer options → turn on **USB debugging / Network debugging**.
2. Find the TV's IP address (Settings → Network).
3. From the PC: `adb connect <tv-ip>:5555`, accept the prompt on the TV, then `./gradlew :app:installDebug`.

## Layout

- `core/`: pure Kotlin/JVM: LRC parser/writer, timing math, LRCLIB client, sidecar and cache providers (unit-tested)
- `app/`: Android TV app: Compose for TV UI, Media3 ExoPlayer, vocal reducer audio processor
