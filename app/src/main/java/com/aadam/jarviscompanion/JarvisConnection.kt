package com.aadam.jarviscompanion

import android.content.Context
import android.media.MediaPlayer
import android.telecom.Connection
import android.telecom.DisconnectCause

/**
 * Represents one simulated call. Telecom calls onAnswer()/onReject()/
 * onDisconnect() in response to the user's actions on the system call UI
 * (or a Bluetooth headset, car system, etc. -- all handled by Telecom
 * automatically since we're plugged into the real framework).
 */
class JarvisConnection(
    private val context: Context,
    private val audioPath: String?
) : Connection() {

    private var mediaPlayer: MediaPlayer? = null

    init {
        // NOTE: previously set connectionProperties = PROPERTY_SELF_MANAGED,
        // but self-managed accounts don't appear in the system's "Calling
        // accounts" settings screen at all -- that's a different Telecom
        // category. Phony (the reference app) uses a CALL_PROVIDER account
        // instead, which DOES show up there, so we're matching that here.
        //
        // Capabilities left at 0 (none) -- we don't support hold, mute
        // management, or conferencing, and setting bogus capability bits
        // here (e.g. via .inv() on a single flag) crashes the call once
        // answered, since Telecom tries to sync nonsense flags to the
        // system UI/audio stack.
        connectionCapabilities = 0
        setAudioModeIsVoip(true)
    }

    override fun onAnswer() {
        setActive()
        playMessageAudio()
    }

    override fun onReject() {
        cleanupAudio()
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
    }

    override fun onDisconnect() {
        cleanupAudio()
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
    }

    override fun onAbort() {
        cleanupAudio()
        setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
        destroy()
    }

    private fun playMessageAudio() {
        val path = audioPath
        if (path == null) {
            return
        }
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener {
                    // Message finished playing -- end the call automatically
                    // rather than leaving it connected with silence.
                    cleanupAudio()
                    setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
                    destroy()
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            // Audio failed to load/play -- disconnect rather than leave a
            // silent, stuck call.
            cleanupAudio()
            setDisconnected(DisconnectCause(DisconnectCause.ERROR))
            destroy()
        }
    }

    private fun cleanupAudio() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            // Already stopped/released, ignore.
        }
        mediaPlayer = null
    }
}
