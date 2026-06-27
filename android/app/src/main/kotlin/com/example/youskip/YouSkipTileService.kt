package com.example.youskip

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
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

        // 1. Check if the system permission is actually granted first!
        if (!isAccessibilityServiceEnabled(this)) {
            Toast.makeText(this, "Enable YouSkip in Accessibility Settings first!", Toast.LENGTH_SHORT).show()
            val tile = qsTile
            tile?.state = Tile.STATE_UNAVAILABLE
            tile?.updateTile()
            return // Stop here. Do not toggle the switch.
        }

        // 2. Permission is granted, proceed with toggle
        UniversalAutomatorService.isMasterSwitchOn = !UniversalAutomatorService.isMasterSwitchOn

        val toggleIntent = Intent("com.example.youskip.TOGGLE_ACTION")
        toggleIntent.setPackage(packageName)
        sendBroadcast(toggleIntent)

        updateTileUI()
    }

    private fun updateTileUI() {
        val tile = qsTile ?: return

        if (!isAccessibilityServiceEnabled(this)) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = "YouSkip: Disabled"
        } else if (UniversalAutomatorService.isMasterSwitchOn) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "YouSkip: ON"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "YouSkip: OFF"
        }
        tile.updateTile()
    }

    // Helper to check system permission
    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedComponentName = ComponentName(context, UniversalAutomatorService::class.java)
        val enabledSettings = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabledSettings.split(':').any {
            val enabledService = ComponentName.unflattenFromString(it)
            enabledService != null && enabledService == expectedComponentName
        }
    }
}