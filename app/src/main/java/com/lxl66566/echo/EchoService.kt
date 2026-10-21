package com.lxl66566.echo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class EchoService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val saveExecutor = Executors.newSingleThreadExecutor()

    private var settings: Settings = Settings()
    private var handle: Long = 0
    private var record: AudioRecord? = null
    private var audioThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var effects: List<AudioEffect> = emptyList()
    private val running = AtomicBoolean(false)
    private val startLock = Any()
    private var lastNotifUpdateMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                startForegroundCompat()
                stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SAVE -> {
                startForegroundCompat()
                val seconds = intent.getDoubleExtra(EXTRA_SECONDS, SettingsStore.load(this).saveSeconds.toDouble())
                saveExecutor.execute { save(seconds) }
            }
            else -> {
                startForegroundCompat()
                startRecording()
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "录音", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                setShowBadge(false)
            }
        )
        startForeground(NOTIF_ID, buildNotification("准备中"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    private fun startRecording() {
        synchronized(startLock) {
            if (running.get()) return
            settings = SettingsStore.load(this)

            val created = Native.create(settings.nativeJson())
            val ok = JSONObject(created)
            if (!ok.optBoolean("ok")) {
                toast("引擎初始化失败: ${ok.optString("error")}")
                stopSelf()
                return
            }
            handle = ok.getLong("handle")

            val samplesPerFrame = (settings.sampleRate * settings.frameMs.ms / 1000.0).toInt()
            val framesPerChunk = (settings.readChunkMs / settings.frameMs.ms).toInt().coerceAtLeast(1)
            val chunkBytes = framesPerChunk * samplesPerFrame * settings.channels * 2
            val minBuf = AudioRecord.getMinBufferSize(
                settings.sampleRate, settings.channelMask, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                destroyEngine()
                toast("getMinBufferSize 失败: $minBuf")
                stopSelf()
                return
            }
            val r = try {
                AudioRecord.Builder()
                    .setAudioSource(settings.source.androidSource)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(settings.sampleRate)
                            .setChannelMask(settings.channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf * 2, chunkBytes * 4))
                    .build()
            } catch (e: Exception) {
                destroyEngine()
                toast("AudioRecord 创建失败: ${e.message}")
                stopSelf()
                return
            }
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                destroyEngine()
                toast("AudioRecord 初始化失败")
                stopSelf()
                return
            }
            record = r
            enableEffects(r.audioSessionId)

            if (settings.wakeLock) {
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Echo:rec").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }

            ServiceState.running = true
            ServiceState.handle = handle
            ServiceState.startedAtMs = SystemClock.elapsedRealtime()
            ServiceState.lastStatsJson = "{}"
            running.set(true)
            audioThread = Thread({ audioLoop(chunkBytes) }, "EchoAudio").also { it.start() }
            updateNotification(force = true)
        }
    }

    private fun audioLoop(chunkBytes: Int) {
        val r = record ?: return
        val buf = ByteBuffer.allocateDirect(chunkBytes).order(ByteOrder.nativeOrder())
        r.startRecording()
        while (running.get()) {
            buf.clear()
            val n = try {
                r.read(buf, chunkBytes)
            } catch (e: Exception) {
                Log.e(TAG, "read failed", e)
                break
            }
            if (n < 0) {
                Log.e(TAG, "read returned $n")
                break
            }
            if (n == 0) continue
            Native.push(handle, buf, n)?.let { err ->
                Log.e(TAG, "push: $err")
                break
            }
            maybeUpdateNotification()
        }
        // Fatal path: the read loop exited while we were supposed to be recording.
        if (running.getAndSet(false)) {
            mainHandler.post {
                synchronized(startLock) {
                    stopEngineLocked()
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopRecording() {
        synchronized(startLock) {
            running.set(false)
            try {
                record?.stop()
            } catch (_: Exception) {
            }
            audioThread?.let { t -> if (t !== Thread.currentThread()) t.join(1500) }
            audioThread = null
            stopEngineLocked()
        }
    }

    /** Releases everything except joining the audio thread; must hold startLock. */
    private fun stopEngineLocked() {
        effects.forEach { runCatching { it.setEnabled(false); it.release() } }
        effects = emptyList()
        record?.release()
        record = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        destroyEngine()
        ServiceState.running = false
        ServiceState.handle = 0
    }

    private fun destroyEngine() {
        if (handle != 0L) {
            Native.destroy(handle)
            handle = 0
        }
    }

    private fun enableEffects(sessionId: Int) {
        val list = ArrayList<AudioEffect>()
        fun attach(enabled: Boolean, create: () -> AudioEffect?) {
            if (enabled) {
                create()?.let { effect ->
                    effect.setEnabled(true)
                    list.add(effect)
                }
            }
        }
        attach(settings.noiseSuppressor) { NoiseSuppressor.create(sessionId) }
        attach(settings.agc) { AutomaticGainControl.create(sessionId) }
        attach(settings.aec) { AcousticEchoCanceler.create(sessionId) }
        effects = list
    }

    private fun maybeUpdateNotification() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifUpdateMs < NOTIF_UPDATE_MS) return
        lastNotifUpdateMs = now
        ServiceState.lastStatsJson = Native.stats(handle)
        updateNotification()
    }

    private fun updateNotification(force: Boolean = false) {
        val text = try {
            val st = JSONObject(ServiceState.lastStatsJson)
            val buffered = st.optDouble("buffered_seconds", 0.0)
            val cap = st.optDouble("capacity_seconds", settings.bufferSeconds.toDouble())
            val drop = st.optLong("frames_dropped", 0)
            "缓冲 %.0f/%.0f 秒 · 丢弃 %d 帧".format(Locale.US, buffered, cap, drop)
        } catch (_: Exception) {
            "缓冲中"
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val savePi = PendingIntent.getService(this, 1, intent(this, ACTION_SAVE), PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(this, 2, intent(this, ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_echo)
            .setContentTitle("Echo 录制中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_echo), "保存", savePi
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause), "停止", stopPi
                ).build()
            )
            .build()
    }

    private fun save(seconds: Double) {
        val h = handle
        if (h == 0L || !running.get()) {
            ServiceState.lastSaveResult = """{"ok":false,"error":"未在录制"}"""
            toast("未在录制")
            return
        }
        val name = "Echo_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".opus"
        val resolver = contentResolver
        val uri = resolver.insert(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/ogg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/Echo")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        )
        if (uri == null) {
            ServiceState.lastSaveResult = """{"ok":false,"error":"MediaStore insert 失败"}"""
            toast("MediaStore insert 失败")
            return
        }
        try {
            val result = resolver.openFileDescriptor(uri, "wt")?.use { pfd ->
                Native.save(h, seconds, pfd.fd)
            } ?: throw IllegalStateException("openFileDescriptor 失败")
            ServiceState.lastSaveResult = result
            val o = JSONObject(result)
            if (!o.optBoolean("ok")) {
                resolver.delete(uri, null, null)
                toast("保存失败: ${o.optString("error")}")
                return
            }
            resolver.update(
                uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
            )
            toast("已保存 $name（${"%.1f".format(o.optDouble("seconds_saved", 0.0))} 秒）")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            ServiceState.lastSaveResult = """{"ok":false,"error":"${e.message}"}"""
            toast("保存失败: ${e.message}")
        }
    }

    private fun toast(msg: String) {
        mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        saveExecutor.shutdown()
        synchronized(startLock) { stopEngineLocked() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EchoService"
        const val ACTION_START = "com.lxl66566.echo.START"
        const val ACTION_STOP = "com.lxl66566.echo.STOP"
        const val ACTION_SAVE = "com.lxl66566.echo.SAVE"
        const val EXTRA_SECONDS = "seconds"
        private const val CHANNEL_ID = "echo_rec"
        private const val NOTIF_ID = 1
        private const val NOTIF_UPDATE_MS = 5000L

        fun intent(ctx: Context, action: String, seconds: Double? = null): Intent =
            Intent(ctx, EchoService::class.java).setAction(action).also { i ->
                seconds?.let { i.putExtra(EXTRA_SECONDS, it) }
            }
    }
}
