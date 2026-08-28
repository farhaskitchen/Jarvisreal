package com.aadam.jarviscompanion

import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle

/**
 * Self-managed ConnectionService: Android's Telecom framework binds to this
 * when we call TelecomManager.addNewIncomingCall(...), and asks us to
 * supply a Connection object representing the simulated call. Telecom then
 * drives the real system call UI (ringing, answer/decline, in-call screen)
 * exactly as it would for a genuine call -- this is the same mechanism
 * FakeCall and real VoIP apps (WhatsApp, etc.) use.
 */
class JarvisConnectionService : ConnectionService() {

    companion object {
        // Set by CallTriggerServer right before addNewIncomingCall() is
        // called, so the Connection created here knows what message to
        // play once answered. Simple static hand-off since Telecom's
        // Bundle extras aren't guaranteed to survive perfectly across all
        // OEMs for self-managed accounts, and this is a single-call-at-a-
        // time use case anyway.
        @Volatile
        var pendingCallerName: String = "Jarvis"

        @Volatile
        var pendingAudioPath: String? = null
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val connection = JarvisConnection(applicationContext, pendingAudioPath)
        connection.setCallerDisplayName(pendingCallerName, android.telecom.TelecomManager.PRESENTATION_ALLOWED)
        connection.setAddress(
            Uri.fromParts("tel", pendingCallerName, null),
            android.telecom.TelecomManager.PRESENTATION_ALLOWED
        )
        connection.setRinging()
        connection.setAudioModeIsVoip(true)
        return connection
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        // No-op: if Telecom rejects the incoming connection (e.g. account
        // not enabled yet in Phone app settings), there's nothing to clean
        // up since no Connection was created.
    }
}
