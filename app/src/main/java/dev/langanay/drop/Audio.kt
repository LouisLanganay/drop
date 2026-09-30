package dev.langanay.drop

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log

/**
 * Capture du micro en blocs de 512 échantillons à 48 kHz (un bloc toutes les 10,7 ms).
 * Source « non traitée » quand le téléphone la propose : sans contrôle de gain automatique
 * ni réduction de bruit, qui écraseraient justement les montées et les drops.
 */
class AudioEngine(private val onFrame: (FloatArray) -> Unit) {
    val sampleRate = 48000
    val hop = 512

    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(minBuf, hop * 16))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Micro indisponible")
        }
        Log.i(TAG, "micro : source ${if (unprocessed) "non traitée" else "reconnaissance vocale"}")
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            rec.startRecording()
            val buf = FloatArray(hop)
            while (running) {
                var read = 0
                while (read < hop && running) {
                    val n = rec.read(buf, read, hop - read, AudioRecord.READ_BLOCKING)
                    if (n <= 0) break
                    read += n
                }
                if (read == hop) onFrame(buf.copyOf())
            }
            rec.stop()
            rec.release()
        }, "drop-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(800)
        thread = null
    }
}
