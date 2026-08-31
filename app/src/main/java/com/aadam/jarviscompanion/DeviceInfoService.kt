package com.aadam.jarviscompanion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Foreground service that exposes a snapshot of device state (battery,
 * network, RAM/storage, active notifications) over a local HTTP endpoint,
 * mirroring the pattern LocationStreamService already uses. Intended for
 * a projector/dashboard GUI to poll periodically -- same
 * "local loopback JSON endpoint" design, just a different set of fields.
 */
class DeviceInfoService : Service() {

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    companion object {
        const val CHANNEL_ID = "jarvis_device_info_channel"
        const val NOTIF_ID = 3
        const val HTTP_PORT = 8767
        const val ACTION_STOP = "com.aadam.jarviscompanion.ACTION_STOP_DEVICE_INFO"

        @Volatile
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isRunning) {
            isRunning = true
            startHttpServer()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Jarvis Device Info", NotificationManager.IMPORTANCE_LOW
            )
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis Companion")
            .setContentText("Broadcasting device info for dashboard")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification)
    }

    private fun startHttpServer() {
        serverThread = thread(start = true) {
            try {
                // Bind to 0.0.0.0 so devices elsewhere on the LAN (e.g.
                // the projector GUI) can reach this using the phone's WiFi
                // IP, not just 127.0.0.1 from the phone itself. Read-only
                // endpoint, so opening it to the network is low-risk
                // compared to CallTriggerServer (which stays loopback-only).
                serverSocket = ServerSocket(HTTP_PORT, 50, java.net.InetAddress.getByName("0.0.0.0"))
                while (!Thread.currentThread().isInterrupted) {
                    val client = serverSocket?.accept() ?: break
                    handleClient(client)
                }
            } catch (e: Exception) {
                // Server stopped or port unavailable.
            }
        }
    }

    private fun handleClient(client: Socket) {
        thread(start = true) {
            try {
                client.getInputStream().bufferedReader().readLine()
                val body = buildDeviceInfoJson().toString()
                val writer = OutputStreamWriter(client.getOutputStream())
                writer.write("HTTP/1.1 200 OK\r\n")
                writer.write("Content-Type: application/json\r\n")
                writer.write("Content-Length: ${body.toByteArray().size}\r\n")
                // CORS: without this header, a browser-based dashboard
                // (fetch() from the projector GUI) will block reading the
                // response even though the request itself succeeds -- the
                // server has to opt in explicitly. Wide open (*) is fine
                // here since this is a read-only endpoint on a local LAN.
                writer.write("Access-Control-Allow-Origin: *\r\n")
                writer.write("Connection: close\r\n\r\n")
                writer.write(body)
                writer.flush()
                writer.close()
            } catch (e: Exception) {
                // Ignore individual connection failures.
            } finally {
                client.close()
            }
        }
    }

    private fun buildDeviceInfoJson(): JSONObject {
        val root = JSONObject()
        root.put("battery", batteryInfo())
        root.put("network", networkInfo())
        root.put("memory", memoryInfo())
        root.put("storage", storageInfo())
        root.put("notifications", notificationsInfo())
        root.put("call_state", callStateInfo())
        return root
    }

    private fun batteryInfo(): JSONObject {
        val obj = JSONObject()
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = registerReceiver(null, filter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val chargeSource = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> "none"
            }
            obj.put("percent", pct)
            obj.put("charging", isCharging)
            obj.put("source", chargeSource)
        } catch (e: Exception) {
            obj.put("error", e.message)
        }
        return obj
    }

    private fun networkInfo(): JSONObject {
        val obj = JSONObject()
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            val isConnected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            obj.put("connected", isConnected)
            obj.put("type", if (isWifi) "wifi" else if (isCellular) "cellular" else "none")

            if (isWifi) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo
                // SSID often reads as "<unknown ssid>" without location
                // permission granted -- expected, not a bug, since Android
                // treats WiFi network names as location-adjacent data.
                obj.put("ssid", wifiInfo.ssid?.trim('"') ?: "unknown")
                obj.put("signal_dbm", wifiInfo.rssi)
                obj.put(
                    "signal_level",
                    WifiManager.calculateSignalLevel(wifiInfo.rssi, 5)
                )
            }
        } catch (e: Exception) {
            obj.put("error", e.message)
        }
        return obj
    }

    private fun memoryInfo(): JSONObject {
        val obj = JSONObject()
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            obj.put("total_bytes", memInfo.totalMem)
            obj.put("available_bytes", memInfo.availMem)
            obj.put("low_memory", memInfo.lowMemory)
        } catch (e: Exception) {
            obj.put("error", e.message)
        }
        return obj
    }

    private fun storageInfo(): JSONObject {
        val obj = JSONObject()
        try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val available = stat.availableBlocksLong * stat.blockSizeLong
            obj.put("total_bytes", total)
            obj.put("available_bytes", available)
        } catch (e: Exception) {
            obj.put("error", e.message)
        }
        return obj
    }

    private fun notificationsInfo(): JSONArray {
        val arr = JSONArray()
        // Populated by JarvisNotificationListenerService, which Android
        // requires to be its own separate service class -- see that file
        // for why, and MainActivity for the manual settings-grant step
        // this needs (same "must be toggled by hand" pattern as the
        // Calling Account and battery-optimization exemption).
        val current = JarvisNotificationListenerService.currentNotifications
        for (n in current) {
            val obj = JSONObject()
            obj.put("app", n.packageName)
            obj.put("title", n.title)
            obj.put("text", n.text)
            obj.put("time", n.postTime)
            arr.put(obj)
        }
        return arr
    }

    private fun callStateInfo(): JSONObject {
        // Reads CallStateManager directly (same object CallTriggerServer's
        // accept/reject endpoints and JarvisConnection/RealCallStateWatcher
        // write into) -- built explicitly rather than via JSONObject(Map)
        // for the same reason noted where this was first written in
        // CallTriggerServer: nested Map-value handling in that constructor
        // isn't reliably documented, safer to be explicit.
        val snap = CallStateManager.snapshot()
        val root = JSONObject()
        root.put("overall_state", snap["overall_state"])
        val fake = snap["fake_call"] as? Map<*, *>
        root.put("fake_call", JSONObject().apply {
            put("state", fake?.get("state"))
            put("caller_name", fake?.get("caller_name"))
        })
        val real = snap["real_call"] as? Map<*, *>
        root.put("real_call", JSONObject().apply {
            put("state", real?.get("state"))
            put("number", real?.get("number"))
        })
        return root
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serverThread?.interrupt()
        try { serverSocket?.close() } catch (e: Exception) {}
    }
}
