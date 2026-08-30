package com.aadam.jarviscompanion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Observes REAL cellular call state via TelephonyManager. This is
 * read-only by design -- Android does not allow a third-party app to
 * answer or reject a genuine incoming call without either being set as
 * the default dialer app (a large, invasive change) or resorting to
 * fragile root-level hacks that vary by OEM and can break on any Android
 * update. So this side of CallStateManager only ever reports state; all
 * accept/reject control stays limited to our own fake calls, where we
 * fully own the ConnectionService already.
 *
 * PhoneStateListener is deprecated as of API 31 in favor of
 * TelephonyCallback, so this branches on SDK version to use the current
 * API where available while still working on our minSdk 26 target.
 */
object RealCallStateWatcher {

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Can't observe without the permission -- caller should have
            // requested it already (see MainActivity's permissionsNeeded),
            // this is just a defensive no-op rather than a crash if not.
            return
        }
        started = true
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleState(state)
                }
            }
            telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Suppress("DEPRECATION")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleState(state, phoneNumber)
                }
            }
            @Suppress("DEPRECATION")
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun handleState(state: Int, phoneNumber: String? = null) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING ->
                CallStateManager.setRealCallState(CallStateManager.State.RINGING, phoneNumber ?: "")
            TelephonyManager.CALL_STATE_OFFHOOK ->
                // OFFHOOK covers both "actively on a call" and "dialing out" --
                // TelephonyManager doesn't distinguish further via this API,
                // so this reports as ACTIVE rather than trying to guess.
                CallStateManager.setRealCallState(CallStateManager.State.ACTIVE)
            TelephonyManager.CALL_STATE_IDLE ->
                CallStateManager.setRealCallState(CallStateManager.State.IDLE)
        }
    }
}
