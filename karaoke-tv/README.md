# Karaoke TV

An Android TV karaoke app: plays your music with time-synced, highlighted lyrics.

## Where songs and lyrics come from

| Source | What it gives you |
| --- | --- |
| **My music → network folder**: a Windows share or NAS, default `\\192.168.1.99\Public\Shared Music` (subfolders included) | Audio streamed straight from the share, plus `.lrc` lyrics next to each song |
| **My music → this TV**: songs on the TV's storage and USB drives | Audio + lyrics |
| **Sidecar `.lrc` files**: `Song.mp3` + `Song.lrc` in the same folder | Synced lyrics, offline |
| **[LRCLIB](https://lrclib.net)**: free, open lyrics database, no API key | Synced lyrics for millions of songs, looked up when a song plays (nothing is saved) |
| **[lyrics.ovh](https://lyricsovh.docs.apiary.io)**: free backup, no API key | Plain (unsynced) lyrics when LRCLIB is busy or has nothing; needs the artist name |
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

## Network music folder

**My music** scans `\\192.168.1.99\Public\Shared Music` by default. Use **Change folder** to set another
path (`\\server\share\folder` or `smb://server/share/folder`) and, if the share isn't public, a
username and password (`DOMAIN\user` works too). You can also hide music stored on the TV itself.

- Songs stream directly from the share, so seeking works and nothing is copied to the TV.
- My music is a compact list of artists (A–Z, "Unknown artist" last). Select an artist to open its
  songs (A–Z) right below it; select it again or press Back to close it. Artist and title come from
  file names like `01 - Artist - Title.mp3`; if the name has no artist, the folder is used
  (`Artist\Album\song.mp3` or `Artist\song.mp3`). Tags inside the file correct them once it plays.
- Album art fills the karaoke screen behind the lyrics: the cover embedded in the file, or
  `cover.jpg` / `folder.jpg` / `front.jpg` in the song's folder. Turn it off in **Look & feel**.
- Lyrics folder: `\\192.168.1.99\Public\Shared Music\.lrc` (change it under **Change folder**; it must
  be on the same share). When a song plays, the app looks for `Artist - Title.lrc` (or a file named
  like the audio file, e.g. `01 Song.lrc`) there first, then `Song.lrc` next to the song, then online.
- Synced lyrics found online are downloaded into that folder as `Artist - Title.lrc`, so next time
  they load instantly and offline. **Download lyrics for all songs** fills the folder in one go;
  lyrics picked with **Wrong lyrics?** replace the saved file. Nothing is written next to your songs.
  Songs with a lyrics file show "♪". Saving needs write access to the share.
- Needs SMB 2 or newer, which is the default on Windows 10/11 and current NAS firmware.
  The login is saved on the TV in plain app storage.

## Customizing the look

Open **Look & feel** on the home screen to choose:

- **Background:** Purple night, Ocean, Sunset, Forest, Neon club, Plain black
- **Font:** Modern, Light, Classic serif, Typewriter, Handwritten
- **Lyrics size:** Small to Huge
- **Highlight color:** the color that sweeps across the line being sung

A preview shows changes live, and your choices are saved on the TV.

To add your own options, edit `app/src/main/kotlin/com/karaoketv/ui/Appearance.kt`:

- New background: add an entry to `BackgroundStyle` with its gradient colors, e.g.
  `Galaxy("Galaxy", listOf(Color(0xFF000814), Color(0xFF3A0CA3))),`
- Custom font file: put e.g. `myfont.ttf` in `app/src/main/res/font/` and add
  `MyFont("My font", FontFamily(Font(R.font.myfont)), FontWeight.Normal),` to `FontChoice`
  (imports: `androidx.compose.ui.text.font.Font`, `com.karaoketv.R`).
- New highlight: add an entry to `HighlightColor`.

The new options show up in the Look & feel screen automatically.

## Build and run

Requirements: Android Studio (Ladybug or newer) or the Android SDK command-line tools with platform 35, and JDK 17+.

```bash
cd karaoke-tv
./gradlew :core:test          # lyrics engine unit tests (plain JVM)
./gradlew :app:assembleDebug  # APK at app/build/outputs/apk/debug/app-debug.apk
```

Or open the `karaoke-tv` folder in Android Studio and run it on an **Android TV emulator**
(Device Manager → Television → 1080p) or a real TV.

### Install on the TV with one command (Windows)

From the `karaoke-tv` folder in PowerShell:

```powershell
.\install-tv.ps1                    # TV at 192.168.1.69
.\install-tv.ps1 -Tv 192.168.1.50   # another TV
```

It connects to the TV, builds the app, installs it and opens it, and tells you what to do if a step fails.
If Windows says running scripts is disabled, run it as
`powershell -ExecutionPolicy Bypass -File .\install-tv.ps1` instead.

### Install on a real Android TV / Google TV

1. On the TV: Settings → System → About → click **Android TV OS build** 7 times to enable developer options.
   Then Settings → System → Developer options → turn on **USB debugging / Network debugging**.
2. Find the TV's IP address (Settings → Network).
3. From the PC: `adb connect <tv-ip>:5555`, accept the prompt on the TV, then `./gradlew :app:installDebug`.

## Layout

- `core/`: pure Kotlin/JVM: LRC parser, timing math, LRCLIB client, sidecar provider, share-path parsing (unit-tested)
- `app/`: Android TV app: Compose for TV UI, Media3 ExoPlayer, vocal reducer audio processor
