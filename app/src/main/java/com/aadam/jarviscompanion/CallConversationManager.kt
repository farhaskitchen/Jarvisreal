package com.aadam.jarviscompanion

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Drives one live, multi-turn conversation for a JarvisConnection call.
 * Records the user's speech via AudioRecord (not termux's mic -- this
 * runs inside the companion app's own process, which already holds
 * audio focus for the active call via our ConnectionService), detects
 * when they've stopped talking via a rolling-average silence check
 * (same style of adaptive threshold jarvis_listener.py uses, reimplemented
 * here in Kotlin since this needs to run inside the call's audio session,
 * not as a separate termux-microphone-record process), POSTs the clip to
 * jarvis.py's /call_turn endpoint, and plays back whatever reply audio
 * comes back -- then loops.
 *
 * Silence handling:
 *  - SILENCE_TO_END_TURN_MS of continuous quiet after speech was detected
 *    ends that turn's recording (the user's finished their sentence).
 *  - TOTAL_SILENCE_TIMEOUT_MS with no speech detected AT ALL triggers the
 *    "Sir? Are you there sir?" prompt once, then disconnects after
 *    another TOTAL_SILENCE_TIMEOUT_MS of continued silence.
 */
class CallConversationManager(
    private val jarvisHost: String,
    private val onNeedDisconnect: () -> Unit
) {
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val SILENCE_TO_END_TURN_MS = 1200L
        private const val TOTAL_SILENCE_TIMEOUT_MS = 20000L
        private const val MAX_TURN_RECORDING_MS = 15000L
        private const val CALL_TURN_PORT = 8768
    }

    private val sessionId = UUID.randomUUID().toString()
    private var audioRecord: AudioRecord? = null
    private var replyPlayer: MediaPlayer? = null
    private var running = false
    private var conversationThread: Thread? = null

    /** Called once the initial message finishes playing -- starts the
     * listen -> transcribe -> reply -> listen loop. */
    fun start() {
        running = true
        conversationThread = thread(start = true) { conversationLoop() }
    }

    fun stop() {
        running = false
        try { audioRecord?.stop() } catch (e: Exception) {}
        try { audioRecord?.release() } catch (e: Exception) {}
        audioRecord = null
        try { replyPlayer?.stop() } catch (e: Exception) {}
        try { replyPlayer?.release() } catch (e: Exception) {}
        replyPlayer = null
        // Best-effort -- tells jarvis.py to drop this session's
        // conversation history rather than holding it forever.
        thread(start = true) {
            try {
                postJson("/call_ended", JSONObject().put("session_id", sessionId))
            } catch (e: Exception) {}
        }
    }

    private fun conversationLoop() {
        var consecutiveEmptyTurns = 0
        var alreadyPromptedForSilence = false

        while (running) {
            val recordedFile = recordOneTurn()
            if (!running) break

            if (recordedFile == null) {
                // Nothing but silence for the whole listen window.
                if (!alreadyPromptedForSilence) {
                    alreadyPromptedForSilence = true
                    playLocalTts("Sir? Are you there sir?")
                    continue
                } else {
                    onNeedDisconnect()
                    return
                }
            }

            alreadyPromptedForSilence = false

            val (replyPath, replyText) = sendTurnToJarvis(recordedFile)
            recordedFile.delete()

            if (replyPath == null) {
                consecutiveEmptyTurns++
                if (consecutiveEmptyTurns >= 3) {
                    // Repeated failures talking to jarvis.py -- don't loop
                    // forever with a dead connection.
                    onNeedDisconnect()
                    return
                }
                continue
            }
            consecutiveEmptyTurns = 0

            playReplyAndWait(replyPath)

            // Simple end-of-conversation heuristic: if Jarvis's own reply
            // sounds like a sign-off, hang up rather than keep listening
            // forever after a natural close (matches the user's example:
            // "got it, see you later sir" ends the call there).
            if (looksLikeSignOff(replyText)) {
                onNeedDisconnect()
                return
            }
        }
    }

    private fun looksLikeSignOff(text: String?): Boolean {
        if (text == null) return false
        val lower = text.lowercase()
        return listOf("see you later", "goodbye", "bye sir", "talk soon", "hang up now")
            .any { lower.contains(it) }
    }

    /**
     * Records until SILENCE_TO_END_TURN_MS of quiet follows detected
     * speech, or MAX_TURN_RECORDING_MS total elapses, or
     * TOTAL_SILENCE_TIMEOUT_MS passes with no speech at all (returns null
     * in that last case so the caller can trigger the "Sir? Are you
     * there" prompt).
     */
    private fun recordOneTurn(): File? {
        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufSize <= 0) return null

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, minBufSize * 4
            )
        } catch (e: Exception) {
            return null
        }
        audioRecord = record

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            audioRecord = null
            return null
        }

        val outFile = File.createTempFile("call_turn_", ".pcm")
        val out = DataOutputStream(FileOutputStream(outFile))
        val buffer = ShortArray(minBufSize)

        var speechDetected = false
        var silenceStartMs = -1L
        var lastSpeechMs = System.currentTimeMillis()
        val turnStartMs = System.currentTimeMillis()
        // Ambient-noise baseline established from the first ~300ms of
        // this turn's recording, same adaptive-threshold idea as
        // jarvis_listener.py's record_until_silence rather than a fixed
        // absolute amplitude cutoff.
        var baseline = -1.0
        var baselineSamples = 0

        try {
            record.startRecording()
            while (running) {
                val now = System.currentTimeMillis()
                if (now - turnStartMs > MAX_TURN_RECORDING_MS) break
                if (!speechDetected && now - turnStartMs > TOTAL_SILENCE_TIMEOUT_MS) {
                    out.close()
                    outFile.delete()
                    record.stop()
                    record.release()
                    audioRecord = null
                    return null
                }

                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                var sum = 0.0
                for (i in 0 until read) sum += abs(buffer[i].toInt())
                val avgAmplitude = sum / read

                if (baselineSamples < 5) {
                    baseline = if (baseline < 0) avgAmplitude else (baseline + avgAmplitude) / 2
                    baselineSamples++
                }

                val threshold = (baseline.takeIf { it > 0 } ?: 200.0) * 1.8
                val isSpeech = avgAmplitude > threshold

                for (i in 0 until read) out.writeShort(buffer[i].toInt())

                if (isSpeech) {
                    speechDetected = true
                    lastSpeechMs = now
                    silenceStartMs = -1L
                } else if (speechDetected) {
                    if (silenceStartMs < 0) silenceStartMs = now
                    if (now - silenceStartMs > SILENCE_TO_END_TURN_MS) break
                }
            }
        } catch (e: Exception) {
            // Fall through to cleanup below.
        }

        try { record.stop() } catch (e: Exception) {}
        record.release()
        audioRecord = null
        out.close()

        if (!speechDetected) {
            outFile.delete()
            return null
        }

        val wavFile = File.createTempFile("call_turn_", ".wav")
        pcmToWav(outFile, wavFile, SAMPLE_RATE)
        outFile.delete()
        return wavFile
    }

    /** Wraps raw 16-bit PCM in a minimal WAV header -- Groq's
     * transcription endpoint (via jarvis.py) needs a real audio
     * container, not headerless PCM. */
    private fun pcmToWav(pcmFile: File, wavFile: File, sampleRate: Int) {
        val pcmData = pcmFile.readBytes()
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * 2

        FileOutputStream(wavFile).use { out ->
            val header = ByteArray(44)
            fun writeString(offset: Int, s: String) {
                for (i in s.indices) header[offset + i] = s[i].code.toByte()
            }
            fun writeInt(offset: Int, v: Int) {
                header[offset] = (v and 0xff).toByte()
                header[offset + 1] = (v shr 8 and 0xff).toByte()
                header[offset + 2] = (v shr 16 and 0xff).toByte()
                header[offset + 3] = (v shr 24 and 0xff).toByte()
            }
            fun writeShort(offset: Int, v: Int) {
                header[offset] = (v and 0xff).toByte()
                header[offset + 1] = (v shr 8 and 0xff).toByte()
            }
            writeString(0, "RIFF")
            writeInt(4, totalDataLen)
            writeString(8, "WAVE")
            writeString(12, "fmt ")
            writeInt(16, 16)
            writeShort(20, 1)
            writeShort(22, 1)
            writeInt(24, sampleRate)
            writeInt(28, byteRate)
            writeShort(32, 2)
            writeShort(34, 16)
            writeString(36, "data")
            writeInt(40, pcmData.size)
            out.write(header)
            out.write(pcmData)
        }
    }

    /** POSTs the recorded turn to jarvis.py and returns
     * (androidReplyAudioPath, replyText) or (null, null) on failure. */
    private fun sendTurnToJarvis(wavFile: File): Pair<String?, String?> {
        return try {
            val json = JSONObject()
                .put("session_id", sessionId)
                .put("audio_path", wavFile.absolutePath)
            val respBody = postJson("/call_turn", json)
            val respJson = JSONObject(respBody)
            val replyPath = respJson.optString("reply_audio_path", "")
            val replyText = respJson.optString("reply_text", "")
            if (replyPath.isNotEmpty()) replyPath to replyText else null to replyText
        } catch (e: Exception) {
            null to null
        }
    }

    private fun postJson(path: String, json: JSONObject): String {
        val url = URL("http://$jarvisHost:$CALL_TURN_PORT$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 30000
        conn.outputStream.use { it.write(json.toString().toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream.bufferedReader().readText()
        conn.disconnect()
        return body
    }

    private fun playReplyAndWait(path: String) {
        val latch = java.util.concurrent.CountDownLatch(1)
        try {
            replyPlayer = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener {
                    it.release()
                    latch.countDown()
                }
                setOnErrorListener { mp, _, _ ->
                    mp.release()
                    latch.countDown()
                    true
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            latch.countDown()
        }
        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        replyPlayer = null
    }

    /** For the "Sir? Are you there sir?" prompt -- generated fresh
     * on-device would need edge-tts (Python-only), so this plays a fixed
     * pre-recorded-style line via the same jarvis.py TTS pipeline through
     * a lightweight endpoint reuse: we just send it through /call_turn's
     * sibling generation path by asking jarvis.py to speak it, same as a
     * reply, but without a transcription step. Simplest correct option:
     * treat it as a reply with no user turn, via a tiny dedicated call. */
    private fun playLocalTts(text: String) {
        try {
            val json = JSONObject()
                .put("session_id", sessionId)
                .put("text", text)
            val respBody = postJson("/speak_line", json)
            val respJson = JSONObject(respBody)
            val path = respJson.optString("audio_path", "")
            if (path.isNotEmpty()) playReplyAndWait(path)
        } catch (e: Exception) {
            // If this fails, we still fall through to the silence-timeout
            // disconnect on the next loop iteration rather than getting stuck.
        }
    }
}
