package com.lxl66566.echo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private lateinit var detailText: TextView
    private lateinit var toggleButton: Button
    private lateinit var saveButton: Button
    private lateinit var restartHint: TextView
    private lateinit var saveSeekLabel: TextView

    private val refresh = object : Runnable {
        override fun run() {
            refreshStatus()
            // Keep polling regardless of state so UI resumes updating when recording starts.
            handler.postDelayed(this, if (ServiceState.running) 1000L else 2000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore.load(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        handler.postDelayed(refresh, 1000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }

        val title = TextView(this).apply {
            text = "Echo · 即时回放录音机"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(title)
        root.addView(TextView(this).apply {
            text = "持续录制，仅保留最近片段在内存；随时一键保存落盘。"
            textSize = 13f
            setPadding(0, dp(4), 0, dp(8))
        })

        detailText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = getDrawable(android.R.drawable.editbox_background)
            setMovementMethod(ScrollingMovementMethod())
            minLines = 6
        }
        root.addView(detailText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toggleButton = Button(this).apply {
            text = if (ServiceState.running) "停止录制" else "开始录制"
            setOnClickListener { onToggle() }
        }
        saveButton = Button(this).apply {
            text = "保存最近片段"
            isEnabled = ServiceState.running
            setOnClickListener {
                val s = settings
                startService(EchoService.intent(this@MainActivity, EchoService.ACTION_SAVE, s.saveSeconds.toDouble()))
            }
        }
        val filesButton = Button(this).apply {
            text = "文件"
            setOnClickListener { showFiles() }
        }
        buttons.addView(toggleButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(saveButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(filesButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        root.addView(TextView(this).apply {
            text = "保存时长（秒）"
            setPadding(0, dp(12), 0, 0)
        })
        saveSeekLabel = TextView(this).apply { text = "" }
        val saveSeek = SeekBar(this)
        saveSeek.max = ((settings.bufferSeconds - 10) / 5).coerceAtLeast(0)
        saveSeek.progress = ((settings.saveSeconds - 10) / 5).coerceIn(0, saveSeek.max)
        saveSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val v = 10 + progress * 5
                saveSeekLabel.text = "$v 秒（最长 ${settings.bufferSeconds} 秒）"
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                mutate { it.copy(saveSeconds = 10 + sb.progress * 5) }
            }
        })
        root.addView(saveSeek)
        root.addView(saveSeekLabel)

        restartHint = TextView(this).apply {
            text = "设置已更改，停止后重新开始生效"
            textSize = 13f
            visibility = View.GONE
        }
        root.addView(restartHint)

        root.addView(sectionTitle("音频 / 编码"))
        root.addView(spinnerRow(
            "采样率", Settings.SAMPLE_RATES.toList(), { "$it Hz" }, settings.sampleRate
        ) { v -> mutate { it.copy(sampleRate = v) } })
        root.addView(spinnerRow(
            "声道", listOf(false, true), { if (it) "立体声" else "单声道" }, settings.stereo
        ) { v -> mutate { it.copy(stereo = v) } })
        root.addView(spinnerRow(
            "Opus 帧长", FrameMs.entries, { "${it.ms} ms" }, settings.frameMs
        ) { v -> mutate { it.copy(frameMs = v) } })
        root.addView(spinnerRow(
            "应用模式", OpusApplication.entries, { it.label }, settings.application
        ) { v -> mutate { it.copy(application = v) } })
        root.addView(spinnerRow(
            "信号提示", OpusSignal.entries, { it.label }, settings.signal
        ) { v -> mutate { it.copy(signal = v) } })
        root.addView(seekRow(
            "比特率", Settings.BITRATE_MIN, Settings.BITRATE_MAX, Settings.BITRATE_STEP,
            settings.bitrateBps, { String.format(Locale.US, "%d kbps", it / 1000) }
        ) { v -> mutate { it.copy(bitrateBps = v) } })
        root.addView(seekRow(
            "Opus 复杂度（低=省电）", 0, 10, 1, settings.complexity, { "$it" }
        ) { v -> mutate { it.copy(complexity = v) } })
        root.addView(switchRow("VBR 可变比特率", settings.vbr) { v -> mutate { it.copy(vbr = v) } })

        root.addView(sectionTitle("缓冲 / 采集"))
        root.addView(seekRow(
            "内存缓冲时长", Settings.BUFFER_MIN, Settings.BUFFER_MAX, Settings.BUFFER_STEP,
            settings.bufferSeconds, { "%d 秒（约 %.1f MB）".format(Locale.US, it, it * settings.bitrateBps / 8.0 / 1e6) }
        ) { v -> mutate { it.copy(bufferSeconds = v) } })
        root.addView(spinnerRow(
            "读取块", Settings.READ_CHUNK_CHOICES_MS.toList(), { "${it.toInt()} ms" }, settings.readChunkMs
        ) { v -> mutate { it.copy(readChunkMs = v) } })
        root.addView(spinnerRow(
            "音频来源", AudioSourceKind.entries, { it.label }, settings.source
        ) { v -> mutate { it.copy(source = v) } })
        root.addView(switchRow("NS 降噪", settings.noiseSuppressor) { v -> mutate { it.copy(noiseSuppressor = v) } })
        root.addView(switchRow("AGC 自动增益", settings.agc) { v -> mutate { it.copy(agc = v) } })
        root.addView(switchRow("AEC 回声消除", settings.aec) { v -> mutate { it.copy(aec = v) } })

        root.addView(sectionTitle("系统"))
        root.addView(switchRow("录制时持有唤醒锁", settings.wakeLock) { v -> mutate { it.copy(wakeLock = v) } })
        root.addView(switchRow("开机自动录制", settings.startOnBoot) { v -> mutate { it.copy(startOnBoot = v) } })

        root.addView(TextView(this).apply {
            text = "文件保存在 Music/Echo 目录（.opus）。保存动作从下拉通知 / 磁贴 / 本页面触发。"
            textSize = 12f
            setPadding(0, dp(16), 0, 0)
        })

        val scroll = ScrollView(this)
        scroll.addView(root)
        return scroll
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun row(label: String, control: View): View {
        val pad = TextView(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.45f)
            gravity = Gravity.CENTER_VERTICAL
        }
        control.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.55f)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
            addView(pad)
            addView(control)
        }
    }

    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): View =
        row(label, Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })

    private fun seekRow(
        label: String, lo: Int, hi: Int, step: Int, init: Int,
        format: (Int) -> String, onChange: (Int) -> Unit,
    ): View {
        val valueLabel = TextView(this).apply { text = format(init) }
        val sb = SeekBar(this).apply {
            max = (hi - lo) / step
            progress = (init - lo) / step
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    valueLabel.text = format(lo + p * step)
                }

                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    onChange(lo + s.progress * step)
                }
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sb)
            addView(valueLabel)
        }
        return row(label, box)
    }

    private fun <T> spinnerRow(
        label: String, options: List<T>, labels: (T) -> String, init: T, onChange: (T) -> Unit,
    ): View {
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                options.map(labels),
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(options.indexOf(init).coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    onChange(options[pos])
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }
        return row(label, sp)
    }

    private fun mutate(f: (Settings) -> Settings) {
        settings = f(settings)
        SettingsStore.store(this, settings)
        restartHint.visibility = if (ServiceState.running) View.VISIBLE else View.GONE
    }

    private fun onToggle() {
        if (ServiceState.running) {
            startService(EchoService.intent(this, EchoService.ACTION_STOP))
            return
        }
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            startForegroundService(EchoService.intent(this, EchoService.ACTION_START))
        } else {
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startForegroundService(EchoService.intent(this, EchoService.ACTION_START))
        } else {
            Toast.makeText(this, "缺少录音权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshStatus() {
        toggleButton.text = if (ServiceState.running) "停止录制" else "开始录制"
        saveButton.isEnabled = ServiceState.running
        if (!ServiceState.running && restartHint.visibility == View.VISIBLE) {
            restartHint.visibility = View.GONE
        }
        val sb = StringBuilder()
        sb.append("状态: ").append(if (ServiceState.running) "录制中" else "空闲").append('\n')
        try {
            if (ServiceState.running) {
                val st = JSONObject(ServiceState.lastStatsJson)
                val elapsed = (android.os.SystemClock.elapsedRealtime() - ServiceState.startedAtMs) / 1000
                sb.append("运行: ").append(formatDuration(elapsed)).append('\n')
                sb.append("缓冲: %.1f / %.0f 秒".format(
                    Locale.US, st.optDouble("buffered_seconds", 0.0), st.optDouble("capacity_seconds", 0.0)))
                    .append('\n')
                sb.append("帧: ").append(st.optLong("frames_encoded", 0))
                    .append(" 已编码 / ").append(st.optLong("frames_dropped", 0))
                    .append(" 已淘汰").append('\n')
                sb.append("编码输出: %.2f MB".format(Locale.US, st.optDouble("encoded_bytes", 0.0) / 1e6)).append('\n')
                ServiceState.lastSaveResult?.let {
                    val o = JSONObject(it)
                    sb.append("上次保存: ").append(if (o.optBoolean("ok"))
                        "%.1f 秒 / %.2f MB".format(
                            Locale.US, o.optDouble("seconds_saved", 0.0), o.optDouble("bytes_written", 0.0) / 1e6)
                        else o.optString("error")).append('\n')
                }
            }
        } catch (_: Exception) {
        }
        if (!ServiceState.running) sb.append("上次保存: ").append(ServiceState.lastSaveResult?.let { r ->
            try {
                val o = JSONObject(r)
                if (o.optBoolean("ok")) "%.1f 秒".format(Locale.US, o.optDouble("seconds_saved", 0.0))
                else o.optString("error")
            } catch (_: Exception) {
                "?"
            }
        } ?: "无").append('\n')
        sb.append("配置: ").append(settings.sampleRate).append("Hz ")
            .append(if (settings.stereo) "立体声" else "单声道")
            .append(" ").append(settings.bitrateBps / 1000).append("kbps")
            .append(" 复杂度").append(settings.complexity)
            .append('\n')
        detailText.text = sb
    }

    private fun formatDuration(sec: Long): String {
        val h = sec / 3600
        val m = sec % 3600 / 60
        val s = sec % 60
        return "%02d:%02d:%02d".format(Locale.US, h, m, s)
    }

    private fun showFiles() {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val items = mutableListOf<Triple<Uri, String, Long>>()
        contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("Music/Echo%"),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                items.add(Triple(
                    ContentUris.withAppendedId(collection, c.getLong(0)),
                    c.getString(1),
                    c.getLong(2),
                ))
            }
        }
        if (items.isEmpty()) {
            Toast.makeText(this, "暂无录音文件（Music/Echo）", Toast.LENGTH_SHORT).show()
            return
        }
        val names = items.map { "${it.second}  ·  ${it.third / 1024} KB" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("录音文件")
            .setItems(names) { _, which -> showItemActions(items[which]) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showItemActions(item: Triple<Uri, String, Long>) {
        val actions = arrayOf("播放", "分享", "删除")
        AlertDialog.Builder(this)
            .setTitle(item.second)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(item.first, "audio/ogg")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }.onFailure { Toast.makeText(this, "无可用播放器: ${it.message}", Toast.LENGTH_SHORT).show() }
                    1 -> runCatching {
                        startActivity(Intent(Intent.ACTION_SEND).setType("audio/ogg")
                            .putExtra(Intent.EXTRA_STREAM, item.first)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                    2 -> AlertDialog.Builder(this)
                        .setMessage("删除 ${item.second}？")
                        .setPositiveButton("删除") { _, _ ->
                            contentResolver.delete(item.first, null, null)
                            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
