package com.aadam.jarviscompanion

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Listens on 127.0.0.1:8766/trigger_call for a POST with JSON body
 * {"caller_name": "...", "audio_path": "/path/to/tts.mp3"} and starts a
 * simulated incoming call via Telecom. Jarvis (Termux) hits this instead
 * of shelling out to a separate app's broadcast receiver.
 */
class CallTriggerServer : Service() {

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    companion object {
        const val PORT = 8766
        const val ACCOUNT_ID = "jarvis_call_account"

        fun getPhoneAccountHandle(context: android.content.Context): PhoneAccountHandle {
            return PhoneAccountHandle(
                android.content.ComponentName(context, JarvisConnectionService::class.java),
                ACCOUNT_ID
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        serverThread = thread(start = true) {
            try {
                serverSocket = ServerSocket(PORT, 50, java.net.InetAddress.getByName("127.0.0.1"))
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
                val reader = client.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: ""
                var contentLength = 0
                var line: String?
                while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    if (line!!.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line!!.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }
                val bodyChars = CharArray(contentLength)
                if (contentLength > 0) reader.read(bodyChars, 0, contentLength)
                val body = String(bodyChars)

                var responseBody = """{"status":"ok"}"""
                var statusLine = "HTTP/1.1 200 OK"

                if (requestLine.startsWith("POST") && requestLine.contains("/trigger_call")) {
                    val callerName = extractJsonString(body, "caller_name") ?: "Jarvis"
                    val callerNumber = extractJsonString(body, "caller_number") ?: "0121000000"
                    val audioPath = extractJsonString(body, "audio_path")
                    val ok = triggerCall(callerName, callerNumber, audioPath)
                    if (!ok) {
                        statusLine = "HTTP/1.1 500 Internal Server Error"
                        responseBody = """{"status":"error","message":"Could not place call. Is the Jarvis phone account enabled in Phone app settings?"}"""
                    }
                } else if (requestLine.contains("/status")) {
                    responseBody = """{"status":"running"}"""
                } else {
                    statusLine = "HTTP/1.1 404 Not Found"
                    responseBody = """{"status":"error","message":"unknown endpoint"}"""
                }

                val writer = OutputStreamWriter(client.getOutputStream())
                writer.write("$statusLine\r\n")
                writer.write("Content-Type: application/json\r\n")
                writer.write("Content-Length: ${responseBody.toByteArray().size}\r\n")
                writer.write("Connection: close\r\n\r\n")
                writer.write(responseBody)
                writer.flush()
                writer.close()
            } catch (e: Exception) {
                // Ignore individual connection failures.
            } finally {
                client.close()
            }
        }
    }

    private fun extractJsonString(json: String, key: String): String? {
        // Minimal, dependency-free JSON string-value extraction -- avoids
        // pulling in a JSON library for two expected fields.
        val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun triggerCall(callerName: String, callerNumber: String, audioPath: String?): Boolean {
        return try {
            JarvisConnectionService.pendingCallerName = callerName
            JarvisConnectionService.pendingCallerNumber = callerNumber
            JarvisConnectionService.pendingAudioPath = audioPath

            val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
            val handle = getPhoneAccountHandle(this)
            // Matches Phony's pattern: the system expects the incoming
            // call's address as a proper tel: URI in this specific extra
            // key, not just an empty bundle -- this is likely part of why
            // registration alone wasn't enough to get a working call.
            val extras = Bundle().apply {
                putParcelable(
                    TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                    android.net.Uri.fromParts("tel", callerNumber, null)
                )
            }
            telecomManager.addNewIncomingCall(handle, extras)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serverThread?.interrupt()
        try { serverSocket?.close() } catch (e: Exception) {}
    }
}
