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
3. Pick a quality (360p / 720p / 1080p) → **Fetch & transcribe**
4. When transcription finishes the player opens automatically — subtitles are
   overlaid on the video, the transcript below is tappable to seek, and the
   current line follows playback
5. Re-runs of the same video play instantly from the on-device cache; export
   the subtitles as SRT/VTT or copy the transcript from the player menu

## Building

Requires JDK 17 and the Android SDK (set `sdk.dir` in `local.properties`).

```
gradlew assembleDebug      # APK at app/build/outputs/apk/debug/app-debug.apk
gradlew installDebug       # install on a connected device
```

## License

GPLv3. See `LICENSE`.
