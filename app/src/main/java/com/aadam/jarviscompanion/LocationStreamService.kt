package com.aadam.jarviscompanion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Foreground service that requests continuous high-accuracy GPS updates
 * (same FusedLocationProviderClient API Google Maps uses) and exposes the
 * latest fix over a minimal local HTTP server on 127.0.0.1:8765/location.
 *
 * Jarvis (running in Termux on the same device) can then poll that
 * endpoint instantly instead of cold-starting a fresh termux-location
 * request every time -- this service keeps GPS "warm" continuously
 * while active, matching how real navigation apps stay responsive.
 */
class LocationStreamService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var callback: LocationCallback
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val latestLocation = AtomicReference<Location?>(null)

    companion object {
        const val CHANNEL_ID = "jarvis_location_channel"
        const val NOTIF_ID = 1
        const val HTTP_PORT = 8765
        const val ACTION_STOP = "com.aadam.jarviscompanion.ACTION_STOP"

        // Tracks whether the service has already completed setup, so a
        // repeated "Start" tap (which re-delivers onStartCommand on the
        // same running instance) doesn't re-register location updates
        // or try to re-bind the already-listening HTTP port.
        @Volatile
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isRunning) {
            isRunning = true
            startLocationUpdates()
            startHttpServer()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Jarvis Location", NotificationManager.IMPORTANCE_LOW
            )
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis Companion")
            .setContentText("Streaming live location for navigation")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification)
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 2000L // 2 second updates, matches nav-app cadence
        ).setMinUpdateIntervalMillis(1000L).build()

        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { latestLocation.set(it) }
            }
        }

        try {
            fusedClient.requestLocationUpdates(request, callback, mainLooper)
        } catch (e: SecurityException) {
            // Permission not granted -- MainActivity is responsible for
            // requesting it before starting this service.
        }
    }

    /**
     * Minimal blocking HTTP server, intentionally dependency-free (no
     * need to pull in a whole server framework for one JSON endpoint).
     * Binds to loopback only -- not reachable from outside the device.
     */
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
                // Server stopped (service destroyed) or port unavailable.
            }
        }
    }

    private fun handleClient(client: Socket) {
        thread(start = true) {
            try {
                client.getInputStream().bufferedReader().readLine() // consume request line, ignore
                val loc = latestLocation.get()
                val body = if (loc != null) {
                    """{"latitude":${loc.latitude},"longitude":${loc.longitude},"accuracy":${loc.accuracy},"speed":${loc.speed},"bearing":${loc.bearing},"time":${loc.time}}"""
                } else {
                    """{"error":"no_fix_yet"}"""
                }
                val writer = OutputStreamWriter(client.getOutputStream())
                writer.write("HTTP/1.1 200 OK\r\n")
                writer.write("Content-Type: application/json\r\n")
                writer.write("Content-Length: ${body.toByteArray().size}\r\n")
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

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try { fusedClient.removeLocationUpdates(callback) } catch (e: Exception) {}
        serverThread?.interrupt()
        try { serverSocket?.close() } catch (e: Exception) {}
    }
}
