# Echo

An always-on instant-replay voice recorder for Android, inspired by NVIDIA ShadowPlay/Instant Replay. Audio is captured continuously, compressed to Opus in a fixed-size in-memory ring buffer, and only written to disk when you explicitly save the last N seconds — nothing touches flash storage until you ask for it.

## Architecture

- `EchoService` (Kotlin) runs as a `microphone` foreground service. A dedicated thread blocks on `AudioRecord.read` into a reused direct `ByteBuffer` (chunk size configurable, default 200 ms) and hands the buffer to the native engine with zero JNI copies.
- `rust/` (cdylib `libecho_core.so`) does everything hot: libopus encoding (via `opusic-sys`, built from source with the NDK toolchain), the ring buffer of encoded packets, statistics, and Ogg Opus muxing. The ring stores compressed packets only, so RAM usage is roughly `bitrate × buffer_seconds / 8` (≈ 1.8 MB for 24 kbps × 10 min).
- Saving clones nothing large: the ring is locked briefly, packets from the cut point are written straight into the `ParcelFileDescriptor` of a MediaStore row (`Music/Echo/*.opus`).
- Triggers: in-app button, notification action, and a Quick Settings tile (save when recording, start otherwise). Optional boot autostart.

Everything crosses the JNI boundary as plain JSON (`{"ok":false,"error":...}` on failure); `push` returns null on success so the hot path allocates no strings.

## Battery notes

The dominant costs are the mic pipeline wakeups and Opus encoding. Tunables, in order of impact:

- Sample rate (8–48 kHz), channels, bitrate (6–256 kbps in UI), Opus complexity (0–10), frame length (2.5–60 ms).
- Read chunk size (50–1000 ms): larger chunks mean fewer thread wakeups, at the price of latency in the buffer.
- Wake lock is optional (off if your device keeps recording alive without it).

Defaults (16 kHz mono, 24 kbps VBR, complexity 5, 200 ms reads) target voice memos with minimal drain.

## Building

Prerequisites: JDK 17+, Android SDK with platform 36, NDK `30.0.16248370` (`sdk.dir` in `local.properties`), Rust stable/nightly, `cargo-ndk`, CMake and Ninja on PATH (libopus is compiled by `opusic-sys`).

```
# Rust: host tests for the engine (ring buffer + Ogg Opus roundtrip)
cd rust && cargo test

# Android: preBuild runs `cargo ndk` for arm64-v8a, armeabi-v7a, x86_64 into jniLibs
./gradlew assembleDebug
```

## Configuration

All settings live in the app's single screen and apply on the next recording start: sample rate, channels, Opus frame length, application (voip/audio/lowdelay), signal hint, bitrate, VBR, complexity, ring buffer length (1–7200 s), save duration, read chunk, audio source (MIC/VOICE_RECOGNITION/UNPROCESSED/...), optional NS/AGC/AEC effects, wake lock, boot autostart.

## Notes

- Files land in `Music/Echo` as `.opus` and are indexed by MediaStore; the in-app file list can play/share/delete them.
- Boot autostart of a microphone FGS is restricted by the system on Android 12+; the receiver tolerates the rejection silently.
- Mid-stream cuts are muxed with pre-skip 0 and granule positions rebased to the cut point, so every saved file starts at zero and its duration is exact.
