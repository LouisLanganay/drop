package dev.langanay.drop

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class ColorSet(val name: String, val colors: List<FloatArray>)

object Palettes {
    private fun c(hex: Int) = floatArrayOf(((hex shr 16) and 0xff) / 255f, ((hex shr 8) and 0xff) / 255f, (hex and 0xff) / 255f)

    val all: LinkedHashMap<String, ColorSet> = linkedMapOf(
        "club" to ColorSet("Club", listOf(c(0xFF2D95), c(0x7B2CFF), c(0x00C8FF), c(0xFFD400))),
        "chaud" to ColorSet("Chaud", listOf(c(0xFF6A00), c(0xFF2D6E), c(0xFFB000), c(0xC03CFF))),
        "mafia" to ColorSet("Mafia", listOf(c(0xC0001A), c(0xFF6A00), c(0x7A0020), c(0xFFB066))),
        "fynex" to ColorSet("Fynex", listOf(c(0xFDA06C), c(0xFECA77), c(0xFF7A45), c(0xE9EC89))),
        "glace" to ColorSet("Glace", listOf(c(0x1E5BFF), c(0x00D0FF), c(0x7A3CFF), c(0x9FD0FF))),
        "sunset" to ColorSet("Coucher de soleil", listOf(c(0xFF4E2A), c(0xFF1F6B), c(0xFFA030), c(0x9B30D9))),
    )

    fun forMood(m: Mood): ColorSet = when (m) {
        Mood.CHILL -> all.getValue("sunset")
        Mood.GROOVE -> all.getValue("chaud")
        Mood.HOUSE -> all.getValue("club")
        Mood.TECHNO -> all.getValue("glace")
        Mood.TRAP -> all.getValue("mafia")
        Mood.ROCK -> all.getValue("mafia")
    }
}

enum class Figure(val label: String) {
    UNISON("Unisson"), DUO("Duo gauche-droite"), WAVE("Vague"), CHASE("Poursuite douce"),
}

/**
 * Le light show, façon boîte de nuit : une nappe de couleur toujours allumée qui respire avec le rythme,
 * plutôt qu'une suite de flashs.
 * - Un look (couleur principale et couleur d'accent qui s'accordent, pochette ou palette du style) tient
 *   16 mesures ; le suivant arrive en fondu de 3 secondes.
 * - Le rythme ne fait que moduler la luminosité, d'une amplitude fixée pour une soirée
 *   (sur chaque temps à haute énergie, sur le premier temps de la mesure sinon, pas du tout au calme).
 * - Les figures (unisson, duo gauche-droite, vague, poursuite douce) déplacent la lumière sans jamais
 *   descendre sous la nappe.
 * - Seul le drop peut flasher : stroboscope de 2,5 à 4 secondes s'il est activé, au plus une fois
 *   toutes les 10 secondes, puis explosion de couleur sur deux mesures.
 */
class Effects(channels: List<Channel>) {
    // Ordre des vagues et poursuites : autour du canapé, de gauche à droite en passant par devant.
    private val ring = channels.sortedByDescending { atan2(it.y, it.x) }
    private val side = channels.associate { it.id to if (it.x < -0.3f) -1 else if (it.x > 0.3f) 1 else 0 }
    private val shownBri = HashMap<Int, Float>()
    private val events = ConcurrentLinkedQueue<AudioEvent>()
    private val rnd = Random(System.nanoTime())

    @Volatile var maxBrightness = 1f
    @Volatile var strobe = 0.3f
    /** 0 = ambiance (presque aucune variation), 1 = fête. */
    @Volatile var intensity = 0.3f
    @Volatile var dropFx = true
    @Volatile var paletteKey = "auto"
    @Volatile var useCover = true
    @Volatile var cover: List<FloatArray> = emptyList()
    @Volatile var genreMood: Mood? = null

    @Volatile var mode = "Silence"
        private set
    @Volatile var figureLabel = ""
        private set

    private var lastTime = 0.0
    private var beats = 0L
    private var beatAt = -10.0
    private var dropAt = -100.0
    private var strobeUntil = -1.0
    private var lastAccent = -100.0
    private var lastChange = -100.0
    private var level = 0f
    private var energy = 0f
    private var tier = 0
    private var figure = Figure.UNISON
    private var look = 0
    private var fromPrimary = floatArrayOf(1f, 0.4f, 0.2f)
    private var fromAccent = floatArrayOf(0.4f, 0.2f, 1f)
    private var curPrimary = fromPrimary
    private var curAccent = fromAccent
    private var lookAt = -10.0

    fun post(e: AudioEvent) { events.add(e) }

    fun frame(now: Double, s: Snapshot): Map<Int, FloatArray> {
        val dt = (if (lastTime == 0.0) 0.02 else now - lastTime).toFloat().coerceIn(0f, 0.1f)
        lastTime = now
        val period = s.period.coerceIn(0.28f, 1.2f).toDouble()
        val st = strobe.coerceIn(0f, 1f)
        val punch = intensity.coerceIn(0f, 1f)
        // Volume lissé sur une demi-seconde : le micro saute d'un mot à l'autre, la lumière ne doit pas.
        level += (s.level - level) * min(1f, dt / 0.6f)
        energy += (s.level - energy) * min(1f, dt / 4f)

        var newPhrase = false
        while (true) {
            when (val e = events.poll() ?: break) {
                is AudioEvent.Beat -> {
                    beats++
                    beatAt = now
                    if (beats % 64L == 0L) newPhrase = true
                }
                AudioEvent.Kick, AudioEvent.Impact -> {}
                AudioEvent.Drop -> if (dropFx) {
                    dropAt = now
                    if (st > 0f && now - lastAccent > 10.0) {
                        // Stroboscope de 2,5 à 4 secondes selon l'énergie du morceau, puis l'explosion de couleur.
                        strobeUntil = now + 2.5 + 1.5 * energy.coerceIn(0f, 1f)
                        lastAccent = now
                        dropAt = strobeUntil
                    }
                    newPhrase = true
                }
            }
        }

        val mood = genreMood?.let { if (it == Mood.HOUSE && s.bpm >= 128f) Mood.TECHNO else it } ?: s.mood
        // Paliers d'énergie avec une marge, pour ne pas basculer sans arrêt entre deux.
        val newTier = when {
            s.silent -> 0
            energy < (if (tier <= 1) 0.3f else 0.24f) -> 1
            energy > (if (tier == 3) 0.48f else 0.56f) && s.onsetRate > 2.5f -> 3
            else -> 2
        }
        if ((newTier != tier && now - lastChange > 8.0) || (newPhrase && now - lastChange > 8.0)) {
            tier = newTier
            lastChange = now
            pickFigure(mood)
            fromPrimary = curPrimary
            fromAccent = curAccent
            look = (look + 1) % 12
            lookAt = now
        }

        val theme = if (useCover && cover.size >= 2) cover else
            (if (paletteKey == "auto") Palettes.forMood(mood) else Palettes.all[paletteKey] ?: Palettes.all.getValue("club")).colors
        val k = ((now - lookAt) / 3.0).toFloat().coerceIn(0f, 1f)
        val primary = mix(fromPrimary, theme[look % theme.size], smooth(k))
        val accent = mix(fromAccent, theme[(look + 1) % theme.size], smooth(k))
        curPrimary = primary
        curAccent = accent

        val sinceBeat = now - beatAt
        val phase = (sinceBeat / period).coerceIn(0.0, 4.0)
        val downbeat = beats % 4L == 0L
        // Respiration : montée franche mais pas instantanée, retombée douce sur la moitié du temps.
        val breath = exp(-sinceBeat / (period * 0.5)).toFloat()
        val sinceDrop = now - dropAt
        val strobeOn = floor(now * 10.0).toLong() % 2L == 0L

        mode = when {
            now < strobeUntil -> "Stroboscope"
            tier == 0 -> "Silence"
            sinceDrop in 0.0..(period * 8) -> "DROP"
            s.buildup > 0.3f -> "Montée"
            tier == 1 -> "Calme"
            tier == 2 -> "Groove"
            else -> "Énergie"
        }
        figureLabel = if (mode == "Groove" || mode == "Énergie") figure.label else ""

        // Nappe : ce qui reste allumé en permanence, selon l'énergie.
        val base = when (tier) {
            0 -> 0.05f
            1 -> 0.3f + 0.25f * level
            2 -> 0.45f + 0.2f * level
            else -> 0.55f + 0.2f * level
        }
        val depth = punch * when (tier) { 3 -> 0.4f; 2 -> 0.25f; else -> 0f }
        val beatBump = when (tier) {
            3 -> breath
            2 -> if (downbeat) breath else 0f
            else -> 0f
        }

        val out = HashMap<Int, FloatArray>()
        val n = ring.size
        for ((i, ch) in ring.withIndex()) {
            var color: FloatArray
            var bri: Float
            var instant = false
            when (mode) {
                "Stroboscope" -> { color = WHITE; bri = if (strobeOn) 1f else 0.05f; instant = true }
                "Silence" -> { color = primary; bri = 0.05f }
                "DROP" -> {
                    // Tout le groupe monte dans la couleur principale, l'accent passe en fondu.
                    val w = (sinceDrop / (period * 8)).toFloat().coerceIn(0f, 1f)
                    color = mix(accent, primary, w)
                    bri = 0.95f - 0.25f * w + depth * 0.5f * breath
                }
                "Montée" -> {
                    // La nappe monte et blanchit doucement, sans clignoter.
                    color = mix(primary, WHITE, 0.5f * s.buildup)
                    bri = base + 0.3f * s.buildup
                }
                "Calme" -> {
                    val w = ((cos(now * 0.25 + i) + 1) / 2).toFloat()
                    color = mix(primary, accent, 0.35f * w)
                    bri = base
                }
                else -> when (figure) {
                    Figure.UNISON -> { color = primary; bri = base + depth * beatBump }
                    Figure.DUO -> {
                        // Deux couleurs, gauche et droite, qui s'échangent lentement toutes les 8 mesures.
                        val sd = side[ch.id] ?: 0
                        val swap = ((cos((beats + phase) / 32.0 * PI) + 1) / 2).toFloat()
                        val left = mix(primary, accent, swap)
                        val right = mix(accent, primary, swap)
                        color = when { sd < 0 -> left; sd > 0 -> right; else -> mix(left, right, 0.5f) }
                        bri = base + depth * beatBump
                    }
                    Figure.WAVE -> {
                        // Une vague de lumière fait le tour de la pièce en une mesure.
                        val bar = ((beats % 4L) + min(phase, 1.0)) / 4.0
                        val w = ((cos(2 * PI * (bar - i.toDouble() / n)) + 1) / 2).toFloat()
                        color = mix(primary, accent, 0.25f * w)
                        bri = base + depth * 0.8f * w
                    }
                    Figure.CHASE -> {
                        // Une lampe un peu plus forte à chaque temps ; les autres gardent la nappe.
                        val lit = (beats % n).toInt() == i
                        color = if (lit) mix(primary, accent, 0.3f) else primary
                        bri = base + (if (lit) depth * breath else 0f)
                    }
                }
            }
            // Lissage : montée en 80 ms, descente en 300 ms (le stroboscope, lui, reste net).
            val prev = shownBri[ch.id] ?: bri
            val tau = if (bri > prev) 0.08f else 0.3f
            val b = if (instant) bri else prev + (bri - prev) * min(1f, dt / tau)
            shownBri[ch.id] = b
            out[ch.id] = scale(color, (b * maxBrightness).coerceIn(0f, 1f))
        }
        return out
    }

    private fun pickFigure(m: Mood) {
        val set = when (tier) {
            0, 1 -> listOf(Figure.UNISON)
            2 -> listOf(Figure.UNISON, Figure.DUO)
            else -> when (m) {
                Mood.TECHNO, Mood.HOUSE -> listOf(Figure.UNISON, Figure.WAVE, Figure.CHASE, Figure.DUO)
                else -> listOf(Figure.UNISON, Figure.DUO, Figure.WAVE)
            }
        }
        val choices = set.filter { it != figure }.ifEmpty { set }
        figure = choices[rnd.nextInt(choices.size)]
    }

    private fun smooth(t: Float) = t * t * (3 - 2 * t)

    companion object {
        val WHITE = floatArrayOf(1f, 1f, 1f)
        fun mix(a: FloatArray, b: FloatArray, t: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * t.coerceIn(0f, 1f) }
        fun scale(a: FloatArray, k: Float) = FloatArray(3) { (a[it] * max(0f, k)).coerceIn(0f, 1f) }
    }
}
