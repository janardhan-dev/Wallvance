package com.wallvance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WallpaperBootReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent?
    ) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            WallpaperEngine.resumeAfterBoot(context)
        }
    }
}