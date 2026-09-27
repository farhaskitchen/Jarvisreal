package com.aadam.jarviscompanion

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.os.Process
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Reads call log, SMS, and app usage data from the OS content providers
 * and UsageStatsManager. Kept separate from DeviceInfoService's existing
 * battery/network/storage code since this touches meaningfully more
 * sensitive data -- each function checks its own permission explicitly
 * and returns an empty/error result rather than crashing if the
 * corresponding permission hasn't been granted, since these can be
 * granted independently of each other and of the rest of the app's
 * permission set.
 */
object DeviceDataProvider {

    fun callLog(context: Context, limit: Int = 20): JSONArray {
        val arr = JSONArray()
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return arr
        }
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, null, null,
                "${CallLog.Calls.DATE} DESC LIMIT $limit"
            )
            cursor?.let {
                val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = it.getColumnIndex(CallLog.Calls.DURATION)
                while (it.moveToNext()) {
                    val obj = JSONObject()
                    obj.put("number", it.getString(numberIdx) ?: "")
                    obj.put("name", it.getString(nameIdx) ?: "")
                    obj.put("type", callTypeToString(it.getInt(typeIdx)))
                    obj.put("timestamp", it.getLong(dateIdx))
                    obj.put("duration_seconds", it.getInt(durationIdx))
                    arr.put(obj)
                }
            }
        } catch (e: Exception) {
            // Return whatever was gathered before the failure, rather than
            // nothing at all.
        } finally {
            cursor?.close()
        }
        return arr
    }

    private fun callTypeToString(type: Int): String {
        return when (type) {
            CallLog.Calls.INCOMING_TYPE -> "incoming"
            CallLog.Calls.OUTGOING_TYPE -> "outgoing"
            CallLog.Calls.MISSED_TYPE -> "missed"
            CallLog.Calls.REJECTED_TYPE -> "rejected"
            CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
            CallLog.Calls.BLOCKED_TYPE -> "blocked"
            else -> "unknown"
        }
    }

    fun smsMessages(context: Context, limit: Int = 20): JSONArray {
        val arr = JSONArray()
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return arr
        }
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI, projection, null, null,
                "${Telephony.Sms.DATE} DESC LIMIT $limit"
            )
            cursor?.let {
                val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
                val typeIdx = it.getColumnIndex(Telephony.Sms.TYPE)
                while (it.moveToNext()) {
                    val obj = JSONObject()
                    obj.put("address", it.getString(addressIdx) ?: "")
                    obj.put("body", it.getString(bodyIdx) ?: "")
                    obj.put("timestamp", it.getLong(dateIdx))
                    obj.put(
                        "direction",
                        if (it.getInt(typeIdx) == Telephony.Sms.MESSAGE_TYPE_INBOX) "received" else "sent"
                    )
                    arr.put(obj)
                }
            }
        } catch (e: Exception) {
            // Return whatever was gathered before the failure.
        } finally {
            cursor?.close()
        }
        return arr
    }

    /**
     * PACKAGE_USAGE_STATS cannot be requested via the normal runtime
     * permission dialog at all -- Android requires the user to grant it
     * manually via a dedicated settings screen (same category as
     * notification access). hasUsageAccess() checks whether that's
     * actually been done via AppOpsManager, since checkSelfPermission()
     * doesn't reliably reflect this particular permission's state.
     */
    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    fun appUsageStats(context: Context, hoursBack: Int = 24): JSONArray {
        val arr = JSONArray()
        if (!hasUsageAccess(context)) return arr

        try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = Calendar.getInstance().timeInMillis
            val start = end - hoursBack * 60 * 60 * 1000L

            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, start, end
            )
            // Multiple INTERVAL_DAILY buckets can exist within the window
            // (e.g. spanning midnight) -- sum foreground time per package
            // rather than reporting each bucket as a separate entry, so
            // "how long have I used X today" gets one sensible number.
            val totals = mutableMapOf<String, Long>()
            val lastUsed = mutableMapOf<String, Long>()
            for (stat in stats) {
                if (stat.totalTimeInForeground <= 0) continue
                totals[stat.packageName] = (totals[stat.packageName] ?: 0L) + stat.totalTimeInForeground
                val prevLast = lastUsed[stat.packageName] ?: 0L
                if (stat.lastTimeUsed > prevLast) lastUsed[stat.packageName] = stat.lastTimeUsed
            }

            totals.entries.sortedByDescending { it.value }.forEach { (pkg, totalMs) ->
                val obj = JSONObject()
                obj.put("package", pkg)
                obj.put("foreground_minutes", totalMs / 60000)
                obj.put("last_used_timestamp", lastUsed[pkg] ?: 0L)
                arr.put(obj)
            }
        } catch (e: Exception) {
            // Return whatever was gathered before the failure.
        }
        return arr
    }
}
