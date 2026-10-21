package com.lxl66566.echo

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SettingsStore.load(context).startOnBoot) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        try {
            context.startForegroundService(EchoService.intent(context, EchoService.ACTION_START))
        } catch (_: Exception) {
            // Starting a microphone FGS from BOOT_COMPLETED is restricted on API 31+; tolerate.
        }
    }
}
