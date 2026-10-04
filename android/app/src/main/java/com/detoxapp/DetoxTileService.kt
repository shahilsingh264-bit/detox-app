package com.detoxapp

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.content.Context
import android.app.ActivityManager

class DetoxTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = isServiceRunning()
        
        if (!isRunning) {
            // Turn ON Service
            val intent = Intent(this, DetoxService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            // Save to prefs so BootReceiver knows it's on
            val prefs = getSharedPreferences("DetoxPrefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("isServiceEnabled", true).apply()
            
        } else {
            // Because of the strict 1-hour anti-cheat cooldown, we cannot just turn it off here.
            // We must launch the main app so the user can see the cooldown timer / warnings.
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            
            if (android.os.Build.VERSION.SDK_INT >= 34) { // UPSIDE_DOWN_CAKE
                val pendingIntent = android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        if (isServiceRunning()) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Detox ON"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Detox OFF"
        }
        tile.updateTile()
    }

    private fun isServiceRunning(): Boolean {
        val prefs = getSharedPreferences("DetoxPrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("isServiceEnabled", false)
    }
}
