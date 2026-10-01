package dev.langanay.drop

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Enregistrement de l'analyse, bloc par bloc (93,75 par seconde) : puissances, flux, niveau, tempo, et les
 * événements (temps, drops, recalages, morceaux, positions de lecture). Deux usages :
 * - les 5 dernières minutes en mémoire, sauvées en CSV à l'arrêt du show et sur demande dans files/recordings ;
 * - pendant le show, si l'envoi est activé, tout part au serveur par tranches (voir [Uploader]).
 */
object Recorder {
    private const val CAP = 28200 // 5 minutes
    private const val COLS = 10
    const val HEADER = "F,t,rms2,bass,high,bflux,mflux,onset,level,bassmid,bpm,conf"

    private val time = DoubleArray(CAP)
    private val data = Array(COLS) { FloatArray(CAP) }
    private var head = 0
    private var size = 0
    private val events = ArrayDeque<Pair<Double, String>>()
    /** Lignes pas encore envoyées au serveur, null quand l'envoi est coupé. */
    private var pending: StringBuilder? = null

    @Synchronized
    fun reset() {
        head = 0
        size = 0
        events.clear()
        pending = null
    }

    /** Commence à garder tout ce qui arrive pour l'envoi, en tête les informations de la session. */
    @Synchronized
    fun startStreaming(meta: Map<String, String>) {
        pending = StringBuilder(1 shl 16).apply {
            for ((k, v) in meta) append("M,").append(k).append(',').append(escape(v)).append('\n')
            append(HEADER).append('\n').append("E,t,kind,detail\n")
        }
    }

    /** Ce qui attend l'envoi depuis le dernier appel (vide si l'envoi est coupé). */
    @Synchronized
    fun takePending(): String {
        val p = pending ?: return ""
        val s = p.toString()
        p.setLength(0)
        return s
    }

    @Synchronized
    fun stopStreaming() { pending = null }

    private fun escape(s: String) = s.replace("%", "%25").replace(",", "%2C").replace("\n", " ")

    @Synchronized
    fun frame(t: Double, vararg v: Float) {
        time[head] = t
        for (i in 0 until COLS) data[i][head] = if (i < v.size) v[i] else 0f
        head = (head + 1) % CAP
        if (size < CAP) size++
        pending?.let { p ->
            p.append("F,").append(t)
            for (i in 0 until COLS) p.append(',').append(if (i < v.size) v[i] else 0f)
            p.append('\n')
        }
    }

    /** Un événement : [kind] (beat, drop, realign...) et son détail (virgules échappées). */
    @Synchronized
    fun event(t: Double, kind: String, detail: String = "") {
        val line = "$kind,${escape(detail)}"
        events.addLast(t to line)
        while (events.isNotEmpty() && t - events.first().first > 300.0) events.removeFirst()
        pending?.append("E,")?.append(t)?.append(',')?.append(line)?.append('\n')
    }

    /** Écrit l'enregistrement et garde les 10 plus récents. Rend le fichier, ou null s'il n'y a rien. */
    @Synchronized
    fun save(ctx: Context): File? {
        if (size == 0) return null
        val dir = File(ctx.filesDir, "recordings").apply { mkdirs() }
        val f = File(dir, "drop-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.csv")
        f.bufferedWriter().use { w ->
            w.append(HEADER).append('\n')
            w.append("E,t,kind,detail\n")
            val sb = StringBuilder(128)
            for (i in 0 until size) {
                val k = (head - size + i + CAP) % CAP
                sb.setLength(0)
                sb.append("F,").append(time[k])
                for (c in 0 until COLS) sb.append(',').append(data[c][k])
                w.append(sb).append('\n')
            }
            for ((t, e) in events) w.append("E,").append(t.toString()).append(',').append(e).append('\n')
        }
        dir.listFiles()?.sortedByDescending { it.name }?.drop(10)?.forEach { it.delete() }
        return f
    }
}
