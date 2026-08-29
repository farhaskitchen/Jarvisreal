package com.aadam.jarviscompanion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val permissionsNeeded = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private val requestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registerPhoneAccount()
        startCallTriggerServer()

        // Simple programmatic UI -- no XML layout needed for this
        // minimal service-launcher screen.
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        val statusText = TextView(this).apply {
            textSize = 16f
        }
        val startButton = Button(this).apply {
            text = "Start Location Streaming"
            setOnClickListener { requestPermissionsAndStart() }
        }
        val stopButton = Button(this).apply {
            text = "Stop Location Streaming"
            setOnClickListener { stopLocationService() }
        }
        val enableCallAccountButton = Button(this).apply {
            text = "Enable Jarvis Calling Account"
            setOnClickListener { openCallAccountSettings() }
        }
        layout.addView(statusText)
        layout.addView(startButton)
        layout.addView(stopButton)
        layout.addView(enableCallAccountButton)
        setContentView(layout)

        this.statusText = statusText
        refreshStatus()
    }

    private lateinit var statusText: TextView

    private var registrationResult: String = "not attempted yet"
    private var callServerStatus: String = "checking..."

    private fun refreshStatus() {
        val locationStatus = if (LocationStreamService.isRunning) "RUNNING" else "STOPPED"
        val accountEnabled = isJarvisAccountEnabled()
        statusText.text = "Jarvis Companion\n\n" +
            "Location streaming: $locationStatus\n" +
            "http://127.0.0.1:8765/location\n\n" +
            "Call trigger server: $callServerStatus\n" +
            "http://127.0.0.1:8766/trigger_call\n\n" +
            "Phone account registration: $registrationResult\n" +
            "Phone account ENABLED: $accountEnabled\n\n" +
            (if (!accountEnabled)
                "Not enabled yet -- tap 'Enable Jarvis Calling Account'. " +
                "If that opens a screen only showing your SIM carrier " +
                "(not 'Jarvis'), your phone's calling-accounts screen is " +
                "in a different place -- try Settings > Apps > Default " +
                "apps > look for 'Calling accounts' or 'Other calling apps', " +
                "or search Settings for 'calling accounts'."
            else "")
        checkCallServerLive()
    }

    private fun checkCallServerLive() {
        // Real liveness check instead of assuming the server is running --
        // a foreground service can still get killed by the OS, so this
        // actually tries to connect rather than trusting a static label.
        Thread {
            val reachable = try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", CallTriggerServer.PORT), 500)
                    true
                }
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                callServerStatus = if (reachable) "RUNNING (port ${CallTriggerServer.PORT} reachable)"
                    else "NOT REACHABLE -- try Force Stop then reopen the app"
                // Update just that one line without re-running the whole
                // status/account check again to avoid a refresh loop.
                statusText.text = statusText.text.toString().replace(
                    Regex("Call trigger server:.*"),
                    "Call trigger server: $callServerStatus"
                )
            }
        }.start()
    }

    private fun isJarvisAccountEnabled(): Boolean {
        return try {
            val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
            val handle = CallTriggerServer.getPhoneAccountHandle(this)
            // enablePhoneAccount check isn't directly queryable pre-API33
            // in a simple boolean, but getPhoneAccount returns null if
            // it's not registered/visible, and isEnabled() reflects the
            // user's toggle state in system settings once it exists.
            val account = telecomManager.getPhoneAccount(handle)
            account?.isEnabled == true
        } catch (e: Exception) {
            false
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun stopLocationService() {
        val intent = Intent(this, LocationStreamService::class.java).apply {
            action = LocationStreamService.ACTION_STOP
        }
        startService(intent)
        // Small delay so the service has time to process ACTION_STOP and
        // update isRunning before we re-read it for the status label.
        statusText.postDelayed({ refreshStatus() }, 300)
    }

    private fun requestPermissionsAndStart() {
        val missing = permissionsNeeded.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), requestCode)
        } else {
            startLocationService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                startLocationService()
            }
            // NOTE: ACCESS_BACKGROUND_LOCATION must be requested separately
            // on Android 10+ (system requires it as its own follow-up
            // prompt after foreground location is granted). If background
            // streaming stops working when the screen is off, that
            // permission needs to be granted manually in
            // Settings > Apps > Jarvis Companion > Permissions > Location > Allow all the time.
        }
    }

    private fun startLocationService() {
        val intent = Intent(this, LocationStreamService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        statusText.postDelayed({ refreshStatus() }, 300)
    }

    private fun registerPhoneAccount() {
        registrationResult = try {
            val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
            val handle = CallTriggerServer.getPhoneAccountHandle(this)
            // NOTE: previously used CAPABILITY_SELF_MANAGED, which is a
            // different Telecom category that does NOT appear in the
            // system's "Calling accounts" settings screen. Confirmed by
            // checking Phony's (working) source: it uses
            // CAPABILITY_CALL_PROVIDER + addSupportedUriScheme(SCHEME_TEL)
            // instead, which is what actually shows up there.
            val account = PhoneAccount.builder(handle, "Jarvis")
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
                .build()
            telecomManager.registerPhoneAccount(account)
            "SUCCESS (registered with Telecom)"
        } catch (e: Exception) {
            "FAILED: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun openCallAccountSettings() {
        try {
            val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
            startActivity(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS))
        } catch (e: Exception) {
            // Fallback: open general app settings if the Telecom settings
            // screen isn't available on this OEM's Android build.
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
    }

    private fun startCallTriggerServer() {
        val intent = Intent(this, CallTriggerServer::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
