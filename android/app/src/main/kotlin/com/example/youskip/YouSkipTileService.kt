package com.example.youskip

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

class YouSkipTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileUI()
    }

    override fun onClick() {
        super.onClick()

        // 1. INSTANT ACTION: Flip the switch directly
        UniversalAutomatorService.isMasterSwitchOn = !UniversalAutomatorService.isMasterSwitchOn

        // 2. INSTANT TOAST: Show the message directly from the Tile
        val statusMessage = if (UniversalAutomatorService.isMasterSwitchOn) "YouSkip Resumed!" else "YouSkip Paused."
        Toast.makeText(applicationContext, statusMessage, Toast.LENGTH_SHORT).show()

        // 3. INSTANT UI: Change the color immediately
        updateTileUI()

        // 4. Send a broadcast just to tell the Main Service to update its notification
        val toggleIntent = Intent("com.example.youskip.TOGGLE_ACTION")
        toggleIntent.setPackage(packageName)
        sendBroadcast(toggleIntent)
    }

    private fun updateTileUI() {
        val tile = qsTile ?: return

        if (UniversalAutomatorService.isMasterSwitchOn) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "YouSkip: ON"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "YouSkip: OFF"
        }

        tile.updateTile()
    }
}