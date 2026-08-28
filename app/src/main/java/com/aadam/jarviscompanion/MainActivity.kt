package com.aadam.jarviscompanion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
        layout.addView(statusText)
        layout.addView(startButton)
        layout.addView(stopButton)
        setContentView(layout)

        this.statusText = statusText
        refreshStatus()
    }

    private lateinit var statusText: TextView

    private fun refreshStatus() {
        statusText.text = if (LocationStreamService.isRunning) {
            "Jarvis Companion\n\nStatus: RUNNING\nServing live location at http://127.0.0.1:8765/location"
        } else {
            "Jarvis Companion\n\nStatus: STOPPED\n\nTap Start to grant permissions and begin streaming location to Jarvis."
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
}
