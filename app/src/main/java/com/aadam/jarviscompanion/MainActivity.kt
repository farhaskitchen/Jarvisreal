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
            text = "Jarvis Companion\n\nThis app streams live location to Jarvis running in Termux, on http://127.0.0.1:8765/location\n\nTap Start to grant permissions and begin."
            textSize = 16f
        }
        val startButton = Button(this).apply {
            text = "Start Location Streaming"
            setOnClickListener { requestPermissionsAndStart() }
        }
        layout.addView(statusText)
        layout.addView(startButton)
        setContentView(layout)
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
    }
}
