package com.detoxapp

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class DetoxAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // When the user clicks "Deactivate Device Admin" in settings,
        // we instantly fire a force-lock intent to our background service!
        val serviceIntent = Intent(context, DetoxService::class.java)
        serviceIntent.action = "FORCE_LOCK_ADMIN"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        return "Active App Blocks are running! You cannot uninstall this app right now."
    }
}
