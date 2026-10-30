# TubeScribe (Android)

TubeScribe takes a YouTube link, downloads the video, transcribes the speech
**with Groq's cloud Whisper model** and plays the video back with subtitles.

## Requirements

- Android 8.0 (API 26) or newer
- A [Groq API key](https://console.groq.com) (free tier works)
- Internet (YouTube extraction + Whisper transcription; audio is uploaded to Groq)

## Usage

1. Open the app → **Settings** (gear) → paste your Groq API key
2. Paste a YouTube link (or share a YouTube link to TubeScribe)
3. Pick a quality (360p / 720p / 1080p) and a subtitle model (Max speed or
   Max accuracy) → **Fetch & transcribe**
4. When transcription finishes the player opens automatically — subtitles are
   overlaid on the video, the transcript below is tappable to seek, and the
   current line follows playback
5. Re-runs of the same video play instantly from the on-device cache; export
   the subtitles as SRT/VTT or copy the transcript from the player menu.
   Pasting a cached link again with a **higher** quality re-downloads it and
   replaces the cached copy in place (the lower-quality file is only removed
   once the new one is fully loaded)
6. Settings (gear) → **Caching videos** caps the on-device cache: delete
   downloads older than 1 hour / 6 hours / 1 day / 3 days / 1 week (default:
   1 week), keep at most 3–20 videos (default: 3; custom count up to 100 or no
   limit), plus a combined rule — delete when either limit is hit (default)
   or when both are. Applied at app start and after each new download.
   The screen also shows the disk space the cache occupies at that moment
7. Star a video in the home screen's **Cached** list to pin it: saved videos
   are exempt from every limit and don't count toward them. Un-starring puts
   the video back on top of Cached with a fresh age, after which the limits
   apply to it again. The Cached list always mirrors what is on disk
8. Swipe a cached row to the **right** to delete that video from disk right
   away: a bin icon grows as you drag, and releasing past the threshold
   removes the entry (its media, subtitles and metadata). Releasing earlier
   slides the row back without deleting anything

## Subtitle models

The home screen's **Subtitles** picker offers two trade-offs:

- **Max speed** (`whisper-large-v3-turbo`) — default: the distilled model,
  about a third of the price of Max accuracy with a small accuracy
  trade-off.
- **Max accuracy** (`whisper-large-v3`) — the full model: more accurate and
  more expensive. Both run far faster than real time on Groq.

Both take the same requests and have the same free-tier limits (25 MB per
upload, 20 requests/minute, 8 hours of audio/day). Cached transcripts remember
which model made them, so switching models re-transcribes a cached video on
the next fetch while reusing the downloaded video. Settings → **About** shows
these mappings and the app version.

## Updates

Settings → **App updates** checks the latest public GitHub release. When a
newer version is published, the screen downloads the APK attached to that
release and hands it to Android's installer. Android asks you to confirm each
install; the first time, allow TubeScribe to install unknown apps. Cached
videos, API keys and settings are kept.

Release builds are signed with the local key described by
`keystore.properties` (copy `keystore.properties.example`). The properties
file and `/keystore/` are gitignored, so builds on another machine need the
same key to install over an existing copy.

## Building

Requires JDK 17 and the Android SDK (set `sdk.dir` in `local.properties`).

```
gradlew assembleDebug      # APK at app/build/outputs/apk/debug/app-debug.apk
gradlew installDebug       # install on a connected device
gradlew assembleRelease    # signed APK at app/build/outputs/apk/release/app-release.apk
```

## License

GPLv3. See `LICENSE`.
