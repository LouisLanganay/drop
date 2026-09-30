package dev.langanay.drop

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class Palette(val name: String, val colors: List<FloatArray>)

object Palettes {
    private fun c(hex: Int) = floatArrayOf(((hex shr 16) and 0xff) / 255f, ((hex shr 8) and 0xff) / 255f, (hex and 0xff) / 255f)

    val all: LinkedHashMap<String, Palette> = linkedMapOf(
        "club" to Palette("Club", listOf(c(0xFF2D95), c(0x7B2CFF), c(0x00C8FF), c(0xFFD400))),
        "chaud" to Palette("Chaud", listOf(c(0xFF8A3D), c(0xFF3D6E), c(0xFFC46B), c(0xB45CFF))),
        "mafia" to Palette("Mafia", listOf(c(0xB0001A), c(0xFF6A00), c(0x5A0010), c(0xFFB066))),
        "fynex" to Palette("Fynex", listOf(c(0xFECA77), c(0xFDA06C), c(0xFED1B3), c(0xE9EC89))),
        "glace" to Palette("Glace", listOf(c(0x2B6BFF), c(0x6ED6EA), c(0xB6D6FF), c(0x8A5CFF))),
        "sunset" to Palette("Coucher de soleil", listOf(c(0xFF5E3A), c(0xFF2A68), c(0xFFB347), c(0x8E44AD))),
    )

    fun forMood(m: Mood): Palette = when (m) {
        Mood.CHILL -> all.getValue("sunset")
        Mood.GROOVE -> all.getValue("chaud")
        Mood.HOUSE -> all.getValue("club")
        Mood.TECHNO -> all.getValue("glace")
        Mood.TRAP -> all.getValue("mafia")
    }
}

/**
 * Transforme l'analyse en couleurs, 50 fois par seconde, pour chaque canal de la zone.
 * Les canaux sont rangés de gauche à droite (position x dans la zone) pour les balayages.
 */
class Effects(channels: List<Channel>) {
    private val order = channels.sortedBy { it.x }
    private val sent = HashMap<Int, FloatArray>()
    private val events = ConcurrentLinkedQueue<AudioEvent>()

    @Volatile var maxBrightness = 1f
    @Volatile var strobe = 0.6f
    @Volatile var dropFx = true
    @Volatile var paletteKey = "auto"

    @Volatile var mode = "Silence"
        private set

    private var pulse = 0f
    private var beats = 0L
    private var offset = 0
    private var mirrored = false
    private var drift = 0f
    private var dropStart = -100.0
    private var lastTime = 0.0

    fun post(e: AudioEvent) { events.add(e) }

    fun frame(now: Double, s: Snapshot): Map<Int, FloatArray> {
        val dt = (if (lastTime == 0.0) 0.02 else now - lastTime).toFloat().coerceIn(0f, 0.1f)
        lastTime = now
        while (true) {
            when (val e = events.poll() ?: break) {
                is AudioEvent.Beat -> {
                    pulse = 1f
                    beats++
                    if (beats % 2L == 0L) offset++
                    if (beats % 4L == 0L) mirrored = !mirrored
                }
                AudioEvent.Drop -> if (dropFx) dropStart = now
            }
        }
        val period = s.period.coerceIn(0.3f, 1.2f)
        pulse *= exp(-dt / (period * 0.35f))
        drift += dt / 6f

        val pal = (if (paletteKey == "auto") Palettes.forMood(s.mood) else Palettes.all[paletteKey]) ?: Palettes.all.getValue("club")
        val cols = pal.colors
        val sinceDrop = now - dropStart
        mode = when {
            s.silent -> "Silence"
            sinceDrop < period * 32.0 -> "DROP"
            s.buildup > 0.25f -> "Montée"
            s.level < 0.25f -> "Calme"
            else -> "Groove"
        }
        val st = strobe.coerceIn(0f, 1f)
        // Stroboscope : 10 Hz au plus, 50 ms allumé puis 50 ms éteint.
        val strobeOn = floor(now * 10.0).toLong() % 2L == 0L

        val out = HashMap<Int, FloatArray>()
        for ((idx, ch) in order.withIndex()) {
            val i = if (mirrored) order.size - 1 - idx else idx
            var flash = false
            val target: FloatArray = when (mode) {
                "Silence" -> scale(blend(cols, drift * 0.3f + idx), 0.06f)

                "Calme" -> scale(blend(cols, drift + idx * 1.3f), 0.12f + 0.45f * s.level + 0.15f * s.bass)

                "Montée" -> {
                    // Pulsations à la croche, puis à la double croche quand la montée s'intensifie, vers le blanc.
                    val sub = if (s.buildup < 0.5f) 2.0 else 4.0
                    val phase = ((now - s.lastBeat) / period).let { it - floor(it) }
                    val sp = (1.0 - (phase * sub - floor(phase * sub))).pow(3.0).toFloat()
                    if (s.buildup > 0.85f && st > 0f) {
                        flash = true
                        if (strobeOn) scale(WHITE, 0.4f + 0.6f * st) else scale(WHITE, 0.03f)
                    } else {
                        val base = mix(cols[(offset + i) % cols.size], WHITE, 0.2f + 0.6f * s.buildup)
                        scale(base, 0.2f + 0.65f * s.buildup * sp + 0.15f * s.level)
                    }
                }

                "DROP" -> when {
                    sinceDrop < 0.45 -> {
                        // Rafale blanche au moment du drop (un seul éclair si le stroboscope est coupé).
                        flash = true
                        if (st > 0f) { if (strobeOn) WHITE else scale(WHITE, 0.02f) }
                        else scale(WHITE, (1.0 - sinceDrop / 0.45).toFloat())
                    }
                    else -> {
                        // Couleurs saturées, et un balayage gauche-droite qui avance d'un canal par temps.
                        val hot = (beats % order.size).toInt() == i
                        val c = cols[(offset + i) % cols.size]
                        if (st > 0.5f && beats % 4L == 0L && pulse > 0.75f) { flash = true; WHITE }
                        else scale(c, if (hot) 1f else 0.45f + 0.45f * pulse)
                    }
                }

                else -> {
                    // Groove : une pulsation par temps, la couleur tourne tous les deux temps, le sens s'inverse à chaque mesure.
                    val c = cols[(offset + i) % cols.size]
                    val front = if (ch.y > 0.5f) 0.2f * s.bass else 0f
                    scale(c, 0.22f + 0.33f * s.level + 0.5f * pulse + front)
                }
            }
            val prev = sent[ch.id] ?: target
            val k = if (flash) 1f else min(1f, dt / 0.035f)
            val v = FloatArray(3) { c -> ((prev[c] + (target[c] - prev[c]) * k) * maxBrightness).coerceIn(0f, 1f) }
            sent[ch.id] = FloatArray(3) { c -> prev[c] + (target[c] - prev[c]) * k }
            out[ch.id] = v
        }
        return out
    }

    private fun blend(cols: List<FloatArray>, pos: Float): FloatArray {
        val p = ((pos % cols.size) + cols.size) % cols.size
        val a = cols[p.toInt() % cols.size]
        val b = cols[(p.toInt() + 1) % cols.size]
        return mix(a, b, p - floor(p))
    }

    companion object {
        val WHITE = floatArrayOf(1f, 1f, 1f)
        fun mix(a: FloatArray, b: FloatArray, t: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * t.coerceIn(0f, 1f) }
        fun scale(a: FloatArray, k: Float) = FloatArray(3) { (a[it] * max(0f, k)).coerceIn(0f, 1f) }
    }
}
