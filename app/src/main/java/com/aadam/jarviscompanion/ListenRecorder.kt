// ---- ADD to AndroidManifest.xml (inside <manifest>, before <application>) ----
// <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"
//     tools:ignore="ScopedStorage" />

// ---- ADD to MainActivity.kt permissionsNeeded block and onResume ----
// See comments inline below.

// ---- New file: ListenRecorder.kt ----
package com.aadam.jarviscompanion

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Records one voice turn using AudioRecord with adaptive VAD -- the same
 * approach as CallConversationManager but standalone, for the /listen
 * HTTP endpoint. Saves a WAV file to /storage/emulated/0/JarvisAudio/.
 *
 * Returns the WAV file path, or null if no speech was detected or
 * recording failed.
 */
object ListenRecorder {

    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    // How long silence must last after speech before we stop recording
    private const val SILENCE_TO_END_MS = 1500L
    // Give up if nobody speaks within this many ms of recording starting
    private const val PRE_SPEECH_TIMEOUT_MS = 5000L
    // Hard cap -- even if they keep talking, stop here
    private const val MAX_DURATION_MS = 15000L

    private val outputDir = File("/storage/emulated/0/JarvisAudio")

    fun record(): String? {
        outputDir.mkdirs()

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuf <= 0) return null

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, minBuf * 4
            )
        } catch (e: Exception) {
            return null
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }

        val pcmFile = File(outputDir, "listen_raw.pcm")
        val out = DataOutputStream(FileOutputStream(pcmFile))
        val buffer = ShortArray(minBuf)

        var speechDetected = false
        var silenceStartMs = -1L
        var baseline = -1.0
        var baselineSamples = 0
        val startMs = System.currentTimeMillis()

        try {
            recorder.startRecording()

            while (true) {
                val now = System.currentTimeMillis()
                val elapsed = now - startMs

                if (elapsed > MAX_DURATION_MS) break
                if (!speechDetected && elapsed > PRE_SPEECH_TIMEOUT_MS) break

                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                // Compute RMS amplitude for this buffer
                var sumSq = 0.0
                for (i in 0 until read) sumSq += buffer[i].toDouble() * buffer[i].toDouble()
                val rms = sqrt(sumSq / read)

                // Build adaptive baseline from the first ~300ms of audio
                if (baselineSamples < 5) {
                    baseline = if (baseline < 0) rms else (baseline * baselineSamples + rms) / (baselineSamples + 1)
                    baselineSamples++
                }

                val threshold = (if (baseline > 0) baseline else 150.0) * 2.0
                val isSpeech = rms > threshold

                for (i in 0 until read) out.writeShort(buffer[i].toInt())

                if (isSpeech) {
                    speechDetected = true
                    silenceStartMs = -1L
                } else if (speechDetected) {
                    if (silenceStartMs < 0) silenceStartMs = now
                    if (now - silenceStartMs > SILENCE_TO_END_MS) break
                }
            }
        } finally {
            try { recorder.stop() } catch (e: Exception) {}
            recorder.release()
            out.close()
        }

        if (!speechDetected) {
            pcmFile.delete()
            return null
        }

        val wavFile = File(outputDir, "listen_result.wav")
        pcmToWav(pcmFile, wavFile, SAMPLE_RATE)
        pcmFile.delete()
        return wavFile.absolutePath
    }

    private fun pcmToWav(pcmFile: File, wavFile: File, sampleRate: Int) {
        val pcmData = pcmFile.readBytes()
        FileOutputStream(wavFile).use { out ->
            val header = ByteArray(44)
            fun writeString(offset: Int, s: String) {
                for (i in s.indices) header[offset + i] = s[i].code.toByte()
            }
            fun writeInt(offset: Int, v: Int) {
                header[offset]     = (v and 0xff).toByte()
                header[offset + 1] = (v shr 8 and 0xff).toByte()
                header[offset + 2] = (v shr 16 and 0xff).toByte()
                header[offset + 3] = (v shr 24 and 0xff).toByte()
            }
            fun writeShort(offset: Int, v: Int) {
                header[offset]     = (v and 0xff).toByte()
                header[offset + 1] = (v shr 8 and 0xff).toByte()
            }
            writeString(0, "RIFF")
            writeInt(4, pcmData.size + 36)
            writeString(8, "WAVE")
            writeString(12, "fmt ")
            writeInt(16, 16)
            writeShort(20, 1)
            writeShort(22, 1)
            writeInt(24, sampleRate)
            writeInt(28, sampleRate * 2)
            writeShort(32, 2)
            writeShort(34, 16)
            writeString(36, "data")
            writeInt(40, pcmData.size)
            out.write(header)
            out.write(pcmData)
        }
    }
}
