package com.aadam.jarviscompanion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
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

    private lateinit var locationStatusRow: StatusRow
    private lateinit var callServerStatusRow: StatusRow
    private lateinit var deviceInfoStatusRow: StatusRow
    private lateinit var phoneAccountStatusRow: StatusRow
    private lateinit var notifAccessStatusRow: StatusRow
    private lateinit var batteryExemptStatusRow: StatusRow

    private var registrationResult: String = "not attempted yet"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registerPhoneAccount()
        startCallTriggerServer()

        setContentView(buildLayout())
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------- UI construction ----------

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun buildLayout(): View {
        val root = ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.jarvis_bg))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(32))
        }

        container.addView(header())
        container.addView(spacer(24))

        container.addView(sectionCard(
            title = "Services",
            rows = listOf(
                statusRowView("Location streaming", "http://127.0.0.1:8765/location").also { locationStatusRow = it },
                statusRowView("Call trigger server", "http://127.0.0.1:8766/trigger_call").also { callServerStatusRow = it },
                statusRowView("Device info dashboard", "http://127.0.0.1:8767/device_info").also { deviceInfoStatusRow = it }
            ),
            buttons = listOf(
                "Start All Services" to { requestPermissionsAndStart() },
                "Stop Location Streaming" to { stopLocationService() }
            )
        ))
        container.addView(spacer(16))

        container.addView(sectionCard(
            title = "Calling account",
            rows = listOf(
                statusRowView("Phone account", "").also { phoneAccountStatusRow = it }
            ),
            buttons = listOf(
                "Enable Jarvis Calling Account" to { openCallAccountSettings() }
            )
        ))
        container.addView(spacer(16))

        container.addView(sectionCard(
            title = "Notifications",
            rows = listOf(
                statusRowView("Notification access", "").also { notifAccessStatusRow = it }
            ),
            buttons = listOf(
                "Grant Notification Access" to { openNotificationAccessSettings() }
            )
        ))
        container.addView(spacer(16))

        container.addView(sectionCard(
            title = "Reliability",
            rows = listOf(
                statusRowView("Battery optimization exempt", "").also { batteryExemptStatusRow = it }
            ),
            buttons = listOf(
                "Disable Battery Optimization" to { requestBatteryExemption() }
            ),
            footnote = "Samsung phones also have their own separate " +
                "\"Sleeping apps\" list under Settings > Battery > " +
                "Background usage limits. Add Jarvis Companion to the " +
                "\"Never sleeping apps\" list there too -- the standard " +
                "Android exemption above doesn't cover Samsung's own " +
                "battery manager."
        ))

        root.addView(container)
        return root
    }

    private fun header(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Jarvis Companion"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.jarvis_text_primary))
                textSize = 26f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Device bridge for Jarvis"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.jarvis_text_secondary))
                textSize = 14f
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun spacer(heightDp: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
        }
    }

    data class StatusRow(val dot: View, val label: TextView, val detail: TextView)

    private fun statusRowView(label: String, url: String): StatusRow {
        // Built here but attached inside sectionCard(); returned so the
        // caller can update dot color / detail text later.
        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(10)
            }
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.dot_status)
        }
        val labelView = TextView(this).apply {
            text = label
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.jarvis_text_primary))
            textSize = 15f
        }
        val detailView = TextView(this).apply {
            text = url
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.jarvis_text_secondary))
            textSize = 12f
        }
        return StatusRow(dot, labelView, detailView)
    }

    private fun sectionCard(
        title: String,
        rows: List<StatusRow>,
        buttons: List<Pair<String, () -> Unit>>,
        footnote: String? = null
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        card.addView(TextView(this).apply {
            text = title
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.jarvis_accent))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.05f
        })
        card.addView(spacer(12))

        for ((i, row) in rows.withIndex()) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            rowLayout.addView(row.dot)
            val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textCol.addView(row.label)
            if (row.detail.text.isNotEmpty()) textCol.addView(row.detail)
            rowLayout.addView(textCol)
            card.addView(rowLayout)
            if (i != rows.lastIndex) card.addView(spacer(10))
        }

        if (buttons.isNotEmpty()) {
            card.addView(spacer(14))
            for ((i, btn) in buttons.withIndex()) {
                val (text, action) = btn
                card.addView(Button(this).apply {
                    this.text = text
                    setTextColor(if (i == 0) Color.WHITE else ContextCompat.getColor(this@MainActivity, R.color.jarvis_text_primary))
                    background = ContextCompat.getDrawable(
                        this@MainActivity,
                        if (i == 0) R.drawable.bg_button else R.drawable.bg_button_outline
                    )
                    setOnClickListener { action() }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
                    ).apply { if (i != 0) topMargin = dp(8) }
                })
            }
        }

        if (footnote != null) {
            card.addView(spacer(10))
            card.addView(TextView(this).apply {
                text = footnote
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.jarvis_text_secondary))
                textSize = 12f
            })
        }

        return card
    }

    // ---------- Status refresh ----------

    private fun refreshStatus() {
        setRow(locationStatusRow, LocationStreamService.isRunning)
        setRow(deviceInfoStatusRow, DeviceInfoService.isRunning)
        setRow(notifAccessStatusRow, JarvisNotificationListenerService.isListening,
            onLabel = "Granted", offLabel = "Not granted -- tap below")

        val accountEnabled = isJarvisAccountEnabled()
        setRow(phoneAccountStatusRow, accountEnabled,
            onLabel = "Enabled", offLabel = "Registered but not enabled -- tap below")

        val exempt = isIgnoringBatteryOptimizations()
        setRow(batteryExemptStatusRow, exempt,
            onLabel = "Exempt", offLabel = "Not exempt -- tap below")

        checkCallServerLive()
    }

    private fun setRow(row: StatusRow, isOn: Boolean, onLabel: String = "Running", offLabel: String = "Stopped") {
        val color = if (isOn) R.color.jarvis_green else R.color.jarvis_red
        (row.dot.background as? GradientDrawable)?.setColor(ContextCompat.getColor(this, color))
        row.detail.text = if (isOn) onLabel else offLabel
        row.detail.visibility = View.VISIBLE
    }

    private fun checkCallServerLive() {
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
                setRow(callServerStatusRow, reachable,
                    onLabel = "Running (port ${CallTriggerServer.PORT})",
                    offLabel = "Not reachable -- try Force Stop then reopen")
            }
        }.start()
    }

    private fun isJarvisAccountEnabled(): Boolean {
        return try {
            val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
            val handle = CallTriggerServer.getPhoneAccountHandle(this)
            val account = telecomManager.getPhoneAccount(handle)
            account?.isEnabled == true
        } catch (e: Exception) {
            false
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        return try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (e: Exception) {
            false
        }
    }

    // ---------- Actions ----------

    private fun stopLocationService() {
        val intent = Intent(this, LocationStreamService::class.java).apply {
            action = LocationStreamService.ACTION_STOP
        }
        startService(intent)
        window.decorView.postDelayed({ refreshStatus() }, 300)
    }

    private fun requestPermissionsAndStart() {
        val missing = permissionsNeeded.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), requestCode)
        } else {
            startAllServices()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                startAllServices()
            }
            // NOTE: ACCESS_BACKGROUND_LOCATION must be requested separately
            // on Android 10+. If background streaming stops when the
            // screen is off, grant it manually in Settings > Apps >
            // Jarvis Companion > Permissions > Location > Allow all the time.
        }
    }

    private fun startAllServices() {
        startForegroundServiceCompat(Intent(this, LocationStreamService::class.java))
        startForegroundServiceCompat(Intent(this, DeviceInfoService::class.java))
        window.decorView.postDelayed({ refreshStatus() }, 300)
    }

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun registerPhoneAccount() {
        registrationResult = try {
            val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
            val handle = CallTriggerServer.getPhoneAccountHandle(this)
            val account = PhoneAccount.builder(handle, "Jarvis")
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
                .build()
            telecomManager.registerPhoneAccount(account)
            "SUCCESS"
        } catch (e: Exception) {
            "FAILED: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun openCallAccountSettings() {
        try {
            startActivity(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS))
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
    }

    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
    }

    private fun requestBatteryExemption() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
        }
    }

    private fun startCallTriggerServer() {
        startForegroundServiceCompat(Intent(this, CallTriggerServer::class.java))
    }
}
