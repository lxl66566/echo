package com.lxl66566.echo

import java.nio.ByteBuffer

/** JNI bindings to the Rust engine (rust/src/bridge.rs). All results are JSON strings. */
object Native {
    init {
        System.loadLibrary("echo_core")
    }

    /** Returns {"ok":true,"handle":N} or {"ok":false,"error":"..."}. */
    external fun create(configJson: String): String

    external fun destroy(handle: Long)

    /**
     * Encodes byteCount bytes of native-endian interleaved PCM16 from the direct buffer.
     * Returns null on success, an error JSON on failure.
     */
    external fun push(handle: Long, buffer: ByteBuffer, byteCount: Int): String?

    /** Writes the last `seconds` of the ring buffer as Ogg Opus into fd; returns a result JSON. */
    external fun save(handle: Long, seconds: Double, fd: Int): String

    external fun stats(handle: Long): String
}
