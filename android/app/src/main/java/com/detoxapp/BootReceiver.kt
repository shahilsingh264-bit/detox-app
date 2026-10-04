package com.detoxapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d("BootReceiver", "Device booted! Checking if DetoxService should start...")
            
            val prefs = context.getSharedPreferences("DetoxPrefs", Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("isServiceEnabled", false)
            
            if (isEnabled) {
                Log.d("BootReceiver", "Starting DetoxService...")
                val serviceIntent = Intent(context, DetoxService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
