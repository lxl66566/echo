---
name: echo-perf
description: Performance design invariants of the Echo instant-replay recorder (encode-once, JNI zero-copy, encoded-packet ring buffer, hot-path discipline). Use whenever changing Echo's audio data path, Rust engine, JNI bridge, buffer or settings semantics, or reviewing/optimizing Echo performance — even for seemingly small edits to those files.
---

# Echo performance invariants

Echo is designed for all-day recording on battery. The decisions below are load-bearing: when changing the audio data path, keep them intact. If a change must break one, state that explicitly in the commit message and update this skill.

Key files: `app/src/main/java/com/lxl66566/echo/EchoService.kt` (audio loop), `app/src/main/java/com/lxl66566/echo/Native.kt`, `rust/src/bridge.rs` (JNI), `rust/src/engine.rs` (ring buffer), `rust/src/ogg.rs` (muxing).

## Architecture: encode once, buffer compressed

- PCM is encoded to Opus as soon as it arrives; the ring buffer stores encoded packets only. RAM is bounded at roughly `bitrate × buffer_seconds / 8` (≈ 1.8 MB for 24 kbps × 600 s), versus tens of MB for raw PCM.
- Never hold raw PCM beyond one frame. Never decode or re-encode on save; saving is pure Ogg muxing of already-encoded packets.

## JNI boundary is zero-copy

- Kotlin owns a single `ByteBuffer.allocateDirect` buffer reused on every read (`audioLoop`); `AudioRecord.read` writes into it directly.
- Rust wraps that memory via `get_direct_buffer_address` + `slice::from_raw_parts`; no `GetByteArrayElements`-style copy exists anywhere on the path.
- `push` returns null on success: the happy path allocates no jstring/JSON. Errors pay that cost only when they occur.
- `save` receives a raw fd, wrapped as `File::from_raw_fd` under `ManuallyDrop` (Kotlin keeps fd ownership) with a 64 KiB `BufWriter`. Do not route saves through Java streams or byte arrays.
- Do not add byte[]-based JNI overloads; a byte[] copy would reintroduce the copy the direct buffer exists to avoid.
- Two small copies inside the engine are accepted: the LE byte→`u16` conversion into `pending`, and the encoded packet's `to_vec` into the ring. Both are negligible next to Opus encoding and keep the code in safe Rust; do not "optimize" them into unsafe reinterpreting without a measured win.

## Ring buffer: deque of encoded packets

- `Engine.frames` is a `VecDeque<Frame>`, one Opus packet plus its sample count per element. Capacity is accounted in samples; overflow evicts via `pop_front` (O(1)) and counts `frames_dropped`.
- Why a packet deque instead of a contiguous byte ring: Opus packets are variable-length (2–1275 B), and owning packets lets `save` mux by slice reference (`iter().skip(idx)`) with zero window reassembly or copying.
- `pending` is a frame-assembly buffer sized for about two frames; keep it that small.

## Hot-path discipline

- One dedicated `EchoAudio` thread; no handlers or executors in the read loop.
- The read chunk length must stay a whole multiple of the Opus frame length, so one `read` plus one JNI `push` encodes N full frames (default 200 ms = 10 × 20 ms).
- Encoded output lands in a stack buffer `[u8; 1275]` before the single `to_vec` into the ring; no per-frame heap scratch.
- All configuration is parsed and validated once in `create` via enums (`config.rs`); the hot loop contains no config checks.
- `stats` calls and notification updates are throttled to 5 s (`NOTIF_UPDATE_MS`); keep that throttle.
- The `AudioRecord` buffer is `max(minBuf * 2, chunkBytes * 4)` to absorb jitter, including save-time stalls.

## Known tradeoff: shared engine mutex

- `push` and `save` share one `Mutex<Engine>`. While a save is muxing, the audio thread blocks briefly and `AudioRecord`'s own buffer absorbs it. This simplicity is deliberate; do not replace it with finer-grained locking without a measured need.

## Build profile

- `rust/Cargo.toml` release profile: `lto = "fat"`, `codegen-units = 1`, `panic = "abort"`, `strip = true`. Keep these.
- The encoder is `opusic-c`: real libopus C sources built by the NDK (SIMD-optimized), not a pure-Rust reimplementation.
