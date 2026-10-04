package com.detoxapp

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableNativeArray
import com.facebook.react.bridge.WritableNativeMap

class DetoxModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String {
        return "DetoxModule"
    }

    @ReactMethod
    fun getInstalledApps(promise: Promise) {
        try {
            val pm = reactApplicationContext.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val writableArray: WritableArray = WritableNativeArray()

            for (packageInfo in packages) {
                if (pm.getLaunchIntentForPackage(packageInfo.packageName) != null) {
                    val map = WritableNativeMap()
                    map.putString("appName", pm.getApplicationLabel(packageInfo).toString())
                    map.putString("packageName", packageInfo.packageName)
                    
                    var categoryStr = "Other"
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        when (packageInfo.category) {
                            android.content.pm.ApplicationInfo.CATEGORY_GAME -> categoryStr = "Games"
                            android.content.pm.ApplicationInfo.CATEGORY_AUDIO -> categoryStr = "Audio"
                            android.content.pm.ApplicationInfo.CATEGORY_VIDEO -> categoryStr = "Video"
                            android.content.pm.ApplicationInfo.CATEGORY_IMAGE -> categoryStr = "Image"
                            android.content.pm.ApplicationInfo.CATEGORY_SOCIAL -> categoryStr = "Social"
                            android.content.pm.ApplicationInfo.CATEGORY_NEWS -> categoryStr = "News"
                            android.content.pm.ApplicationInfo.CATEGORY_MAPS -> categoryStr = "Maps"
                            android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY -> categoryStr = "Productivity"
                        }
                    }
                    if (categoryStr == "Other") {
                        val pkg = packageInfo.packageName.lowercase()
                        if (pkg.contains("instagram") || pkg.contains("facebook") || pkg.contains("twitter") || pkg.contains("snapchat") || pkg.contains("tiktok") || pkg.contains("reddit")) categoryStr = "Social"
                        else if (pkg.contains("youtube") || pkg.contains("netflix") || pkg.contains("hulu")) categoryStr = "Video"
                        else if (pkg.contains("spotify") || pkg.contains("music")) categoryStr = "Audio"
                        else if (pkg.contains("maps") || pkg.contains("uber") || pkg.contains("lyft")) categoryStr = "Maps"
                        else if (pkg.contains("mail") || pkg.contains("docs") || pkg.contains("calendar")) categoryStr = "Productivity"
                    }
                    map.putString("category", categoryStr)
                    writableArray.pushMap(map)
                }
            }
            promise.resolve(writableArray)
        } catch (e: Exception) {
            promise.reject("Error", e)
        }
    }

    @ReactMethod
    fun checkUsageStatsPermission(promise: Promise) {
        val appOps = reactApplicationContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            reactApplicationContext.packageName
        )
        promise.resolve(mode == AppOpsManager.MODE_ALLOWED)
    }

    @ReactMethod
    fun requestUsageStatsPermission() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        reactApplicationContext.startActivity(intent)
    }

    @ReactMethod
    fun checkOverlayPermission(promise: Promise) {
        promise.resolve(Settings.canDrawOverlays(reactApplicationContext))
    }

    @ReactMethod
    fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(reactApplicationContext)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${reactApplicationContext.packageName}")
            )
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            reactApplicationContext.startActivity(intent)
        }
    }

    @ReactMethod
    fun checkDeviceAdmin(promise: Promise) {
        val dpm = reactApplicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val compName = android.content.ComponentName(reactApplicationContext, DetoxAdminReceiver::class.java)
        promise.resolve(dpm.isAdminActive(compName))
    }

    @ReactMethod
    fun requestDeviceAdmin() {
        val compName = android.content.ComponentName(reactApplicationContext, DetoxAdminReceiver::class.java)
        val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        intent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName)
        intent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "We need this to prevent you from uninstalling the app while blocks are active.")
        
        val activity = getCurrentActivity()
        if (activity != null) {
            activity.startActivity(intent)
        } else {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            reactApplicationContext.startActivity(intent)
        }
    }

    @ReactMethod
    fun startPomodoro(durationMinutes: Int, promise: Promise) {
        val prefs = reactApplicationContext.getSharedPreferences("DetoxPrefs", Context.MODE_PRIVATE)
        val endTime = System.currentTimeMillis() + (durationMinutes * 60 * 1000L)
        prefs.edit().putLong("pomodoroEndTime", endTime).apply()
        promise.resolve(endTime.toString())
    }

    @ReactMethod
    fun getPomodoroState(promise: Promise) {
        val prefs = reactApplicationContext.getSharedPreferences("DetoxPrefs", Context.MODE_PRIVATE)
        val endTime = prefs.getLong("pomodoroEndTime", 0L)
        val whitelist = prefs.getString("pomodoroWhitelist", "[]")
        
        val map = com.facebook.react.bridge.WritableNativeMap()
        map.putString("endTime", endTime.toString())
        map.putString("whitelist", whitelist)
        promise.resolve(map)
    }

    @ReactMethod
    fun savePomodoroWhitelist(jsonStr: String, promise: Promise) {
        val prefs = reactApplicationContext.getSharedPreferences("DetoxPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("pomodoroWhitelist", jsonStr).apply()
        promise.resolve(null)
    }

    @ReactMethod
    fun startDetoxService() {
        val prefs = reactApplicationContext.getSharedPreferences("DetoxPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("isServiceEnabled", true).apply()

        val intent = Intent(reactApplicationContext, DetoxService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            reactApplicationContext.startForegroundService(intent)
        } else {
            reactApplicationContext.startService(intent)
        }
    }

    @ReactMethod
    fun stopDetoxService() {
        val prefs = reactApplicationContext.getSharedPreferences("DetoxPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("isServiceEnabled", false).apply()

        val intent = Intent(reactApplicationContext, DetoxService::class.java)
        reactApplicationContext.stopService(intent)
    }

    @ReactMethod
    fun getServiceStatus(promise: Promise) {
        val prefs = reactApplicationContext.getSharedPreferences("DetoxPrefs", Context.MODE_PRIVATE)
        promise.resolve(prefs.getBoolean("isServiceEnabled", false))
    }

    @ReactMethod
    fun getUsageStatsToday(promise: Promise) {
        val usm = reactApplicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val pm = reactApplicationContext.packageManager
        
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val stats = usm.queryAndAggregateUsageStats(startTime, endTime)
        val array = com.facebook.react.bridge.WritableNativeArray()
        
        if (stats != null) {
            for ((pkgName, usageStats) in stats) {
                if (usageStats.totalTimeInForeground > 60000) { // Only show apps used for > 1 minute
                    val map = com.facebook.react.bridge.WritableNativeMap()
                    map.putString("packageName", pkgName)
                    map.putDouble("totalTimeInForeground", usageStats.totalTimeInForeground.toDouble())
                    
                    try {
                        val appInfo = pm.getApplicationInfo(pkgName, 0)
                        val appName = pm.getApplicationLabel(appInfo).toString()
                        map.putString("appName", appName)
                    } catch (e: Exception) {
                        map.putString("appName", pkgName)
                    }
                    
                    array.pushMap(map)
                }
            }
        }
        promise.resolve(array)
    }

    @ReactMethod
    fun saveBlockedApps(jsonStr: String) {
        val prefs = reactApplicationContext.getSharedPreferences("DetoxPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("blockedApps", jsonStr).apply()
    }

    @ReactMethod
    fun getBlockedApps(promise: Promise) {
        val prefs = reactApplicationContext.getSharedPreferences("DetoxPrefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("blockedApps", "[]")
        promise.resolve(jsonStr)
    }
}
