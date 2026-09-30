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
    UNISON("Unisson"), CHASE("Poursuite"), PINGPONG("Ping-pong gauche-droite"),
    SWEEP("Balayage aller-retour"), CROSS("Couleurs croisées"), HITS("Noir et flash"),
}

/**
 * Le light show, 50 images par seconde. Son intensité suit la musique sur une échelle continue
 * (« excitation », tirée de l'énergie et de la densité des attaques) :
 * - une figure par phrase de 8 mesures : unisson (accent sur le premier temps), poursuite autour du canapé,
 *   ping-pong gauche-droite, balayage aller-retour, couleurs croisées, et pour le rap et le rock noir et
 *   flash sur chaque grosse caisse ;
 * - les couleurs changent toutes les 4 mesures quand ça envoie, toutes les 8 sinon, et se répartissent
 *   entre les lampes : elles ne font la même chose qu'en unisson ;
 * - ping-pong, poursuite et balayage jouent avec le noir (un côté éteint, deux lampes allumées au plus,
 *   un faisceau qui traverse la pièce), mais jamais toutes les lampes éteintes en même temps ; les autres
 *   figures pulsent sur une nappe ;
 * - montée : poursuite qui accélère et blanchit ; drop : stroboscope de 2,5 à 4 secondes (si activé), toutes
 *   les lampes ensemble, puis deux mesures où tout le groupe change de couleur à chaque temps.
 * Les couleurs viennent de la pochette (complétées de teintes voisines si elle en a peu) ou du style.
 */
class Effects(channels: List<Channel>) {
    // Ordre de la poursuite : autour du canapé, de gauche à droite en passant par devant.
    private val ring = channels.sortedByDescending { atan2(it.y, it.x) }
    // De gauche à droite : position de 0 à 1 pour le balayage, moitié gauche et moitié droite pour le ping-pong.
    private val byX = channels.sortedBy { it.x }
    private val across = byX.withIndex().associate { (i, c) -> c.id to if (byX.size > 1) i / (byX.size - 1f) else 0.5f }
    private val side = byX.withIndex().associate { (i, c) ->
        c.id to when {
            byX.size % 2 == 1 && i == byX.size / 2 -> 0
            i < byX.size / 2 -> -1
            else -> 1
        }
    }
    private val shown = HashMap<Int, FloatArray>()
    private val events = ConcurrentLinkedQueue<AudioEvent>()
    private val rnd = Random(System.nanoTime())

    @Volatile var maxBrightness = 1f
    @Volatile var strobe = 1f
    @Volatile var dropFx = true
    @Volatile var useCover = true
    @Volatile var cover: List<FloatArray> = emptyList()
    @Volatile var genreMood: Mood? = null

    @Volatile var mode = "Silence"
        private set
    @Volatile var figureLabel = ""
        private set
    @Volatile var excitement = 0f
        private set

    private var lastTime = 0.0
    private var beats = 0L
    private var beatAt = -10.0
    private var kickAt = -10.0
    private var dropAt = -100.0
    private var strobeUntil = -1.0
    private var lastStrobe = -100.0
    @Volatile private var testRequested = false
    private var level = 0f
    private var energy = 0f
    private var excite = 0f
    private var calm = true
    private var barsSinceLook = 0
    private var barsSinceFigure = 0
    private var figure = Figure.CROSS
    private var fast = false
    private var chaseDir = 1
    private var look = 0
    private var fromP = floatArrayOf(1f, 0.4f, 0.2f)
    private var fromA = floatArrayOf(0.4f, 0.2f, 1f)
    private var fromT = floatArrayOf(1f, 0.2f, 0.6f)
    private var curP = fromP
    private var curA = fromA
    private var curT = fromT
    private var lookAt = -10.0
    private var fade = 1.0

    fun post(e: AudioEvent) { events.add(e) }

    /** Bouton de test : lance tout de suite la séquence du drop, stroboscope compris. */
    fun testDrop() { testRequested = true }

    fun frame(now: Double, s: Snapshot): Map<Int, FloatArray> {
        val dt = (if (lastTime == 0.0) 0.02 else now - lastTime).toFloat().coerceIn(0f, 0.1f)
        lastTime = now
        val period = s.period.coerceIn(0.28f, 1.2f).toDouble()
        level += (s.level - level) * min(1f, dt / 0.4f)
        energy += (s.level - energy) * min(1f, dt / 3f)
        // Excitation : 0 pour une ballade, 1 pour un morceau qui tape fort et dense.
        val target = if (s.silent) 0f else
            ((energy - 0.18f) / 0.45f).coerceIn(0f, 1f) * (0.55f + 0.45f * (s.onsetRate / 5f).coerceIn(0f, 1f))
        excite += (target - excite) * min(1f, dt / 2f)
        val x = excite
        excitement = x
        if (calm) { if (x > 0.28f) calm = false } else if (x < 0.2f) calm = true

        val mood = genreMood?.let { if (it == Mood.HOUSE && s.bpm >= 128f) Mood.TECHNO else it } ?: s.mood
        if (testRequested) {
            testRequested = false
            drop(now, mood, test = true)
        }
        while (true) {
            when (val e = events.poll() ?: break) {
                is AudioEvent.Beat -> {
                    beats++
                    beatAt = now
                    if (now - dropAt in 0.0..(period * 8)) {
                        // Juste après le drop : tout le groupe change de couleur à chaque temps.
                        changeLook(now, 0.06)
                    } else if (beats % 4L == 0L) {
                        barsSinceLook++
                        barsSinceFigure++
                        if (barsSinceFigure >= 8) pickFigure(mood, x)
                        if (barsSinceLook >= (if (x > 0.5f) 4 else 8)) changeLook(now, if (x > 0.5f) 0.3 else 1.5)
                    }
                }
                AudioEvent.Kick -> if (now - kickAt > 0.22) kickAt = now
                AudioEvent.Impact -> {}
                AudioEvent.Drop -> if (dropFx) drop(now, mood, test = false)
            }
        }

        // Trois couleurs par look, en fondu depuis celles affichées au moment du changement.
        val theme = themeFor(mood)
        val k = smooth(((now - lookAt) / fade).toFloat().coerceIn(0f, 1f))
        val p = mix(fromP, theme[look % theme.size], k)
        val a = mix(fromA, theme[(look + 1) % theme.size], k)
        val t = mix(fromT, theme[(look + 2) % theme.size], k)
        curP = p
        curA = a
        curT = t
        val trio = listOf(p, a, t)

        val sinceBeat = now - beatAt
        val phase = (sinceBeat / period).coerceIn(0.0, 4.0)
        val downbeat = beats % 4L == 0L
        val env = exp(-sinceBeat / (period * lerp(0.5f, 0.35f, x))).toFloat()
        // Nappe et crête : les lampes au repos gardent la nappe, celles qui jouent montent à la crête.
        val lo = lerp(0.32f, 0.2f, x)
        val hi = lerp(0.8f, 1f, x)
        val sinceDrop = now - dropAt
        val strobeOn = floor(now * 10.0).toLong() % 2L == 0L

        mode = when {
            now < strobeUntil -> "Stroboscope"
            s.silent -> "Silence"
            sinceDrop in 0.0..(period * 8) -> "DROP"
            s.buildup > 0.3f -> "Montée"
            calm -> "Calme"
            x > 0.6f -> "Énergie"
            else -> "Groove"
        }
        figureLabel = when (mode) {
            "Groove", "Énergie" -> figure.label
            "DROP" -> "Explosion"
            "Montée" -> "Accélération"
            else -> ""
        }

        val n = ring.size
        val out = HashMap<Int, FloatArray>()
        for ((i, ch) in ring.withIndex()) {
            val sd = side[ch.id] ?: 0
            var instant = false
            var up = 0.035f
            var down = 0.2f
            val target: FloatArray = when (mode) {
                "Stroboscope" -> { instant = true; if (strobeOn) WHITE else scale(WHITE, 0.04f) }
                "Silence" -> scale(p, 0.05f)
                "DROP" -> scale(if (i % 2 == 0) p else a, 0.55f + 0.45f * env)
                "Montée" -> {
                    // Poursuite qui accélère (1, 2 puis 4 lampes par temps) et blanchit, deux lampes allumées au plus.
                    down = 0.06f
                    val speed = if (s.buildup < 0.5f) 1 else if (s.buildup < 0.75f) 2 else 4
                    chase(i, n, speed, phase, scale(mix(p, WHITE, 0.6f * s.buildup), 1f), scale(mix(a, WHITE, 0.4f * s.buildup), 0.35f))
                }
                "Calme" -> {
                    // Chaque lampe dans sa couleur, qui glisse lentement vers celle de sa voisine ; respiration au premier temps.
                    down = 0.4f
                    val w = ((cos(now * 0.25 + i * PI / 2) + 1) / 2).toFloat()
                    scale(mix(trio[i % 3], trio[(i + 1) % 3], w), 0.42f + 0.12f * level + (if (downbeat) 0.15f * env else 0f))
                }
                else -> when (figure) {
                    Figure.UNISON -> {
                        // Toutes ensemble, accent sur le premier temps.
                        scale(if (downbeat && phase < 0.5) a else p, lo + (hi - lo) * env * (if (downbeat) 1f else 0.7f))
                    }
                    Figure.CHASE -> {
                        // Une lampe à la fois autour du canapé, d'un cran par temps (à la croche quand ça envoie),
                        // suivie d'une traîne : deux lampes allumées au plus, les autres éteintes.
                        down = 0.06f
                        chase(i, n, if (fast) 2 else 1, phase, scale(p, hi), scale(a, 0.35f * hi))
                    }
                    Figure.PINGPONG -> {
                        // Gauche sur un temps, droite sur le suivant : le côté qui ne joue pas est noir, celui qui
                        // joue ne descend pas sous 40 %, donc jamais tout éteint. Le centre marque le premier temps.
                        val leftTurn = beats % 2L == 1L
                        val mine = when (sd) { -1 -> leftTurn; 1 -> !leftTurn; else -> downbeat }
                        down = 0.08f
                        if (mine) scale(when (sd) { -1 -> p; 1 -> a; else -> t }, max(0.4f, lo + (hi - lo) * env)) else BLACK
                    }
                    Figure.SWEEP -> {
                        // Un faisceau qui traverse la pièce de gauche à droite puis revient : une mesure par passage,
                        // une demi-mesure quand ça envoie.
                        val perPass = if (fast) 2 else 4
                        val pos = ((beats % perPass) + min(phase, 1.0)) / perPass
                        val head = if ((beats / perPass) % 2L == 0L) pos else 1 - pos
                        // Faisceau dans le noir : la lampe la plus proche du faisceau reste au-dessus de 60 %.
                        val d = (across[ch.id] ?: 0.5f) - head
                        val g = exp(-(d * d) / 0.06).toFloat()
                        down = 0.1f
                        scale(mix(a, p, g), hi * g)
                    }
                    Figure.CROSS -> {
                        // Une lampe sur deux dans chaque couleur, échangées à chaque mesure (tous les deux temps
                        // quand ça envoie) ; pulsation marquée au premier temps, légère sur les autres.
                        val swap = (beats / (if (fast) 2L else 4L)) % 2L == 1L
                        scale(if ((i % 2 == 0) != swap) p else a, lo + ((if (downbeat) hi else 0.6f) - lo) * env)
                    }
                    Figure.HITS -> {
                        // Presque noir, et tout le groupe claque sur chaque grosse caisse, couleurs croisées.
                        val col = if ((i % 2 == 0) != ((beats / 4) % 2L == 1L)) p else a
                        val sinceKick = now - kickAt
                        if (sinceKick > 2.0) scale(col, lo + (0.7f - lo) * env) // pas de grosse caisse : pulsation sur les temps
                        else {
                            instant = sinceKick < 0.03
                            down = 0.07f
                            scale(col, 0.07f + 0.93f * exp(-sinceKick / 0.16).toFloat())
                        }
                    }
                }
            }
            // Lissage par composante : montée rapide, descente plus douce ; le stroboscope reste net.
            val prev = shown[ch.id] ?: target
            val v = if (instant) target else FloatArray(3) { c ->
                prev[c] + (target[c] - prev[c]) * min(1f, dt / (if (target[c] > prev[c]) up else down))
            }
            shown[ch.id] = v
            out[ch.id] = FloatArray(3) { c -> (v[c] * maxBrightness).coerceIn(0f, 1f) }
        }
        return out
    }

    /** Drop : stroboscope (au plus un toutes les 10 s, sauf en test), puis explosion du groupe. */
    private fun drop(now: Double, mood: Mood, test: Boolean) {
        dropAt = now
        if (test || (strobe > 0f && now - lastStrobe > 10.0)) {
            strobeUntil = now + if (test) 3.0 else 2.5 + 1.5 * energy.coerceIn(0f, 1f)
            lastStrobe = now
            dropAt = strobeUntil
        }
        pickFigure(mood, 1f)
    }

    /** Poursuite : la lampe de tête, la précédente en traîne, les autres noires. */
    private fun chase(i: Int, n: Int, speed: Int, phase: Double, head: FloatArray, trail: FloatArray): FloatArray {
        val step = beats * speed + floor(min(phase, 0.999) * speed).toLong()
        return when ((if (chaseDir > 0) i else n - 1 - i).toLong()) {
            step % n -> head
            (step - 1 + n) % n -> trail
            else -> BLACK
        }
    }

    private fun changeLook(now: Double, seconds: Double) {
        fromP = curP
        fromA = curA
        fromT = curT
        look = (look + 1) % 12
        lookAt = now
        fade = seconds
        barsSinceLook = 0
    }

    /** Figure de la phrase suivante, tirée au sort selon le style, jamais deux fois la même d'affilée. */
    private fun pickFigure(m: Mood, x: Float) {
        barsSinceFigure = 0
        val pool = when (m) {
            Mood.TRAP, Mood.ROCK -> listOf(Figure.HITS to 2, Figure.PINGPONG to 2, Figure.CROSS to 2, Figure.CHASE to 1, Figure.UNISON to 1)
            Mood.TECHNO, Mood.HOUSE -> listOf(Figure.CHASE to 2, Figure.SWEEP to 2, Figure.PINGPONG to 2, Figure.CROSS to 1, Figure.UNISON to 1)
            Mood.GROOVE -> listOf(Figure.CROSS to 2, Figure.PINGPONG to 2, Figure.SWEEP to 2, Figure.CHASE to 1, Figure.UNISON to 1)
            Mood.CHILL -> listOf(Figure.SWEEP to 2, Figure.CROSS to 2, Figure.UNISON to 1)
        }.filter { it.first != figure }
        var r = rnd.nextInt(pool.sumOf { it.second })
        figure = pool.first { r -= it.second; r < 0 }.first
        fast = x > 0.6f
        chaseDir = if (rnd.nextBoolean()) 1 else -1
    }

    /** Couleurs du morceau : la pochette complétée de teintes voisines (harmonie analogue), sinon la palette du style. */
    private var themeKey: List<FloatArray>? = null
    private var themeCache: List<FloatArray> = emptyList()
    private fun themeFor(m: Mood): List<FloatArray> {
        if (!(useCover && cover.size >= 2)) return Palettes.forMood(m).colors
        if (themeKey === cover) return themeCache
        val out = ArrayList(cover)
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV((cover[0][0] * 255).toInt(), (cover[0][1] * 255).toInt(), (cover[0][2] * 255).toInt(), hsv)
        var shift = 25f
        while (out.size < 4) {
            out += NowPlaying.hsv(hsv[0] + shift, max(0.75f, hsv[1]), 1f)
            shift = if (shift > 0) -shift else -shift + 20f
        }
        themeKey = cover
        themeCache = out
        return out
    }

    private fun smooth(t: Float) = t * t * (3 - 2 * t)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

    companion object {
        val WHITE = floatArrayOf(1f, 1f, 1f)
        val BLACK = floatArrayOf(0f, 0f, 0f)
        fun mix(a: FloatArray, b: FloatArray, t: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * t.coerceIn(0f, 1f) }
        fun scale(a: FloatArray, k: Float) = FloatArray(3) { (a[it] * max(0f, k)).coerceIn(0f, 1f) }
    }
}
