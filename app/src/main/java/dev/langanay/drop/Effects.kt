package dev.langanay.drop

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.atan2
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
    UNISON("Unisson"), CHASE("Poursuite"), PINGPONG("Ping-pong gauche-droite"), SWEEP("Balayage"),
    SPLIT("Couleurs croisées"), HITS("Noir et flash"), SLOW("Fondu"),
}

/**
 * Le light show, 50 images par seconde. Comme un éclairagiste :
 * - un « look » par phrase de 8 mesures, une couleur principale et une couleur d'accent qui s'accordent
 *   (couleurs de la pochette, sinon la palette du style), le blanc gardé pour les temps forts ;
 * - une figure de groupe par phrase (unisson, poursuite une par une, ping-pong gauche-droite, balayage,
 *   couleurs croisées, noir et flash), choisie selon l'énergie et le style ;
 * - les montées accélèrent vers le blanc, le drop part d'une rafale blanche puis tout le groupe explose
 *   dans la couleur principale ; stroboscope bridé à 10 flashs par seconde.
 */
class Effects(channels: List<Channel>) {
    // Ordre de la poursuite : autour du canapé, de gauche à droite en passant par devant.
    private val ring = channels.sortedByDescending { atan2(it.y, it.x) }
    private val side = channels.associate { it.id to if (it.x < -0.3f) -1 else if (it.x > 0.3f) 1 else 0 }
    private val sent = HashMap<Int, FloatArray>()
    private val events = ConcurrentLinkedQueue<AudioEvent>()
    private val rnd = Random(System.nanoTime())

    @Volatile var maxBrightness = 1f
    @Volatile var strobe = 0.6f
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
    private var kickAt = -10.0
    private var dropAt = -100.0
    private var strobeUntil = -1.0
    private var energy = 0f
    private var tier = 0
    private var figure = Figure.SLOW
    private var look = 0
    private var prevPrimary = floatArrayOf(1f, 0.4f, 0.2f)
    private var prevAccent = floatArrayOf(0.4f, 0.2f, 1f)
    private var lookAt = -10.0
    private var curPrimary = prevPrimary
    private var curAccent = prevAccent

    fun post(e: AudioEvent) { events.add(e) }

    fun frame(now: Double, s: Snapshot): Map<Int, FloatArray> {
        val dt = (if (lastTime == 0.0) 0.02 else now - lastTime).toFloat().coerceIn(0f, 0.1f)
        lastTime = now
        val period = s.period.coerceIn(0.28f, 1.2f).toDouble()
        val st = strobe.coerceIn(0f, 1f)
        energy += (s.level - energy) * (dt / 3f)

        var newPhrase = false
        while (true) {
            when (val e = events.poll() ?: break) {
                is AudioEvent.Beat -> {
                    beats++
                    beatAt = now
                    if (beats % 32L == 0L) newPhrase = true
                    // Dernier temps d'une phrase à haute énergie : un demi-temps de stroboscope.
                    if (beats % 32L == 31L && tier == 3 && st > 0.5f) strobeUntil = max(strobeUntil, now + period * 0.5)
                }
                AudioEvent.Kick -> kickAt = now
                AudioEvent.Impact -> if (st > 0f) strobeUntil = max(strobeUntil, now + 0.3)
                AudioEvent.Drop -> if (dropFx) {
                    dropAt = now
                    if (st > 0f) strobeUntil = max(strobeUntil, now + 0.6)
                    newPhrase = true
                }
            }
        }

        val mood = genreMood?.let { if (it == Mood.HOUSE && s.bpm >= 128f) Mood.TECHNO else it } ?: s.mood
        val newTier = when {
            s.silent -> 0
            energy < 0.28f -> 1
            energy > 0.5f && s.onsetRate > 2.5f -> 3
            else -> 2
        }
        if (newTier != tier || newPhrase) {
            tier = newTier
            pickFigure(mood)
            nextLook(now)
        }

        // Look courant, en fondu depuis le précédent sur un peu moins d'une seconde.
        val theme = if (useCover && cover.size >= 2) cover else
            (if (paletteKey == "auto") Palettes.forMood(mood) else Palettes.all[paletteKey] ?: Palettes.all.getValue("club")).colors
        val k = ((now - lookAt) / 0.8).toFloat().coerceIn(0f, 1f)
        val primary = mix(prevPrimary, theme[look % theme.size], k)
        val accent = mix(prevAccent, theme[(look + 1) % theme.size], k)
        curPrimary = primary
        curAccent = accent

        val sinceBeat = now - beatAt
        val phase = (sinceBeat / period).coerceIn(0.0, 2.0)
        val pulse = exp(-sinceBeat / (period * 0.3)).toFloat()
        val downbeat = beats % 4L == 0L
        val sinceDrop = now - dropAt
        val strobeOn = floor(now * 10.0).toLong() % 2L == 0L

        mode = when {
            now < strobeUntil -> "Stroboscope"
            tier == 0 -> "Silence"
            sinceDrop < period * 16 -> "DROP"
            s.buildup > 0.25f -> "Montée"
            tier == 1 -> "Calme"
            else -> "Groove"
        }
        figureLabel = when (mode) {
            "DROP" -> "Explosion"
            "Montée" -> "Accélération"
            "Silence", "Stroboscope" -> ""
            else -> figure.label
        }

        val out = HashMap<Int, FloatArray>()
        for ((i, ch) in ring.withIndex()) {
            var flash = false
            val target: FloatArray = when (mode) {
                "Stroboscope" -> { flash = true; if (strobeOn) WHITE else scale(WHITE, 0.02f) }
                "Silence" -> scale(primary, 0.05f)
                "DROP" -> {
                    // Tout le groupe dans la couleur principale, l'accent claque sur chaque premier temps.
                    val c = if (downbeat && phase < 0.5) accent else primary
                    flash = phase < 0.1
                    scale(c, 0.55f + 0.45f * pulse)
                }
                "Montée" -> {
                    // Poursuite qui accélère (1, 2 puis 4 lampes par temps) et blanchit ; stroboscope au sommet.
                    if (s.buildup > 0.8f && st > 0.5f) {
                        flash = true
                        if (strobeOn) WHITE else scale(WHITE, 0.03f)
                    } else {
                        val speed = if (s.buildup < 0.45f) 1 else if (s.buildup < 0.7f) 2 else 4
                        val step = (beats * speed + floor(phase * speed).toLong()) % ring.size
                        val c = mix(primary, WHITE, 0.15f + 0.6f * s.buildup)
                        flash = true
                        if (step.toInt() == i) scale(c, 0.9f) else scale(c, 0.08f + 0.2f * s.buildup)
                    }
                }
                else -> figureColor(figure, i, ch, primary, accent, phase, pulse, downbeat, s, now).also { flash = it.second }.first
            }
            val prev = sent[ch.id] ?: target
            val a = if (flash) 1f else min(1f, dt / 0.04f)
            val v = FloatArray(3) { c -> prev[c] + (target[c] - prev[c]) * a }
            sent[ch.id] = v
            out[ch.id] = FloatArray(3) { c -> (v[c] * maxBrightness).coerceIn(0f, 1f) }
        }
        return out
    }

    /** Couleur d'une lampe pour la figure en cours ; le booléen dit si le changement doit être instantané. */
    private fun figureColor(
        f: Figure, i: Int, ch: Channel, primary: FloatArray, accent: FloatArray,
        phase: Double, pulse: Float, downbeat: Boolean, s: Snapshot, now: Double,
    ): Pair<FloatArray, Boolean> {
        val n = ring.size
        val base = 0.12f + 0.25f * energy
        return when (f) {
            Figure.SLOW -> {
                // Toutes les lampes ensemble, fondu lent entre les deux couleurs du look.
                val w = ((kotlin.math.sin(now * 0.35) + 1) / 2).toFloat()
                scale(mix(primary, accent, w), 0.15f + 0.5f * s.level) to false
            }
            Figure.UNISON -> {
                // Un seul bloc : pulsation commune à chaque temps, accent sur le premier temps de la mesure.
                val c = if (downbeat && phase < 0.35) accent else primary
                scale(c, base + 0.75f * pulse) to (phase < 0.08)
            }
            Figure.CHASE -> {
                // Une lampe à la fois, qui avance d'un cran par temps (à la croche quand l'énergie est haute).
                val speed = if (tier == 3) 2 else 1
                val step = ((beats * speed + floor(phase * speed).toLong()) % n).toInt()
                val c = if ((beats / 4) % 2L == 0L) primary else accent
                (if (step == i) scale(c, 1f) else scale(c, 0.04f)) to true
            }
            Figure.PINGPONG -> {
                // Gauche sur les temps impairs, droite sur les pairs ; le centre marque le premier temps.
                val sd = side[ch.id] ?: 0
                val leftTurn = beats % 2L == 1L
                val lit = when (sd) { -1 -> leftTurn; 1 -> !leftTurn; else -> downbeat }
                val c = if (sd == 1) accent else primary
                (if (lit) scale(c, 0.25f + 0.75f * pulse) else scale(c, 0.04f)) to (phase < 0.08)
            }
            Figure.SWEEP -> {
                // Une vague qui traverse la pièce à chaque temps, dans un sens puis dans l'autre à chaque mesure.
                val dir = if ((beats / 4) % 2L == 0L) i else n - 1 - i
                val center = dir.toDouble() / n
                val d = kotlin.math.abs(phase - center)
                val bri = exp(-(d * d) / 0.012).toFloat()
                scale(primary, 0.05f + 0.95f * bri) to false
            }
            Figure.SPLIT -> {
                // Gauche principale, droite accent, qui s'échangent à chaque mesure ; pulsation commune.
                val sd = side[ch.id] ?: 0
                val swap = (beats / 4) % 2L == 1L
                val c = when {
                    sd == 0 -> mix(primary, accent, 0.5f)
                    (sd < 0) != swap -> primary
                    else -> accent
                }
                scale(c, base + 0.6f * pulse) to (phase < 0.08)
            }
            Figure.HITS -> {
                // Noir, et tout le groupe claque sur chaque grosse caisse.
                val k = exp(-(now - kickAt) / 0.09).toFloat()
                scale(if (beats % 8L == 0L) accent else primary, 0.02f + 0.98f * k) to (now - kickAt < 0.03)
            }
        }
    }

    private fun pickFigure(m: Mood) {
        val set = when (tier) {
            0, 1 -> listOf(Figure.SLOW)
            2 -> when (m) {
                Mood.CHILL -> listOf(Figure.SLOW, Figure.SPLIT)
                Mood.TRAP, Mood.ROCK -> listOf(Figure.HITS, Figure.SPLIT, Figure.UNISON)
                else -> listOf(Figure.UNISON, Figure.SPLIT, Figure.PINGPONG)
            }
            else -> when (m) {
                Mood.TECHNO -> listOf(Figure.CHASE, Figure.SWEEP, Figure.PINGPONG, Figure.UNISON)
                Mood.HOUSE -> listOf(Figure.UNISON, Figure.PINGPONG, Figure.CHASE, Figure.SWEEP)
                Mood.TRAP -> listOf(Figure.HITS, Figure.UNISON, Figure.PINGPONG)
                Mood.ROCK -> listOf(Figure.HITS, Figure.UNISON, Figure.CHASE)
                Mood.GROOVE -> listOf(Figure.UNISON, Figure.PINGPONG, Figure.SPLIT)
                Mood.CHILL -> listOf(Figure.SPLIT, Figure.UNISON)
            }
        }
        val choices = set.filter { it != figure }.ifEmpty { set }
        figure = choices[rnd.nextInt(choices.size)]
    }

    private fun nextLook(now: Double) {
        // Le nouveau look part des couleurs affichées à l'instant, pour un fondu sans saut.
        prevPrimary = curPrimary
        prevAccent = curAccent
        look = (look + 1) % 12
        lookAt = now
    }

    companion object {
        val WHITE = floatArrayOf(1f, 1f, 1f)
        fun mix(a: FloatArray, b: FloatArray, t: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * t.coerceIn(0f, 1f) }
        fun scale(a: FloatArray, k: Float) = FloatArray(3) { (a[it] * max(0f, k)).coerceIn(0f, 1f) }
    }
}
