package com.wallvance

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object WallpaperEngine {

    private const val PREFS = "wallvance_prefs"

    private const val KEY_IMAGES = "images"
    private const val KEY_INTERVAL = "interval"
    private const val KEY_ORDER = "order"
    private const val KEY_TARGET = "target"

    private const val KEY_INDEX = "index"
    private const val KEY_LAST_INDEX = "last_index"

    private const val KEY_CUSTOM_VALUE = "custom_value"
    private const val KEY_CUSTOM_UNIT = "custom_unit"

    private const val KEY_LAST_CHANGED_TIME = "last_changed_time"
    private const val KEY_LAST_CHANGED_URI = "last_changed_uri"
    private const val KEY_NEXT_CHANGE_TIME = "next_change_time"

    private const val KEY_RUNNING = "running"

    private const val ALARM_REQUEST_CODE = 1001

    /*
     * Only one wallpaper operation may run at a time inside the app process.
     *
     * This prevents accidental overlapping calls if Android delivers
     * another broadcast while a wallpaper is still being applied.
     */
    private val changeLock = Any()

    // ---------------------------------------------------------------------
    // START AUTOMATION
    // ---------------------------------------------------------------------

    fun start(
        context: Context,
        images: List<Uri>,
        interval: String,
        order: String,
        target: String,
        customValue: String,
        customUnit: String
    ) {
        if (images.isEmpty()) return

        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

        /*
         * Cancel any old alarm before starting.
         *
         * This is important when the user changes settings and presses Start
         * again. There should never be an old repeating alarm left behind.
         */
        cancelAlarm(context)

        prefs.edit()
            .putString(
                KEY_IMAGES,
                images.joinToString("\n") {
                    it.toString()
                }
            )
            .putString(
                KEY_INTERVAL,
                interval
            )
            .putString(
                KEY_ORDER,
                order
            )
            .putString(
                KEY_TARGET,
                target
            )
            .putString(
                KEY_CUSTOM_VALUE,
                customValue
            )
            .putString(
                KEY_CUSTOM_UNIT,
                customUnit
            )
            .putInt(
                KEY_INDEX,
                0
            )
            .putInt(
                KEY_LAST_INDEX,
                -1
            )
            .putBoolean(
                KEY_RUNNING,
                true
            )
            .apply()

        /*
         * Apply the first wallpaper immediately.
         */
        changeWallpaper(context)

        /*
         * Schedule exactly one future alarm.
         *
         * We deliberately do NOT use setInexactRepeating().
         * The receiver schedules the next one after each completed change.
         */
        scheduleNext(
            context = context,
            interval = interval,
            customValue = customValue,
            customUnit = customUnit
        )
    }

    // ---------------------------------------------------------------------
    // STOP AUTOMATION
    // ---------------------------------------------------------------------

    fun stop(context: Context) {

        cancelAlarm(context)

        context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .edit()
            .putBoolean(
                KEY_RUNNING,
                false
            )
            .putLong(
                KEY_NEXT_CHANGE_TIME,
                0L
            )
            .apply()
    }

    // ---------------------------------------------------------------------
    // CHANGE WALLPAPER
    // ---------------------------------------------------------------------

    fun changeWallpaper(context: Context): Boolean {

        /*
         * Prevent overlapping wallpaper operations.
         *
         * synchronized is intentionally kept around the complete operation
         * because WallpaperManager.setStream() is synchronous.
         */
        synchronized(changeLock) {

            val prefs =
                context.getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )

            /*
             * Never change a wallpaper after the user has stopped automation.
             */
            if (
                !prefs.getBoolean(
                    KEY_RUNNING,
                    false
                )
            ) {
                return false
            }

            val imageString =
                prefs.getString(
                    KEY_IMAGES,
                    null
                ) ?: return false

            val images =
                imageString
                    .split("\n")
                    .filter {
                        it.isNotBlank()
                    }

            if (images.isEmpty()) return false

            val order =
                prefs.getString(
                    KEY_ORDER,
                    "Random"
                ) ?: "Random"

            val target =
                (
                        prefs.getString(
                            KEY_TARGET,
                            "Both"
                        ) ?: "Both"
                        )
                    .trim()
                    .lowercase()

            val currentIndex =
                prefs.getInt(
                    KEY_INDEX,
                    0
                )

            val lastIndex =
                prefs.getInt(
                    KEY_LAST_INDEX,
                    -1
                )

            // -------------------------------------------------------------
            // SELECT NEXT WALLPAPER
            // -------------------------------------------------------------

            val selectedIndex =
                if (order == "Random") {

                    if (images.size == 1) {

                        0

                    } else {

                        var randomIndex =
                            Random.nextInt(
                                images.size
                            )

                        while (
                            randomIndex == lastIndex
                        ) {
                            randomIndex =
                                Random.nextInt(
                                    images.size
                                )
                        }

                        randomIndex
                    }

                } else {

                    currentIndex
                        .coerceAtLeast(0)
                        .mod(images.size)
                }

            val uriString =
                images.getOrNull(
                    selectedIndex
                ) ?: return false

            val uri =
                Uri.parse(
                    uriString
                )

            // -------------------------------------------------------------
            // APPLY WALLPAPER
            // -------------------------------------------------------------

            var appliedSuccessfully = false

            try {

                /*
                 * We intentionally stream the original URI directly to
                 * WallpaperManager.
                 *
                 * We do NOT decode the full wallpaper into a Bitmap here.
                 * That keeps Wallvance from creating a second large copy of
                 * every wallpaper in application memory.
                 */
                context.contentResolver
                    .openInputStream(uri)
                    ?.use { inputStream ->

                        val wallpaperManager =
                            WallpaperManager.getInstance(
                                context
                            )

                        val flags =
                            when (target) {

                                "home",
                                "home screen" ->
                                    WallpaperManager.FLAG_SYSTEM

                                "lock",
                                "lock screen" ->
                                    WallpaperManager.FLAG_LOCK

                                else ->
                                    WallpaperManager.FLAG_SYSTEM or
                                            WallpaperManager.FLAG_LOCK
                            }

                        wallpaperManager.setStream(
                            inputStream,
                            null,
                            true,
                            flags
                        )

                        appliedSuccessfully = true
                    }

            } catch (_: Exception) {

                /*
                 * A missing/revoked URI or a WallpaperManager failure should
                 * not crash the application.
                 *
                 * The current index is intentionally NOT advanced when the
                 * wallpaper was not applied successfully.
                 */
                return false
            }

            if (!appliedSuccessfully) {
                return false
            }

            // -------------------------------------------------------------
            // UPDATE STATE ONLY AFTER SUCCESS
            // -------------------------------------------------------------

            val nextIndex =
                (selectedIndex + 1)
                    .mod(images.size)

            val now =
                System.currentTimeMillis()

            prefs.edit()
                .putInt(
                    KEY_INDEX,
                    nextIndex
                )
                .putInt(
                    KEY_LAST_INDEX,
                    selectedIndex
                )
                .putLong(
                    KEY_LAST_CHANGED_TIME,
                    now
                )
                .putString(
                    KEY_LAST_CHANGED_URI,
                    uri.toString()
                )
                .apply()

            return true
        }
    }

    // ---------------------------------------------------------------------
    // CALCULATE INTERVAL
    // ---------------------------------------------------------------------

    private fun getIntervalMillis(
        interval: String,
        customValue: String,
        customUnit: String
    ): Long {

        val value =
            customValue
                .toLongOrNull()
                ?.coerceAtLeast(1L)
                ?: 30L

        return when (interval) {

            "15 minutes" ->
                TimeUnit.MINUTES.toMillis(
                    15L
                )

            "30 minutes" ->
                TimeUnit.MINUTES.toMillis(
                    30L
                )

            "1 hour" ->
                TimeUnit.HOURS.toMillis(
                    1L
                )

            "6 hours" ->
                TimeUnit.HOURS.toMillis(
                    6L
                )

            "12 hours" ->
                TimeUnit.HOURS.toMillis(
                    12L
                )

            "24 hours" ->
                TimeUnit.HOURS.toMillis(
                    24L
                )

            "Custom" ->
                when (customUnit) {

                    "Minutes" ->
                        TimeUnit.MINUTES.toMillis(
                            value.coerceAtLeast(5L)
                        )

                    "Hours" ->
                        TimeUnit.HOURS.toMillis(
                            value
                        )

                    "Days" ->
                        TimeUnit.DAYS.toMillis(
                            value
                        )

                    else ->
                        TimeUnit.MINUTES.toMillis(
                            value
                        )
                }

            else ->
                TimeUnit.MINUTES.toMillis(
                    30L
                )
        }
    }

    // ---------------------------------------------------------------------
    // SCHEDULE NEXT CHANGE
    // ---------------------------------------------------------------------

    fun scheduleNext(
        context: Context,
        interval: String,
        customValue: String,
        customUnit: String
    ) {

        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

        /*
         * Never create an alarm if automation is currently stopped.
         */
        if (
            !prefs.getBoolean(
                KEY_RUNNING,
                false
            )
        ) {
            cancelAlarm(context)
            return
        }

        val intervalMillis =
            getIntervalMillis(
                interval = interval,
                customValue = customValue,
                customUnit = customUnit
            )

        val now =
            System.currentTimeMillis()

        val nextChangeTime =
            now + intervalMillis

        /*
         * Cancel first so there is exactly one Wallvance alarm.
         */
        cancelAlarm(context)

        prefs.edit()
            .putLong(
                KEY_NEXT_CHANGE_TIME,
                nextChangeTime
            )
            .apply()

        val pendingIntent =
            createPendingIntent(
                context
            )

        val alarmManager =
            context.getSystemService(
                Context.ALARM_SERVICE
            ) as AlarmManager

        /*
         * One-shot alarm instead of repeating alarm.
         *
         * WallpaperAlarmReceiver schedules the next one after a successful
         * delivery. This prevents a repeating alarm from accumulating or
         * racing with wallpaper processing.
         */
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                !alarmManager.canScheduleExactAlarms()
            ) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextChangeTime,
                    pendingIntent
                )
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextChangeTime,
                    pendingIntent
                )
            }
        } catch (_: SecurityException) {
            // If exact-alarm access is unavailable, fall back safely.
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextChangeTime,
                pendingIntent
            )
        }
    }

    // ---------------------------------------------------------------------
    // RESUME AFTER PHONE REBOOT
    // ---------------------------------------------------------------------

    fun resumeAfterBoot(
        context: Context
    ) {

        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

        /*
         * Do not restart an automation that the user stopped.
         */
        if (
            !prefs.getBoolean(
                KEY_RUNNING,
                false
            )
        ) {
            return
        }

        val imagesString =
            prefs.getString(
                KEY_IMAGES,
                null
            ) ?: return

        if (
            imagesString.isBlank()
        ) {
            return
        }

        val interval =
            prefs.getString(
                KEY_INTERVAL,
                "30 minutes"
            ) ?: "30 minutes"

        val customValue =
            prefs.getString(
                KEY_CUSTOM_VALUE,
                "30"
            ) ?: "30"

        val customUnit =
            prefs.getString(
                KEY_CUSTOM_UNIT,
                "Minutes"
            ) ?: "Minutes"

        /*
         * Android removes alarms during reboot, so create exactly one new
         * alarm after boot.
         *
         * We intentionally do not immediately change the wallpaper here.
         */
        scheduleNext(
            context = context,
            interval = interval,
            customValue = customValue,
            customUnit = customUnit
        )
    }

    // ---------------------------------------------------------------------
    // CANCEL ALARM
    // ---------------------------------------------------------------------

    private fun cancelAlarm(
        context: Context
    ) {

        val alarmManager =
            context.getSystemService(
                Context.ALARM_SERVICE
            ) as AlarmManager

        alarmManager.cancel(
            createPendingIntent(
                context
            )
        )
    }

    // ---------------------------------------------------------------------
    // PENDING INTENT
    // ---------------------------------------------------------------------

    private fun createPendingIntent(
        context: Context
    ): PendingIntent {

        val intent =
            Intent(
                context,
                WallpaperAlarmReceiver::class.java
            )

        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )
    }
}
