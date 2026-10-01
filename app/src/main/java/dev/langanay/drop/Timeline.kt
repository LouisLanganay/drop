package dev.langanay.drop

/**
 * Mémoire des deux dernières minutes du show, pour l'écran d'analyse : la musique (niveau, basses), ce que
 * l'analyse en comprend (moment, tension, intensité, temps et mesures, drops, recalages) et ce que jouent
 * les lampes. Écrite par le service à chaque image (50 par seconde) et par l'analyse, lue par l'écran sous
 * le même verrou. Le temps est celui de l'horloge audio, en secondes depuis Go.
 */
object Timeline {
    const val CAP = 6400 // un peu plus de 2 minutes à 50 images par seconde

    /** Moments du show, dans l'ordre des codes enregistrés. */
    val MODES = listOf("Silence", "Calme", "Groove", "Énergie", "Montée", "DROP", "Stroboscope")

    enum class Kind { BEAT, DROP, NOT_DROP, REALIGN, TRACK }

    /** Un repère : un temps ([value] = place dans la phrase, de 0 à 31), un drop, un recalage, un nouveau morceau. */
    class Mark(val t: Double, val kind: Kind, val label: String, val value: Int)

    val time = DoubleArray(CAP)
    val level = FloatArray(CAP)
    val bass = FloatArray(CAP)
    val tension = FloatArray(CAP)
    val intensity = FloatArray(CAP)
    val mode = ByteArray(CAP)
    val figure = ByteArray(CAP)
    var channels: IntArray = IntArray(0)
        private set
    /** Couleur envoyée à chaque lampe (ARGB, luminosité comprise), une ligne par canal de [channels]. */
    var lamps: Array<IntArray> = emptyArray()
        private set
    private var head = 0
    var size = 0
        private set
    val marks = ArrayDeque<Mark>()
    /** Instant (System.nanoTime) du dernier échantillon, pour faire défiler l'écran sans à-coups entre deux images. */
    @Volatile var lastSampleNanos = 0L
        private set

    @Synchronized
    fun reset(channelIds: List<Int>) {
        channels = channelIds.toIntArray()
        lamps = Array(channelIds.size) { IntArray(CAP) }
        head = 0
        size = 0
        marks.clear()
    }

    @Synchronized
    fun sample(t: Double, lv: Float, b: Float, tens: Float, inten: Float, modeName: String, figureCode: Int, out: Map<Int, FloatArray>) {
        time[head] = t
        level[head] = lv
        bass[head] = b
        tension[head] = tens
        intensity[head] = inten
        mode[head] = MODES.indexOf(modeName).coerceAtLeast(0).toByte()
        figure[head] = figureCode.toByte()
        for ((i, ch) in channels.withIndex()) {
            val c = out[ch]
            lamps[i][head] = if (c == null) 0xFF000000.toInt() else android.graphics.Color.rgb(
                (c[0].coerceIn(0f, 1f) * 255).toInt(), (c[1].coerceIn(0f, 1f) * 255).toInt(), (c[2].coerceIn(0f, 1f) * 255).toInt(),
            )
        }
        head = (head + 1) % CAP
        if (size < CAP) size++
        lastSampleNanos = System.nanoTime()
        while (marks.isNotEmpty() && t - marks.first().t > 150.0) marks.removeFirst()
    }

    @Synchronized
    fun mark(t: Double, kind: Kind, label: String = "", value: Int = 0) {
        marks.addLast(Mark(t, kind, label, value))
    }

    /** Case du i-ème échantillon, du plus ancien (0) au plus récent (size - 1). À lire sous le verrou. */
    fun at(i: Int): Int = (head - size + i + CAP) % CAP

    /** Temps du dernier échantillon. À lire sous le verrou. */
    fun last(): Double = if (size == 0) 0.0 else time[at(size - 1)]
}
