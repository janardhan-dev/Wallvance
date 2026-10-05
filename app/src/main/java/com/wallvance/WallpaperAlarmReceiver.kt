package com.wallvance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WallpaperAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("wallvance_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("running", false)) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val changed = WallpaperEngine.changeWallpaper(appContext)
                if (changed) {
                    val currentPrefs = appContext.getSharedPreferences("wallvance_prefs", Context.MODE_PRIVATE)
                    if (currentPrefs.getBoolean("running", false)) {
                        WallpaperEngine.scheduleNext(
                            context = appContext,
                            interval = currentPrefs.getString("interval", "30 minutes") ?: "30 minutes",
                            customValue = currentPrefs.getString("custom_value", "30") ?: "30",
                            customUnit = currentPrefs.getString("custom_unit", "Minutes") ?: "Minutes"
                        )
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
