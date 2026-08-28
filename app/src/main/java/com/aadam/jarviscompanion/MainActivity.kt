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

    private fun refreshStatus() {
        val locationStatus = if (LocationStreamService.isRunning) "RUNNING" else "STOPPED"
        statusText.text = "Jarvis Companion\n\n" +
            "Location streaming: $locationStatus\n" +
            "http://127.0.0.1:8765/location\n\n" +
            "Call trigger server: running\n" +
            "http://127.0.0.1:8766/trigger_call\n\n" +
            "If fake calls don't ring, tap 'Enable Jarvis Calling Account' " +
            "and turn it on in the Phone app settings that open -- Android " +
            "requires this to be enabled manually once."
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
        try {
            val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
            val handle = CallTriggerServer.getPhoneAccountHandle(this)
            val account = PhoneAccount.builder(handle, "Jarvis")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .build()
            telecomManager.registerPhoneAccount(account)
        } catch (e: Exception) {
            // Registration can fail on some OEM Telecom implementations --
            // the "Enable Jarvis Calling Account" button lets the user
            // check/fix this manually via system settings regardless.
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
            startService(intent)
        } else {
            startService(intent)
        }
    }
}
