package com.aadam.jarviscompanion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Starts all three foreground services automatically when the device
 * finishes booting, so the phone-info dashboard and call-trigger pipeline
 * come up without the user having to open the app manually.
 *
 * RECEIVE_BOOT_COMPLETED is the standard Android mechanism for this --
 * no root required for the boot-start itself. What DOES vary by OEM (and
 * is the actual "make sure it never goes off due to battery" concern) is
 * whether Samsung's separate battery-management system (distinct from
 * stock Android's Doze/App Standby) later kills these services hours
 * after boot. MainActivity has a button that requests the standard
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS exemption, which stock Android
 * respects fully -- but Samsung devices also have their own additional
 * "Put unused apps to sleep" / "Sleeping apps" list under
 * Settings > Battery > Background usage limits, which is a manual,
 * OEM-specific toggle no permission or root call can grant automatically.
 * See MainActivity's status text for the exact settings path.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        startForegroundServiceCompat(context, LocationStreamService::class.java)
        startForegroundServiceCompat(context, CallTriggerServer::class.java)
        startForegroundServiceCompat(context, DeviceInfoService::class.java)
    }

    private fun startForegroundServiceCompat(context: Context, cls: Class<*>) {
        val serviceIntent = Intent(context, cls)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            // Some OEM builds restrict background service starts from a
            // BOOT_COMPLETED receiver even with the exemption granted --
            // if this throws, the user will need to open the app once
            // after this specific OS update to re-arm auto-start.
        }
    }
}
