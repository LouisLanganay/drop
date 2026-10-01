package dev.langanay.drop

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Enregistrement des 5 dernières minutes de l'analyse, bloc par bloc (93,75 par seconde), pour régler la
 * détection hors ligne sur de vrais morceaux : puissances, flux, niveau, tempo, et les événements (temps,
 * drops, recalages, morceaux). Sauvé en CSV à l'arrêt du show et sur demande depuis l'écran d'analyse, dans
 * files/recordings (lecture par `adb exec-out run-as dev.langanay.drop cat files/recordings/<nom>`).
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

    @Synchronized
    fun reset() {
        head = 0
        size = 0
        events.clear()
    }

    @Synchronized
    fun frame(t: Double, vararg v: Float) {
        time[head] = t
        for (i in 0 until COLS) data[i][head] = if (i < v.size) v[i] else 0f
        head = (head + 1) % CAP
        if (size < CAP) size++
    }

    /** Un événement : [kind] (beat, drop, realign...) et son détail, sans virgule. */
    @Synchronized
    fun event(t: Double, kind: String, detail: String = "") {
        events.addLast(t to "$kind,${detail.replace(',', ';')}")
        while (events.isNotEmpty() && t - events.first().first > 300.0) events.removeFirst()
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
