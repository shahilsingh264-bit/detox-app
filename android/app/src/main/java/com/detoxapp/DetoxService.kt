package com.detoxapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

class DetoxService : Service() {
    private val CHANNEL_ID = "DetoxServiceChannel"
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var lastBlockedApp: String = ""

    private val runnable = object : Runnable {
        override fun run() {
            checkForegroundApp()
            if (isRunning) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Detox Active")
                .setContentText("Monitoring your app usage...")
                .setSmallIcon(android.R.drawable.ic_secure)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("Detox Active")
                .setContentText("Monitoring your app usage...")
                .setSmallIcon(android.R.drawable.ic_secure)
                .build()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, 0x40000000)
        } else {
            startForeground(1, notification)
        }

        if (intent?.action == "FORCE_LOCK_ADMIN") {
            lastBlockedApp = "com.android.settings" // Dummy package to prevent instant hiding
            showLockScreen()
            
            // Wait 5 seconds then hide it so they aren't permanently locked out of settings
            handler.postDelayed({ hideLockScreen() }, 5000)
        }

        if (!isRunning) {
            isRunning = true
            handler.post(runnable)
        }

        return START_STICKY
    }

    private fun checkForegroundApp() {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()
        var topPackageName = ""
        val events = usageStatsManager.queryEvents(time - 1000 * 60 * 60, time) // Last hour
        val event = android.app.usage.UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                topPackageName = event.packageName ?: ""
            }
        }

        if (topPackageName.isEmpty()) return

        // Read blocked apps and pomodoro state from SharedPreferences
        val prefs = getSharedPreferences("DetoxPrefs", Context.MODE_PRIVATE)
        val blockedAppsJsonStr = prefs.getString("blockedApps", "[]") ?: "[]"
        val pomodoroEndTime = prefs.getLong("pomodoroEndTime", 0L)
        val pomodoroWhitelistStr = prefs.getString("pomodoroWhitelist", "[]") ?: "[]"
        
        try {
            var isBlocked = false
            
            // 1. Check Pomodoro
            if (time < pomodoroEndTime) {
                isBlocked = true
                try {
                    val whitelist = org.json.JSONArray(pomodoroWhitelistStr)
                    for (i in 0 until whitelist.length()) {
                        if (whitelist.getString(i) == topPackageName) {
                            isBlocked = false
                            break
                        }
                    }
                } catch (e: Exception) {}
            }
            
            // 2. Check Standard Schedule Block (if not already blocked by Pomodoro)
            if (!isBlocked) {
                val blockedApps = JSONObject(blockedAppsJsonStr)
                if (blockedApps.has(topPackageName)) {
                    val schedule = blockedApps.getJSONObject(topPackageName)
                    val startTime = schedule.getString("start")
                    val endTime = schedule.getString("end")

                    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    val currentTimeStr = sdf.format(java.util.Date())
                    
                    if (startTime <= endTime) {
                        if (currentTimeStr in startTime..endTime) {
                            isBlocked = true
                        }
                    } else {
                        // Overnight window
                        if (currentTimeStr >= startTime || currentTimeStr <= endTime) {
                            isBlocked = true
                        }
                    }
                }
            }

            // Do not block our own app or the launcher
            if (topPackageName == packageName || topPackageName.contains("launcher") || topPackageName.contains("systemui")) {
                isBlocked = false
            }

            if (isBlocked) {
                if (overlayView == null || topPackageName != lastBlockedApp) {
                    lastBlockedApp = topPackageName
                    showLockScreen()
                }
            } else {
                hideLockScreen()
                lastBlockedApp = ""
            }
        } catch (e: Exception) {
            Log.e("DetoxService", "Error in blocking logic", e)
        }
    }

    private fun showLockScreen() {
        handler.post {
            if (overlayView != null) return@post

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            )

            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#121212"))
                gravity = Gravity.CENTER
            }

            // Create a breathing circle
            val breathCircle = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(400, 400).apply {
                    setMargins(0, 60, 0, 60)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#BB86FC")) // Primary Purple
                }
            }
            
            // Animate it
            val scaleX = android.animation.ObjectAnimator.ofFloat(breathCircle, "scaleX", 1f, 1.4f, 1f)
            val scaleY = android.animation.ObjectAnimator.ofFloat(breathCircle, "scaleY", 1f, 1.4f, 1f)
            scaleX.repeatCount = android.animation.ValueAnimator.INFINITE
            scaleY.repeatCount = android.animation.ValueAnimator.INFINITE
            scaleX.duration = 5000 // 5 seconds per breath
            scaleY.duration = 5000
            val animatorSet = android.animation.AnimatorSet()
            animatorSet.playTogether(scaleX, scaleY)
            animatorSet.start()

            val titleView = TextView(this).apply {
                text = "Detox Active"
                setTextColor(Color.WHITE)
                textSize = 28f
                gravity = Gravity.CENTER
            }

            val subtitleView = TextView(this).apply {
                text = "Take a deep breath.\nNo need to open this app right now."
                setTextColor(Color.LTGRAY)
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(40, 40, 40, 40)
            }

            val homeButton = Button(this).apply {
                text = "Go Home"
                setBackgroundColor(Color.DKGRAY)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    val startMain = Intent(Intent.ACTION_MAIN)
                    startMain.addCategory(Intent.CATEGORY_HOME)
                    startMain.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(startMain)
                    hideLockScreen()
                }
            }

            layout.addView(titleView)
            layout.addView(breathCircle)
            layout.addView(subtitleView)
            layout.addView(homeButton)

            overlayView = layout
            windowManager?.addView(overlayView, params)
        }
    }

    private fun hideLockScreen() {
        handler.post {
            if (overlayView != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        hideLockScreen()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Detox Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
