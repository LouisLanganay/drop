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
    /**
     * Un temps, émis en avance de la latence réglée pour que la lumière tombe dessus. [inBar] va de 0
     * (premier temps de la mesure) à 3, [bar] de 0 (première mesure de la phrase de 8) à 7.
     */
    data class Beat(val index: Long, val period: Float, val inBar: Int, val bar: Int) : AudioEvent()
    data object Drop : AudioEvent()
    /** Coup de grosse caisse détecté dans les basses (sert au mode « noir et flash »). */
    data object Kick : AudioEvent()
    /** Brusque montée d'énergie : accent de stroboscope. */
    data object Impact : AudioEvent()
    /** Les basses reviennent après deux mesures sans elles : l'explosion part tout de suite, le stroboscope attend. */
    data object DropStart : AudioEvent()
    /** Le retour des basses n'a pas tenu deux temps : ce n'était pas un drop, le show reprend sa figure. */
    data object DropCancel : AudioEvent()
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
 *
 * Mesures et phrases sont calées sur la musique, pas sur Go : le premier temps est celui où tombe
 * la grosse caisse et jamais la caisse claire, et la phrase de 8 mesures repart d'un drop, d'un
 * nouveau morceau ou d'un changement net de section.
 *
 * Un drop, c'est un bloc de basses qui arrive au moins 6 dB au-dessus de l'énergie des quatre mesures d'avant
 * (ou juste après une montée coupée par un trou), au niveau des passages forts du morceau, et qui tient toute
 * sa première mesure : un coup de basse de deux ou trois temps avant le vrai drop n'en est pas un. Une coupure d'une ou deux mesures dans un groove n'en est pas un : les mesures pleines d'avant la
 * coupure gardent l'énergie haute. Le niveau des passages forts est le 80e centile des temps de la dernière
 * minute : un coup isolé ne le fausse pas.
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
    private var lastDrop = -100.0
    private var quietSince = -1.0
    private var mood = Mood.GROOVE

    // Drops : basses moyennées sur environ un temps, comparées au niveau des basses des passages forts
    // (80e centile des temps de la dernière minute, en dB).
    private var bassMid = 0f
    private val bassBeats = ArrayDeque<Float>()
    private var loudBass = Float.NaN
    private var lastBeatLow = false
    private var lowLevel = 0f
    private var candidateAt = -1.0
    private var candidateBeat = 0L
    private var candidateLow = 0f
    private var lastCancel = -100.0

    // Mesures et phrases : le temps numéro « anchor » est le premier temps de la première mesure d'une phrase.
    private var anchor = 1L
    /** Pas de déplacement du premier temps par les statistiques avant ce temps (après un recalage franc). */
    private var lockUntil = 0L
    private var lastRealign = -100L
    // Grosse caisse et caisse claire mesurées sur chaque temps, cumulées par place dans la mesure.
    private val kickAcc = FloatArray(4)
    private val snareAcc = FloatArray(4)
    private var kickMean = 0f
    private var otherSlotBars = 0
    private val pending = ArrayDeque<Pair<Long, Double>>()
    private val histT = DoubleArray(64)
    private val histB = FloatArray(64)
    private val histM = FloatArray(64)
    private var histPos = 0
    // Puissance de chaque temps (tout, basses, aigus) pour repérer les changements de section.
    private var sumFull = 0.0
    private var sumBass = 0.0
    private var sumHigh = 0.0
    private var sumN = 0
    private val beatFeat = ArrayDeque<Pair<Long, FloatArray>>()
    private val changes = ArrayDeque<Pair<Long, Float>>()
    @Volatile private var trackChanged = false
    // Position de lecture rapportée par le lecteur : [position, instant sur l'horloge audio], et le morceau.
    @Volatile private var posReport: DoubleArray? = null
    @Volatile private var posKey: String? = null
    private var posAlignedKey: String? = null
    private var lastPos = -1.0
    private var lastPosAt = 0.0
    private var anchorOnStrongBeatUntil = -1.0

    /** Niveau le plus fort depuis la dernière lecture, pour le graphe de l'écran d'analyse. */
    @Volatile private var levelPeak = 0f
    fun takeLevelPeak(): Float { val v = levelPeak; levelPeak = 0f; return v }

    @Volatile var snapshot = Snapshot(0.0, 0f, 0f, 0f, 120f, 0f, 0f, true, Mood.GROOVE, 0.0, 0.5f, 0f)
        private set

    /** Nouveau morceau : le prochain temps franc devient le début d'une phrase. */
    fun newTrack() { trackChanged = true }

    /** Position de lecture du morceau [key], en secondes, à l'instant de l'appel. */
    fun trackPosition(sec: Double, key: String) {
        posKey = key
        posReport = doubleArrayOf(sec, now())
    }

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

        if (level > levelPeak) levelPeak = level
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
        var midFlux = 0f
        val maxBin = (10000f / (sampleRate.toFloat() / n)).toInt()
        for (k in 1..maxBin) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k])
            val lm = ln(1f + 100f * mag)
            val d = lm - prevLog[k]
            if (d > 0f) {
                flux += d
                if (k in 2..8) bassFlux += d else if (k in 43..170) midFlux += d
            }
            prevLog[k] = lm
            val p = mag * mag
            if (k in 2..6) bass += p else if (k >= 107) high += p
        }
        bassRef = if (bass > bassRef) bassRef + (bass - bassRef) * 0.2f else max(bassRef * 0.9995f, 1e-9f)
        highRef = if (high > highRef) highRef + (high - highRef) * 0.2f else max(highRef * 0.9995f, 1e-9f)
        val bassN = sqrt(bass / bassRef).coerceIn(0f, 1f)
        val highN = sqrt(high / highRef).coerceIn(0f, 1f)

        if (trackChanged) {
            trackChanged = false
            anchorOnStrongBeatUntil = t + 6.0
            for (i in 0..3) { kickAcc[i] *= 0.3f; snareAcc[i] *= 0.3f }
            beatFeat.clear()
            changes.clear()
            bassBeats.clear()
            loudBass = Float.NaN
            Recorder.event(t, "track")
        }
        histT[histPos] = t - dt
        histB[histPos] = if (silent) 0f else bassFlux
        histM[histPos] = if (silent) 0f else midFlux
        histPos = (histPos + 1) % histT.size
        if (!silent) {
            sumFull += (rms * rms).toDouble()
            sumBass += bass.toDouble()
            sumHigh += high.toDouble()
            sumN++
        }
        while (pending.isNotEmpty() && t - pending.first().second > 0.08) {
            val (raw, at) = pending.removeFirst()
            measureBeat(raw, at)
        }

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
        posReport?.let { r -> posReport = null; alignFromPosition(r[0], r[1], posKey) }
        trackBeat(t, onset, onsetTime)

        // Sections : montée (basses retirées, aigus et roulements qui grimpent) puis drop (retour des basses).
        eShort += (level - eShort) * (dt / 0.3f)
        eLong += (level - eLong) * (dt / 8f)
        bShort += (bassN - bShort) * (dt / 0.25f)
        bLong += (bassN - bLong) * (dt / 6f)
        hShort += (highN - hShort) * (dt / 0.5f)
        hLong += (highN - hLong) * (dt / 8f)

        val bassDown = bShort < bLong * 0.7f
        val building = !silent && t > 6.0 && ((hShort > hLong * 1.1f && bassDown) ||
            (bassDown && onsetRate > onsetRateLong * 1.2f && eShort > 0.25f) ||
            (hShort > hLong * 1.3f && onsetRate > onsetRateLong * 1.2f))
        buildup = if (building) min(1f, buildup + dt / 4f) else max(0f, buildup - dt / 1.5f)
        buildupPeak = max(buildup, buildupPeak - dt / 4f)

        // Drop, en deux temps. Les basses reviennent au niveau des passages forts, nettement au-dessus des quatre
        // mesures d'avant (ou après une montée coupée d'un trou) : l'explosion part tout de suite. Elles tiennent
        // toute la première mesure : c'est confirmé, le stroboscope part sur la deuxième et la phrase repart du drop.
        bassMid += (bass - bassMid) * min(1f, dt / 0.4f)
        if (candidateAt >= 0 && t - candidateAt > 6.0) cancelDrop(t, "le tempo s'est perdu")
        if (candidateAt < 0 && !silent && !loudBass.isNaN() && t > 3.0 && t - lastDrop > 15.0 && t - lastCancel > 0.5 &&
            db(bassMid) >= loudBass - 4f && (db(bassMid) >= lowLevel + 6f || (buildupPeak > 0.5f && lastBeatLow))
        ) {
            // Le retour des basses a précédé d'environ 0,15 s le franchissement du seuil : temps le plus proche de ce retour.
            val back = t - 0.15
            val next = lastBeatEmitted + 60.0 / bpm
            candidateAt = back
            candidateBeat = if (abs(next - back) < abs(lastBeatEmitted - back)) beatIndex + 1 else beatIndex
            candidateLow = lowLevel
            Log.i(TAG, "drop possible à ${"%.1f".format(back)} s (+${"%.1f".format(db(bassMid) - lowLevel)} dB sur les quatre mesures d'avant, montée ${"%.2f".format(buildupPeak)})")
            Recorder.event(back, "drop_start", "low=${"%.1f".format(lowLevel)} ref=${"%.1f".format(loudBass)}")
            emit(AudioEvent.DropStart)
        }
        Recorder.frame(t, rms * rms, bass, high, bassFlux, midFlux, o, level, bassMid, bpm, conf)

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
            val pos = Math.floorMod(beatIndex - anchor, 32L).toInt()
            Log.i(TAG, "mesure ${pos / 4 + 1}/8 temps ${pos % 4 + 1} bpm ${"%.1f".format(bpm)} conf ${"%.2f".format(conf)} niveau ${"%.2f".format(level)} " +
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
        closeBeat(beatIndex)
        beatIndex++
        pending.addLast(beatIndex to at)
        val pos = Math.floorMod(beatIndex - anchor, 32L).toInt()
        Timeline.mark(at, Timeline.Kind.BEAT, value = pos)
        Recorder.event(at, "beat", "$beatIndex $pos")
        emit(AudioEvent.Beat(beatIndex, period.toFloat(), pos % 4, pos / 4))
    }

    /** Le temps [raw] devient le premier temps d'une phrase. */
    private fun realign(raw: Long, why: String) {
        anchor = raw
        lockUntil = raw + 32
        lastRealign = raw
        otherSlotBars = 0
        Log.i(TAG, "phrase recalée : $why")
        Recorder.event(lastBeatEmitted, "realign", "$raw $why")
        Timeline.mark(lastBeatEmitted, Timeline.Kind.REALIGN, "Recalé · ${why.substringBefore(" (")}")
    }

    /**
     * Un temps mesuré après coup, une fois son audio arrivé : grosse caisse (flux des basses) et caisse
     * claire (flux de 1 à 4 kHz) au plus fort dans les 50 ms autour du temps.
     */
    private fun measureBeat(raw: Long, at: Double) {
        var k = 0f
        var m = 0f
        for (i in histT.indices) if (abs(histT[i] - at) <= 0.05) { k = max(k, histB[i]); m = max(m, histM[i]) }
        val slot = Math.floorMod(raw, 4L).toInt()
        kickAcc[slot] = kickAcc[slot] * 0.85f + k
        snareAcc[slot] = snareAcc[slot] * 0.85f + m
        kickMean += (k - kickMean) * 0.1f

        if (anchorOnStrongBeatUntil > 0) {
            if (at > anchorOnStrongBeatUntil) anchorOnStrongBeatUntil = -1.0
            else if (k > max(1.2f * kickMean, 0.5f)) {
                anchorOnStrongBeatUntil = -1.0
                realign(raw, "nouveau morceau")
            }
        }
        if (Math.floorMod(raw - anchor, 4L) == 3L) checkDownbeat()
    }

    /**
     * Une fois par mesure : la place du premier temps est celle où la grosse caisse est la plus forte et la caisse
     * claire absente, la caisse claire tombant sur 2 et 4 (ou sur 3 en demi-tempo). On ne déplace le premier temps
     * que si une autre place l'emporte nettement pendant quatre mesures de suite.
     */
    private fun checkDownbeat() {
        if (beatIndex < lockUntil) return
        val kn = normalized(kickAcc) ?: return
        val sn = normalized(snareAcc) ?: return
        fun score(d: Int) = kn[d] - sn[d] + 0.5f * max((sn[(d + 1) % 4] + sn[(d + 3) % 4]) / 2f, sn[(d + 2) % 4])
        val cur = Math.floorMod(anchor, 4L).toInt()
        var best = cur
        for (d in 0..3) if (score(d) > score(best)) best = d
        otherSlotBars = if (best != cur && score(best) - score(cur) > 0.35f) otherSlotBars + 1 else 0
        if (otherSlotBars >= 4) {
            val shift = (best - cur + 4) % 4
            anchor += if (shift == 3) -1 else shift.toLong()
            otherSlotBars = 0
            Log.i(TAG, "premier temps déplacé d'un cran de $shift (grosse caisse et caisse claire)")
            Timeline.mark(lastBeatEmitted, Timeline.Kind.REALIGN, "1er temps déplacé")
        }
    }

    /**
     * Les morceaux commencent sur le premier temps d'une phrase : la position de lecture donne donc le rang du
     * temps en cours depuis le début, à un temps près (latence du son, silence d'ouverture), que la grosse caisse
     * et la caisse claire départagent. Fait une fois par morceau, et à nouveau si on avance ou recule dans le
     * morceau ; au-delà d'une minute, l'écart de tempo cumulé dépasserait un temps, l'analyse du son prend le relais.
     */
    private fun alignFromPosition(pos: Double, at: Double, key: String?) {
        if (key == null) return
        val seek = key == posAlignedKey && lastPos >= 0 && abs(pos - (lastPos + (at - lastPosAt))) > 1.5
        lastPos = pos
        lastPosAt = at
        if (seek) { posAlignedKey = null; Log.i(TAG, "saut dans le morceau à ${"%.0f".format(pos)} s") }
        if (key == posAlignedKey || beatTime < 0 || conf < 0.2f || pos < 8.0 || pos > 60.0) return
        val period = 60.0 / bpm
        // Position dans le morceau du dernier temps émis, en comptant ~0,1 s de latence entre le haut-parleur et l'analyse.
        val beatPos = pos - (at - lastBeatEmitted) - 0.1
        val n = kotlin.math.round(beatPos / period).toLong()
        var a = beatIndex - n
        val kn = normalized(kickAcc)
        val sn = normalized(snareAcc)
        if (kn != null && sn != null) {
            fun score(d: Int) = kn[d] - sn[d] + 0.5f * max((sn[(d + 1) % 4] + sn[(d + 3) % 4]) / 2f, sn[(d + 2) % 4])
            val base = a
            for (shift in longArrayOf(-1L, 1L)) {
                val d = Math.floorMod(base + shift, 4L).toInt()
                if (score(d) > score(Math.floorMod(a, 4L).toInt()) + 0.2f) a = base + shift
            }
        }
        posAlignedKey = key
        val before = Math.floorMod(beatIndex - anchor, 32L).toInt()
        anchor = a
        lockUntil = beatIndex + 32
        lastRealign = beatIndex
        otherSlotBars = 0
        val after = Math.floorMod(beatIndex - anchor, 32L).toInt()
        Timeline.mark(lastBeatEmitted, Timeline.Kind.REALIGN, "Recalé · position ${"%.0f".format(pos)} s")
        Log.i(TAG, "phrase recalée sur la position ${"%.1f".format(pos)} s : mesure ${after / 4 + 1} temps ${after % 4 + 1} (était mesure ${before / 4 + 1} temps ${before % 4 + 1})")
    }

    private fun normalized(a: FloatArray): FloatArray? {
        val m = a.sum() / 4f
        return if (m <= 1e-6f) null else FloatArray(4) { a[it] / m }
    }

    /**
     * Ferme le temps [raw] : sa puissance moyenne (tout, basses, aigus) entre dans l'historique. Un temps qui change
     * nettement (4 dB ou plus) par rapport aux 8 d'avant, sur les 4 temps qu'il ouvre, est un début de section.
     */
    private fun closeBeat(raw: Long) {
        if (sumN == 0) { beatFeat.clear(); changes.clear(); return }
        fun db(v: Double) = (10.0 * kotlin.math.log10(v + 1e-12)).toFloat()
        val bassDb = db(sumBass / sumN)
        beatFeat.addLast(raw to floatArrayOf(db(sumFull / sumN), bassDb, db(sumHigh / sumN)))
        sumFull = 0.0; sumBass = 0.0; sumHigh = 0.0; sumN = 0
        if (beatFeat.size > 12) beatFeat.removeFirst()
        updateBassContext(bassDb)
        if (candidateAt >= 0 && raw >= candidateBeat + 3) settleDrop()
        if (beatFeat.size < 12) return
        var chg = 0f
        for (band in 0..2) {
            var before = 0f
            var after = 0f
            for (i in 0 until 8) before += beatFeat[i].second[band]
            for (i in 8 until 12) after += beatFeat[i].second[band]
            chg = max(chg, abs(after / 4f - before / 8f) * (if (band == 0) 1f else 0.7f))
        }
        changes.addLast(beatFeat[8].first to chg)
        if (changes.size > 3) changes.removeFirst()
        if (changes.size == 3) {
            val (r, c) = changes[1]
            if (c >= 4f && c >= changes[0].second && c > changes[2].second) sectionStart(r, c)
        }
    }

    private fun db(v: Float) = 10f * kotlin.math.log10(v + 1e-12f)

    /** Basses du temps qui vient de se fermer : niveau des passages forts, et énergie des quatre dernières mesures. */
    private fun updateBassContext(bassDb: Float) {
        bassBeats.addLast(bassDb)
        if (bassBeats.size > 140) bassBeats.removeFirst()
        if (bassBeats.size < 16) return
        val sorted = bassBeats.sorted()
        loudBass = sorted[(sorted.size * 0.8f).toInt().coerceAtMost(sorted.size - 1)]
        // Énergie moyenne (en puissance, pas en dB : une mesure creuse ne fait pas oublier trois mesures pleines).
        var sum = 0.0
        for (i in bassBeats.size - 16 until bassBeats.size) sum += Math.pow(10.0, bassBeats[i] / 10.0)
        lowLevel = (10.0 * kotlin.math.log10(sum / 16.0 + 1e-12)).toFloat()
        lastBeatLow = bassDb <= loudBass - 6f
    }

    /**
     * La première mesure du drop possible est fermée. Il tient si son niveau (moyenne en dB des quatre temps : un
     * coup isolé ne la tire pas) atteint les passages forts à 3 dB près (une montée d'énergie avant le drop reste
     * en dessous), au moins 6 dB au-dessus de l'énergie des quatre mesures d'avant, sans temps qui s'effondre (le
     * trou qui suit un coup de basse annonçant le drop). Réglé sur les enregistrements du 01/10 (« Lunettes »,
     * « Charger ») et les verdicts de Louis.
     */
    private fun settleDrop() {
        val t = frame / fps.toDouble()
        val vals = (0..3).map { i -> beatFeat.firstOrNull { it.first == candidateBeat + i }?.second?.get(1) }
        if (vals.any { it == null }) { cancelDrop(t, "temps manquants"); return }
        val v = vals.filterNotNull()
        val level = v.average().toFloat()
        val weakest = v.min()
        val rise = level - candidateLow
        if (level >= loudBass - 3f && rise >= 6f && weakest >= max(loudBass - 12f, level - 12f)) {
            lastDrop = t
            buildup = 0f
            buildupPeak = 0f
            Log.i(TAG, "DROP confirmé à ${"%.1f".format(candidateAt)} s (+${"%.1f".format(rise)} dB sur les quatre mesures d'avant, ${"%+.1f".format(level - loudBass)} dB par rapport aux passages forts)")
            Recorder.event(candidateAt, "drop", "rise=${"%.1f".format(rise)} level=${"%.1f".format(level)} ref=${"%.1f".format(loudBass)}")
            Timeline.mark(candidateAt, Timeline.Kind.DROP, "Drop · +${"%.0f".format(rise)} dB")
            realign(candidateBeat, "drop")
            candidateAt = -1.0
            emit(AudioEvent.Drop)
        } else {
            cancelDrop(t, "+${"%.1f".format(rise)} dB sur les quatre mesures d'avant, ${"%+.1f".format(level - loudBass)} dB par rapport aux passages forts, temps le plus faible ${"%+.1f".format(weakest - loudBass)} dB")
        }
    }

    private fun cancelDrop(t: Double, why: String) {
        Log.i(TAG, "pas un drop : $why")
        Recorder.event(candidateAt, "drop_cancel", why)
        Timeline.mark(candidateAt, Timeline.Kind.NOT_DROP, "Pas un drop")
        candidateAt = -1.0
        lastCancel = t
        emit(AudioEvent.DropCancel)
    }

    private fun sectionStart(raw: Long, db: Float) {
        val pos = Math.floorMod(raw - anchor, 32L).toInt()
        if (pos % 16 == 0) return // déjà en début ou au milieu d'une phrase
        if (raw - lastRealign < 32) return // recalé il y a moins de 8 mesures
        if (pos % 4 != 0 && db < 6f) return // contredire le premier temps demande un changement très net
        realign(raw, "début de section (${"%.1f".format(db)} dB, était mesure ${pos / 4 + 1} temps ${pos % 4 + 1})")
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
