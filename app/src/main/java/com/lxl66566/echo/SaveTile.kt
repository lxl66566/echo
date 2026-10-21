package com.lxl66566.echo

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/** QS tile: save the replay buffer when recording, start recording otherwise. */
class SaveTile : TileService() {

    override fun onStartListening() {
        syncState()
    }

    override fun onClick() {
        super.onClick()
        val ctx = applicationContext
        if (ServiceState.running) {
            ctx.startService(EchoService.intent(ctx, EchoService.ACTION_SAVE))
        } else {
            try {
                ctx.startForegroundService(EchoService.intent(ctx, EchoService.ACTION_START))
            } catch (e: Exception) {
                Log.e("SaveTile", "start recording failed", e)
            }
        }
        syncState()
    }

    private fun syncState() {
        val t = qsTile ?: return
        t.state = if (ServiceState.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}
