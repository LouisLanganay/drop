package dev.langanay.drop

import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class Mood(val label: String) {
    CHILL("Chill"), GROOVE("Groove"), HOUSE("House"), TECHNO("Techno"), TRAP("Rap / trap"), ROCK("Rock")
}

sealed class AudioEvent {
    /** Un temps, émis en avance de la latence réglée pour que la lumière tombe dessus. */
    data class Beat(val index: Long, val period: Float) : AudioEvent()
    data object Drop : AudioEvent()
    /** Coup de grosse caisse détecté dans les basses (sert au mode « noir et flash »). */
    data object Kick : AudioEvent()
    /** Brusque montée d'énergie : accent de stroboscope. */
    data object Impact : AudioEvent()
}

/** Ce que les effets lisent à chaque image, écrit par le fil audio. */
class Snapshot(
    @JvmField val time: Double,
    @JvmField val level: Float,
    @JvmField val bass: Float,
    @JvmField val high: Float,
    @JvmField val bpm: Float,
    @JvmField val confidence: Float,
    @JvmField val buildup: Float,
    @JvmField val silent: Boolean,
    @JvmField val mood: Mood,
    @JvmField val lastBeat: Double,
    @JvmField val period: Float,
    @JvmField val onsetRate: Float,
)

/**
 * Analyse en direct, un bloc de 512 échantillons à la fois (93,75 blocs par seconde).
 * Spectre sur 2048 points (23 Hz par case), attaques par flux spectral, tempo par
 * autocorrélation des attaques sur 6 secondes, phase des temps suivie et corrigée
 * par les attaques qui tombent près du temps attendu.
 */
class Analyzer(private val sampleRate: Int, private val hop: Int, private val emit: (AudioEvent) -> Unit) {
    private val n = 2048
    private val fps = sampleRate.toFloat() / hop
    private val dt = 1f / fps

    private val ring = FloatArray(n)
    private var ringPos = 0
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / (n - 1))).toFloat() }
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val prevLog = FloatArray(n / 2)
    private var frame = 0L
    private var lastFrameNanos = System.nanoTime()

    @Volatile var sensitivity = 1f
    @Volatile var latencySec = 0.07f

    // Niveaux de référence qui suivent le volume de la pièce (montée rapide, descente lente).
    private var refRms = 0.01f
    private var bassRef = 1e-6f
    private var highRef = 1e-6f

    // Courbe des attaques pour le tempo (~6 s) et historique court pour le seuil.
    private val envLen = 576
    private val env = FloatArray(envLen)
    private var envPos = 0
    private val recent = FloatArray(48)
    private var recentPos = 0
    private var o1 = 0f
    private var o2 = 0f
    private val recentBass = FloatArray(48)
    private var b1 = 0f
    private var b2 = 0f
    private var lastKick = -1.0
    private var lastImpact = -100.0
    private var eFast = 0f
    private val eHist = FloatArray(24)
    private var eHistPos = 0
    private var lastOnset = -1.0
    private val onsetTimes = ArrayDeque<Double>()
    private var onsetRateLong = 0f

    // Tempo et phase.
    private var bpm = 120f
    private var conf = 0f
    private var candidate = 0f
    private var candidateHits = 0
    private var beatTime = -1.0
    private var emittedFor = -1.0
    private var beatIndex = 0L
    private var lastBeatEmitted = 0.0
    private var misses = 0

    // Énergies lissées et sections.
    private var eShort = 0f
    private var eLong = 0f
    private var bShort = 0f
    private var bLong = 0f
    private var hShort = 0f
    private var hLong = 0f
    private var buildup = 0f
    private var buildupPeak = 0f
    private var lowBassSince = -1.0
    private var lastDrop = -100.0
    private var quietSince = -1.0
    private var mood = Mood.GROOVE

    @Volatile var snapshot = Snapshot(0.0, 0f, 0f, 0f, 120f, 0f, 0f, true, Mood.GROOVE, 0.0, 0.5f, 0f)
        private set

    /** Horloge audio, prolongée entre deux blocs pour que les effets aient un temps continu. */
    fun now(): Double = frame / fps.toDouble() + (System.nanoTime() - lastFrameNanos) / 1e9

    fun process(block: FloatArray) {
        for (s in block) {
            ring[ringPos] = s
            ringPos = (ringPos + 1) % n
        }
        frame++
        lastFrameNanos = System.nanoTime()
        val t = frame / fps.toDouble()

        var sum = 0f
        for (s in block) sum += s * s
        val rms = sqrt(sum / block.size)
        refRms = if (rms > refRms) refRms + (rms - refRms) * 0.3f else max(refRms * 0.99893f, 0.003f)
        val level = (rms / (refRms * 0.9f)).coerceIn(0f, 1f).pow(1f / sensitivity.coerceIn(0.3f, 2f))

        if (rms < 0.0025f) { if (quietSince < 0) quietSince = t } else quietSince = -1.0
        val silent = quietSince >= 0 && t - quietSince > 1.2

        // Spectre.
        for (i in 0 until n) {
            re[i] = ring[(ringPos + i) % n] * window[i]
            im[i] = 0f
        }
        fft(re, im)
        var bass = 0f
        var high = 0f
        var flux = 0f
        var bassFlux = 0f
        val maxBin = (10000f / (sampleRate.toFloat() / n)).toInt()
        for (k in 1..maxBin) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k])
            val lm = ln(1f + 100f * mag)
            val d = lm - prevLog[k]
            if (d > 0f) {
                flux += d
                if (k in 2..8) bassFlux += d
            }
            prevLog[k] = lm
            val p = mag * mag
            if (k in 2..6) bass += p else if (k >= 107) high += p
        }
        bassRef = if (bass > bassRef) bassRef + (bass - bassRef) * 0.2f else max(bassRef * 0.9995f, 1e-9f)
        highRef = if (high > highRef) highRef + (high - highRef) * 0.2f else max(highRef * 0.9995f, 1e-9f)
        val bassN = sqrt(bass / bassRef).coerceIn(0f, 1f)
        val highN = sqrt(high / highRef).coerceIn(0f, 1f)

        // Attaques : les basses comptent triple, c'est là que vit la grosse caisse.
        val o = if (silent) 0f else 0.5f * flux + 1.5f * bassFlux
        env[envPos] = o
        envPos = (envPos + 1) % envLen
        recent[recentPos] = o
        recentPos = (recentPos + 1) % recent.size
        var mean = 0f
        for (v in recent) mean += v
        mean /= recent.size
        val threshold = mean * 1.5f + 0.5f
        var onset = false
        val onsetTime = t - dt
        if (o1 > o2 && o1 >= o && o1 > threshold && onsetTime - lastOnset > 0.1) {
            onset = true
            lastOnset = onsetTime
            onsetTimes.addLast(onsetTime)
        }
        o2 = o1
        o1 = o

        // Grosse caisse : pic du flux dans les basses, au-dessus de sa moyenne récente.
        val bf = if (silent) 0f else bassFlux
        recentBass[recentPos % recentBass.size] = bf
        var bm = 0f
        for (v in recentBass) bm += v
        bm /= recentBass.size
        if (b1 > b2 && b1 >= bf && b1 > bm * 1.8f + 0.3f && onsetTime - lastKick > 0.12) {
            lastKick = onsetTime
            emit(AudioEvent.Kick)
        }
        b2 = b1
        b1 = bf

        // Impact : l'énergie des 50 dernières ms dépasse nettement celle des 250 ms d'avant.
        eFast += (level - eFast) * (dt / 0.05f)
        val past = eHist[eHistPos]
        eHist[eHistPos] = eFast
        eHistPos = (eHistPos + 1) % eHist.size
        if (!silent && eFast > 0.75f && eFast - past > 0.4f && t - lastImpact > 1.5) {
            lastImpact = t
            Log.i(TAG, "impact à ${"%.1f".format(t)} s")
            emit(AudioEvent.Impact)
        }
        while (onsetTimes.isNotEmpty() && t - onsetTimes.first() > 2.0) onsetTimes.removeFirst()
        val onsetRate = onsetTimes.size / 2f
        onsetRateLong += (onsetRate - onsetRateLong) * (dt / 8f)

        if (frame % 47L == 0L && frame > fps * 4) estimateTempo()
        trackBeat(t, onset, onsetTime)

        // Sections : montée (basses retirées, aigus et roulements qui grimpent) puis drop (retour des basses).
        eShort += (level - eShort) * (dt / 0.3f)
        eLong += (level - eLong) * (dt / 8f)
        bShort += (bassN - bShort) * (dt / 0.25f)
        bLong += (bassN - bLong) * (dt / 6f)
        hShort += (highN - hShort) * (dt / 0.5f)
        hLong += (highN - hLong) * (dt / 8f)

        val bassDown = bShort < bLong * 0.7f
        val building = !silent && ((hShort > hLong * 1.1f && bassDown) ||
            (bassDown && onsetRate > onsetRateLong * 1.2f && eShort > 0.25f) ||
            (hShort > hLong * 1.3f && onsetRate > onsetRateLong * 1.2f))
        buildup = if (building) min(1f, buildup + dt / 4f) else max(0f, buildup - dt / 1.5f)
        buildupPeak = max(buildup, buildupPeak - dt / 4f)

        if (bShort < bLong * 0.55f) { if (lowBassSince < 0) lowBassSince = t }
        val lowBassFor = if (lowBassSince >= 0) t - lowBassSince else 0.0
        val bassBack = bassN > 0.6f && bShort > max(0.45f, bLong * 1.2f)
        if (bassBack && t - lastDrop > 6.0 && eShort > 0.4f && (buildupPeak > 0.25f || lowBassFor > 1.0)) {
            Log.i(TAG, "DROP à ${"%.1f".format(t)} s (montée ${"%.2f".format(buildupPeak)}, basses absentes ${"%.1f".format(lowBassFor)} s)")
            lastDrop = t
            buildup = 0f
            buildupPeak = 0f
            lowBassSince = -1.0
            emit(AudioEvent.Drop)
        }
        if (bShort > bLong * 0.8f) lowBassSince = -1.0

        if (frame % 188L == 0L) {
            mood = when {
                eLong < 0.2f -> Mood.CHILL
                bpm < 95f -> if (bLong > 0.55f) Mood.TRAP else Mood.CHILL
                bpm < 116f -> Mood.GROOVE
                bpm < 133f -> Mood.HOUSE
                else -> Mood.TECHNO
            }
        }

        snapshot = Snapshot(t, level, bShort, hShort, bpm, conf, buildup, silent, mood, lastBeatEmitted, 60f / bpm, onsetRate)

        if (frame % 94L == 0L) {
            Log.i(TAG, "bpm ${"%.1f".format(bpm)} conf ${"%.2f".format(conf)} niveau ${"%.2f".format(level)} " +
                "basses ${"%.2f".format(bShort)}/${"%.2f".format(bLong)} aigus ${"%.2f".format(hShort)}/${"%.2f".format(hLong)} " +
                "montée ${"%.2f".format(buildup)} attaques/s ${"%.1f".format(onsetRate)} style ${mood.label}${if (silent) " (silence)" else ""}")
        }
    }

    /** Autocorrélation des attaques sur 6 s, préférence douce autour de 122 BPM pour lever l'ambiguïté d'octave. */
    private fun estimateTempo() {
        var m = 0f
        for (v in env) m += v
        m /= envLen
        val x = FloatArray(envLen) { env[(envPos + it) % envLen] - m }
        var zero = 0f
        for (v in x) zero += v * v
        if (zero <= 1e-6f) return
        val minLag = (60f * fps / 185f).toInt()
        val maxLag = (60f * fps / 68f).toInt()
        val acf = FloatArray(maxLag + 2)
        for (lag in (minLag - 1)..(maxLag + 1)) {
            var s = 0f
            for (i in lag until envLen) s += x[i] * x[i - lag]
            acf[lag] = s
        }
        var best = -Float.MAX_VALUE
        var bestLag = 0
        for (lag in minLag..maxLag) {
            val b = 60f * fps / lag
            val prior = exp(-0.5f * (log2(b / 122f) / 0.55f).pow(2))
            val v = acf[lag] * (0.5f + 0.5f * prior)
            if (v > best) { best = v; bestLag = lag }
        }
        if (bestLag <= 0) return
        val a = acf[bestLag - 1]
        val b = acf[bestLag]
        val c = acf[bestLag + 1]
        val den = a - 2 * b + c
        val off = if (den != 0f) (0.5f * (a - c) / den).coerceIn(-0.5f, 0.5f) else 0f
        var est = 60f * fps / (bestLag + off)
        val newConf = (b / zero).coerceIn(0f, 1f)

        if (conf > 0.2f) {
            if (abs(est * 2 - bpm) / bpm < 0.04f) est *= 2 else if (abs(est / 2 - bpm) / bpm < 0.04f) est /= 2
        }
        if (abs(est - bpm) / bpm < 0.04f) {
            bpm += (est - bpm) * 0.3f
            conf += (newConf - conf) * 0.3f
        } else {
            if (candidate > 0f && abs(est - candidate) / candidate < 0.04f) candidateHits++ else { candidate = est; candidateHits = 1 }
            if (candidateHits >= 3 || conf < 0.12f) {
                bpm = est
                conf = newConf * 0.8f
                candidateHits = 0
            } else {
                conf *= 0.97f
            }
        }
    }

    /**
     * Le temps suivant est prévu à partir du dernier et de la période. Il est émis en avance
     * de la latence réglée ; une attaque qui tombe près du temps prévu recale la phase.
     */
    private fun trackBeat(t: Double, onset: Boolean, onsetTime: Double) {
        val period = 60.0 / bpm
        if (beatTime < 0) {
            if (onset && conf > 0.08f) { beatTime = onsetTime; fire(onsetTime, period) }
            return
        }
        val next = beatTime + period
        if (t + latencySec >= next && emittedFor != next) fire(next, period)
        if (onset) {
            val e = onsetTime - next
            when {
                abs(e) < 0.2 * period -> {
                    if (emittedFor != next) fire(next, period)
                    beatTime = next + 0.35 * e
                    emittedFor = beatTime
                    misses = 0
                }
                misses > 6 && conf > 0.2f -> {
                    beatTime = onsetTime
                    fire(onsetTime, period)
                    misses = 0
                }
            }
        }
        if (t > next + 0.2 * period && beatTime < next - 1e-9) {
            beatTime = next
            emittedFor = next
            misses++
        }
    }

    private fun fire(at: Double, period: Double) {
        emittedFor = at
        lastBeatEmitted = at
        beatIndex++
        emit(AudioEvent.Beat(beatIndex, period.toFloat()))
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val size = re.size
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= size) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            val half = len / 2
            var i = 0
            while (i < size) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val vr = re[b] * cr - im[b] * ci
                    val vi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - vr; im[b] = im[a] - vi
                    re[a] += vr; im[a] += vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
