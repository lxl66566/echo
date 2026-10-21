package com.lxl66566.echo

import android.content.Context
import android.media.AudioFormat
import android.media.MediaRecorder

enum class AudioSourceKind(val androidSource: Int, val label: String) {
    MIC(MediaRecorder.AudioSource.MIC, "MIC"),
    DEFAULT(MediaRecorder.AudioSource.DEFAULT, "DEFAULT"),
    CAMCORDER(MediaRecorder.AudioSource.CAMCORDER, "CAMCORDER"),
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION"),
    VOICE_COMMUNICATION(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION"),
    UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED"),
}

enum class OpusApplication(val json: String, val label: String) {
    VOIP("voip", "voip 语音优先"),
    AUDIO("audio", "audio 音乐优先"),
    LOWDELAY("lowdelay", "lowdelay 低延迟"),
}

enum class OpusSignal(val json: String, val label: String) {
    AUTO("auto", "auto 自动"),
    VOICE("voice", "voice 人声"),
    MUSIC("music", "music 音乐"),
}

enum class FrameMs(val ms: Double, val json: String) {
    MS2_5(2.5, "2.5"),
    MS5(5.0, "5"),
    MS10(10.0, "10"),
    MS20(20.0, "20"),
    MS40(40.0, "40"),
    MS60(60.0, "60"),
}

data class Settings(
    val sampleRate: Int = 16000,
    val stereo: Boolean = false,
    val frameMs: FrameMs = FrameMs.MS20,
    val bitrateBps: Int = 24000,
    val vbr: Boolean = true,
    val complexity: Int = 5,
    val application: OpusApplication = OpusApplication.VOIP,
    val signal: OpusSignal = OpusSignal.AUTO,
    val bufferSeconds: Int = 600,
    val saveSeconds: Int = 120,
    val readChunkMs: Double = 200.0,
    val source: AudioSourceKind = AudioSourceKind.MIC,
    val wakeLock: Boolean = true,
    val startOnBoot: Boolean = false,
    val noiseSuppressor: Boolean = false,
    val agc: Boolean = false,
    val aec: Boolean = false,
) {
    val channels: Int get() = if (stereo) 2 else 1
    val channelMask: Int get() = if (stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO

    /** JSON consumed by the native EngineConfig (rust/src/config.rs); keep fields in sync. */
    fun nativeJson(): String = org.json.JSONObject().apply {
        put("sample_rate", sampleRate)
        put("channels", if (stereo) "stereo" else "mono")
        put("frame_ms", frameMs.json)
        put("bitrate_bps", bitrateBps)
        put("vbr", vbr)
        put("complexity", complexity)
        put("application", application.json)
        put("signal", signal.json)
        put("buffer_seconds", bufferSeconds)
    }.toString()

    companion object {
        val SAMPLE_RATES = intArrayOf(8000, 12000, 16000, 24000, 48000)
        val READ_CHUNK_CHOICES_MS = doubleArrayOf(50.0, 100.0, 200.0, 400.0, 500.0, 1000.0)
        val BITRATE_MIN = 6000
        val BITRATE_MAX = 256000
        val BITRATE_STEP = 2000
        val BUFFER_MIN = 60
        val BUFFER_MAX = 7200
        val BUFFER_STEP = 30
    }
}

object SettingsStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("echo", Context.MODE_PRIVATE)

    fun load(ctx: Context): Settings {
        val p = prefs(ctx)
        return Settings(
            sampleRate = p.getInt("sampleRate", 16000),
            stereo = p.getBoolean("stereo", false),
            frameMs = enumPref(p, "frameMs", FrameMs.MS20),
            bitrateBps = p.getInt("bitrateBps", 24000),
            vbr = p.getBoolean("vbr", true),
            complexity = p.getInt("complexity", 5),
            application = enumPref(p, "application", OpusApplication.VOIP),
            signal = enumPref(p, "signal", OpusSignal.AUTO),
            bufferSeconds = p.getInt("bufferSeconds", 600),
            saveSeconds = p.getInt("saveSeconds", 120),
            readChunkMs = p.getFloat("readChunkMs", 200f).toDouble(),
            source = enumPref(p, "source", AudioSourceKind.MIC),
            wakeLock = p.getBoolean("wakeLock", true),
            startOnBoot = p.getBoolean("startOnBoot", false),
            noiseSuppressor = p.getBoolean("noiseSuppressor", false),
            agc = p.getBoolean("agc", false),
            aec = p.getBoolean("aec", false),
        )
    }

    fun store(ctx: Context, s: Settings) {
        prefs(ctx).edit()
            .putInt("sampleRate", s.sampleRate)
            .putBoolean("stereo", s.stereo)
            .putString("frameMs", s.frameMs.name)
            .putInt("bitrateBps", s.bitrateBps)
            .putBoolean("vbr", s.vbr)
            .putInt("complexity", s.complexity)
            .putString("application", s.application.name)
            .putString("signal", s.signal.name)
            .putInt("bufferSeconds", s.bufferSeconds)
            .putInt("saveSeconds", s.saveSeconds)
            .putFloat("readChunkMs", s.readChunkMs.toFloat())
            .putString("source", s.source.name)
            .putBoolean("wakeLock", s.wakeLock)
            .putBoolean("startOnBoot", s.startOnBoot)
            .putBoolean("noiseSuppressor", s.noiseSuppressor)
            .putBoolean("agc", s.agc)
            .putBoolean("aec", s.aec)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumPref(p: android.content.SharedPreferences, key: String, def: T): T =
        p.getString(key, null)?.let { v -> enumValues<T>().firstOrNull { it.name == v } } ?: def
}
