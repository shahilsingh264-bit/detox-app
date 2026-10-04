package com.detoxapp

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.app.usage.UsageStatsManager
import android.app.PendingIntent
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.Color
import java.util.Calendar

class UsageWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == "com.detoxapp.ACTION_TOGGLE_MODE") {
            val prefs = context.getSharedPreferences("DetoxPrefs", Context.MODE_PRIVATE)
            val isWeekly = prefs.getBoolean("widget_is_weekly", true)
            prefs.edit().putBoolean("widget_is_weekly", !isWeekly).apply()
            
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, UsageWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            onUpdate(context, appWidgetManager, appWidgetIds)
        } else if (intent.action == "com.detoxapp.ACTION_START_POMODORO") {
            val prefs = context.getSharedPreferences("DetoxPrefs", Context.MODE_PRIVATE)
            val endTime = System.currentTimeMillis() + (25 * 60 * 1000L)
            prefs.edit().putLong("pomodoroEndTime", endTime).putBoolean("isServiceEnabled", true).apply()
            
            val serviceIntent = Intent(context, DetoxService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, UsageWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            onUpdate(context, appWidgetManager, appWidgetIds)
        }
    }

    companion object {
        private fun drawableToBitmap(drawable: Drawable): Bitmap {
            if (drawable is BitmapDrawable) return drawable.bitmap
            val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        }

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_usage)
            val prefs = context.getSharedPreferences("DetoxPrefs", Context.MODE_PRIVATE)
            val isWeekly = prefs.getBoolean("widget_is_weekly", true)
            
            // PendingIntents for buttons
            val toggleIntent = Intent(context, UsageWidgetProvider::class.java).apply { action = "com.detoxapp.ACTION_TOGGLE_MODE" }
            val togglePending = PendingIntent.getBroadcast(context, 0, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_toggle_btn, togglePending)

            val pomoIntent = Intent(context, UsageWidgetProvider::class.java).apply { action = "com.detoxapp.ACTION_START_POMODORO" }
            val pomoPending = PendingIntent.getBroadcast(context, 1, pomoIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_pomodoro_btn, pomoPending)
            
            val isPomoActive = prefs.getLong("pomodoroEndTime", 0L) > System.currentTimeMillis()
            if (isPomoActive) {
                views.setTextViewText(R.id.widget_pomodoro_btn, "Focusing")
            } else {
                views.setTextViewText(R.id.widget_pomodoro_btn, "Pomodoro")
            }
            
            try {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val pm = context.packageManager
                
                val calendar = Calendar.getInstance()
                if (isWeekly) {
                    calendar.add(Calendar.DAY_OF_YEAR, -7)
                    views.setTextViewText(R.id.widget_title, "7-DAY SCREEN TIME")
                    views.setTextViewText(R.id.widget_toggle_btn, "View Today")
                } else {
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    views.setTextViewText(R.id.widget_title, "TODAY'S SCREEN TIME")
                    views.setTextViewText(R.id.widget_toggle_btn, "View Weekly")
                }
                
                val startTime = calendar.timeInMillis
                val endTime = System.currentTimeMillis()

                val stats = usm.queryAndAggregateUsageStats(startTime, endTime)
                var topApp = "None"
                var topAppPkg = ""
                var maxTime = 0L
                var totalTime = 0L

                if (stats != null) {
                    for ((pkgName, usageStats) in stats) {
                        val time = usageStats.totalTimeInForeground
                        if (time > 0 && pm.getLaunchIntentForPackage(pkgName) != null) {
                            totalTime += time
                            if (time > maxTime) {
                                maxTime = time
                                topAppPkg = pkgName
                                topApp = try {
                                    val appInfo = pm.getApplicationInfo(pkgName, 0)
                                    pm.getApplicationLabel(appInfo).toString()
                                } catch (e: Exception) {
                                    pkgName
                                }
                            }
                        }
                    }
                }

                if (topAppPkg.isNotEmpty()) {
                    try {
                        val iconDrawable = pm.getApplicationIcon(topAppPkg)
                        views.setImageViewBitmap(R.id.widget_app_icon, drawableToBitmap(iconDrawable))
                    } catch (e: Exception) {}
                }

                val totalHrs = totalTime / (1000 * 60 * 60)
                val totalMins = (totalTime / 60000) % 60
                
                val topMinsTotal = maxTime / 60000
                val topHrs = topMinsTotal / 60
                val topMins = topMinsTotal % 60
                
                val topStr = if (topHrs > 0) "${topHrs}h ${topMins}m" else "${topMins}m"
                
                views.setTextViewText(R.id.widget_total_time, "${totalHrs}h ${totalMins}m")
                views.setTextViewText(R.id.widget_top_app, "$topApp ($topStr)")
                
                val maxAllowed = if (isWeekly) (35L * 60 * 60 * 1000) else (5L * 60 * 60 * 1000)
                val yellowLimit = if (isWeekly) (20L * 60 * 60 * 1000) else (3L * 60 * 60 * 1000)
                
                val progressPercent = ((totalTime.toFloat() / maxAllowed) * 100).toInt()
                views.setProgressBar(R.id.widget_progress, 100, Math.min(progressPercent, 100), false)
                
                var grade = "A"
                var colorHex = "#00C851" // Green
                
                if (totalTime > maxAllowed) {
                    grade = "F"
                    colorHex = "#ff4444" // Red
                } else if (totalTime > yellowLimit) {
                    grade = "C"
                    colorHex = "#ffbb33" // Yellow
                } else if (totalTime > yellowLimit / 2) {
                    grade = "B"
                }

                views.setTextColor(R.id.widget_total_time, Color.parseColor(colorHex))
                
                if (!isWeekly) {
                    views.setViewVisibility(R.id.widget_grade, android.view.View.VISIBLE)
                    views.setTextViewText(R.id.widget_grade, "Grade: $grade")
                    views.setTextColor(R.id.widget_grade, Color.parseColor(colorHex))
                } else {
                    views.setViewVisibility(R.id.widget_grade, android.view.View.GONE)
                }
                
            } catch (e: Exception) {
                views.setTextViewText(R.id.widget_total_time, "Error")
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
