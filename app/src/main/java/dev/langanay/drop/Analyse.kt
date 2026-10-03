package dev.langanay.drop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val Retour = trait("M15 6l-6 6 6 6")
internal val Courbe = trait("M3 12h4l3-7 4 14 3-7h4")

private val MOMENTS = listOf("Silence", "Calme", "Groove", "Énergie", "Montée", "Drop")

/** Moment de la musique compris par l'analyse : le stroboscope fait partie du drop. */
private fun momentDe(mode: Int): Int = if (mode == 6) 5 else mode

/** Ce que jouent les lampes, tiré du moment et de la figure enregistrés. */
internal fun effetDe(mode: Int, figure: Int): String = when (Timeline.MODES.getOrNull(mode)) {
    "Silence" -> "Veille"
    "Calme" -> "Respiration"
    "Montée" -> "Accélération"
    "DROP" -> "Explosion"
    "Stroboscope" -> "Stroboscope"
    else -> Figure.values().getOrNull(figure)?.let { FIGURES[it.label]?.first ?: it.label } ?: "Groove"
}

/** Ce que l'écran copie de la mémoire du show, le temps d'une image. */
private class Vue(
    val debut: Double,
    val cols: Int,
    val niveau: FloatArray,
    val basses: FloatArray,
    val tension: FloatArray,
    val intensite: FloatArray,
    val strobe: BooleanArray,
    val lampes: Array<IntArray>,
    val segments: List<Segment>,
    val reperes: List<Timeline.Mark>,
)

private class Segment(val t0: Double, var t1: Double, val mode: Int, val figure: Int)

/**
 * Analyse en direct : la musique au milieu (le son au-dessus de l'axe, les basses en dessous), ce que l'analyse
 * en comprend en bas (temps et mesures, moment, tension et intensité, drops et recalages), ce que jouent les
 * lampes en haut (figure, puis la couleur envoyée à chaque lampe). Le présent est au bord droit. Toucher le
 * graphe le fige pour le lire.
 */
@Composable
internal fun AnalyseScreen(lamps: List<Lamp>, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val state by DropService.live.collectAsState()
    val accent = if (state.running) accentDe(state.lead) else Texte
    var fenetre by remember { mutableStateOf(30.0) }
    var fige by remember { mutableStateOf<Double?>(null) }
    var image by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { image = it } }
    val mesureur = rememberTextMeasurer()
    // Lampes de gauche à droite, comme sur le plan.
    val ordre = remember(lamps) { lamps.sortedBy { it.x } }

    Column(
        Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = "Revenir au show", onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Icon(Retour, contentDescription = "Retour", tint = Texte, modifier = Modifier.size(22.dp)) }
            Text(
                "Analyse en direct", Modifier.padding(start = 2.dp).weight(1f),
                style = corps(19, FontWeight.SemiBold).copy(letterSpacing = (-0.4).sp),
            )
            Fenetres(fenetre) { fenetre = it }
        }
        Resume(state, accent)
        MemoireMorceau()
        Canvas(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(Plan)
                .border(1.dp, Ligne, RoundedCornerShape(16.dp))
                .pointerInput(Unit) { detectTapGestures { fige = if (fige == null) finActuelle() else null } },
        ) {
            image // redessine à chaque image de l'écran
            val fin = fige ?: finActuelle()
            dessiner(mesureur, fin, fenetre, ordre, accent, vivant = fige == null && state.running)
        }
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var sauve by remember { mutableStateOf<String?>(null) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                sauve ?: if (fige != null) "Figé. Touche le graphe pour reprendre." else "Touche le graphe pour le figer.",
                Modifier.weight(1f), style = corps(12, color = Secondaire), maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Repere, RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = "Enregistrer les 5 dernières minutes") {
                        scope.launch {
                            val f = withContext(Dispatchers.IO) { runCatching { Recorder.save(ctx) }.getOrNull() }
                            sauve = if (f != null) "Enregistré : ${f.name}" else "Rien à enregistrer."
                            delay(5000)
                            sauve = null
                        }
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Enregistrer", style = corps(14, FontWeight.Medium)) }
        }
    }
}

/** Présent du graphe : le dernier échantillon, prolongé jusqu'à maintenant pendant le show pour défiler sans à-coups. */
private fun finActuelle(): Double {
    val dernier = synchronized(Timeline) { Timeline.last() }
    return if (DropService.live.value.running) dernier + (System.nanoTime() - Timeline.lastSampleNanos).coerceIn(0L, 200_000_000L) / 1e9
    else dernier
}

@Composable
private fun Fenetres(fenetre: Double, onChange: (Double) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Surface).border(1.dp, Ligne2, RoundedCornerShape(10.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(30.0 to "30 s", 120.0 to "2 min").forEach { (v, nom) ->
            val choisi = fenetre == v
            Box(
                Modifier.height(38.dp).clip(RoundedCornerShape(8.dp)).background(if (choisi) SurfaceHaute else Color.Transparent)
                    .selectable(selected = choisi, role = Role.RadioButton) { onChange(v) }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(nom, style = corps(13, FontWeight.Medium, color = if (choisi) Texte else Secondaire)) }
        }
    }
}

/** Ce que Drop sait du morceau en cours : ses drops mémorisés, avec « Oublier », ou qu'il ne le connaît pas encore. */
@Composable
private fun MemoireMorceau() {
    val ctx = LocalContext.current
    val track by NowPlaying.track.collectAsState()
    val v by TrackMemory.version.collectAsState()
    val t = track ?: return
    val key = remember(t.artist, t.title) { TrackMemory.key(t.artist, t.title) }
    val drops = remember(key, v) { TrackMemory.drops(key) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            when {
                drops == null -> "Morceau pas encore connu : il le sera après une écoute en entier."
                drops.isEmpty() -> "Morceau connu, sans drop."
                else -> "Morceau connu : drop${if (drops.size > 1) "s" else ""} à ${drops.joinToString(", ") { "${it.toInt() / 60}:${"%02d".format(it.toInt() % 60)}" }}."
            },
            Modifier.weight(1f), style = corps(13, color = Secondaire), maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (drops != null) {
            Box(
                Modifier.height(36.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Repere, RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClickLabel = "Oublier ce morceau") { TrackMemory.forget(ctx, key) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Oublier", style = corps(13, FontWeight.Medium)) }
        }
    }
}

/** Le moment et la figure en cours, le tempo et la place dans la phrase. */
@Composable
private fun Resume(state: LiveState, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.running) {
            Text("Arrêté. Le graphe garde le dernier show.", style = corps(14, color = Secondaire))
            return@Row
        }
        val moment = if (state.mode == "DROP" || state.mode == "Stroboscope") "Drop" else state.mode
        val fort = moment == "Montée" || moment == "Drop"
        Text(moment, style = corps(15, FontWeight.SemiBold, color = if (fort) accent else Texte))
        if (state.figure.isNotEmpty()) {
            Text(FIGURES[state.figure]?.first ?: state.figure, Modifier.weight(1f, fill = false), style = corps(15, color = Secondaire), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.weight(1f))
        Text(
            "${state.bpm.roundToInt()} BPM  ${state.barInPhrase.coerceIn(1, 8)}/8  T${state.beatInBar.coerceIn(1, 4)}",
            style = Etiquette.copy(color = Tertiaire),
        )
    }
}

/** Copie sous le verrou de ce que la fenêtre montre : colonnes de 2 dp, segments de moment, repères. */
private fun copier(debut: Double, fenetre: Double, cols: Int, ordre: List<Lamp>): Vue? = synchronized(Timeline) {
    val n = Timeline.size
    if (n == 0) return@synchronized null
    val niveau = FloatArray(cols)
    val basses = FloatArray(cols)
    val tension = FloatArray(cols) { -1f }
    val intensite = FloatArray(cols) { -1f }
    val strobe = BooleanArray(cols)
    val canal = ordre.map { l -> Timeline.channels.indexOf(l.channel) }
    val lampes = Array(ordre.size) { IntArray(cols) }
    val segments = ArrayList<Segment>()
    // Premier échantillon de la fenêtre, par dichotomie (les temps sont croissants).
    var lo = 0
    var hi = n
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (Timeline.time[Timeline.at(mid)] < debut) lo = mid + 1 else hi = mid
    }
    for (i in max(0, lo - 1) until n) {
        val k = Timeline.at(i)
        val t = Timeline.time[k]
        val m = Timeline.mode[k].toInt()
        val f = Timeline.figure[k].toInt()
        val last = segments.lastOrNull()
        if (last != null && last.mode == m && last.figure == f) last.t1 = t else segments += Segment(max(t, debut), t, m, f)
        if (t < debut) continue
        val c = ((t - debut) / fenetre * cols).toInt()
        if (c !in 0 until cols) continue
        niveau[c] = max(niveau[c], Timeline.level[k])
        basses[c] = max(basses[c], Timeline.bass[k])
        tension[c] = Timeline.tension[k]
        intensite[c] = Timeline.intensity[k]
        if (m == 6) strobe[c] = true
        for ((j, ch) in canal.withIndex()) if (ch >= 0) lampes[j][c] = Timeline.lamps[ch][k]
    }
    val reperes = Timeline.marks.filter { it.t >= debut - 0.5 }
    Vue(debut, cols, niveau, basses, tension, intensite, strobe, lampes, segments, reperes)
}

private fun DrawScope.dessiner(m: TextMeasurer, fin: Double, fenetre: Double, ordre: List<Lamp>, accent: Color, vivant: Boolean) {
    val petit = TextStyle(fontFamily = Geist, fontSize = 11.sp, color = Tertiaire)
    val mono = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.6.sp, color = Tertiaire)
    val gauche = 88.dp.toPx()
    val droite = size.width - 12.dp.toPx()
    val pw = droite - gauche
    val debut = fin - fenetre
    val cols = max(1, (pw / 2.dp.toPx()).toInt())
    val colW = pw / cols
    fun x(t: Double) = gauche + ((t - debut) / fenetre * pw).toFloat()

    val vue = copier(debut, fenetre, cols, ordre)
    if (vue == null) {
        val r = m.measure("Lance Go : le graphe se construit en direct.", corps(14, color = Secondaire))
        drawText(r, topLeft = Offset((size.width - r.size.width) / 2, (size.height - r.size.height) / 2))
        return
    }

    // Étages, de haut en bas.
    var y = 12.dp.toPx()
    val titreEffets = y
    y += 20.dp.toPx()
    val bandeEffet = y
    val hBande = 22.dp.toPx()
    y += hBande + 6.dp.toPx()
    val hLampe = 20.dp.toPx()
    val lampes0 = y
    y += ordre.size * (hLampe + 4.dp.toPx()) + 12.dp.toPx()
    val titreMusique = y
    y += 20.dp.toPx()
    val onde0 = y
    val bas = size.height
    val analyse0 = bas - 192.dp.toPx()
    val onde1 = analyse0 - 14.dp.toPx()
    val titreAnalyse = analyse0
    val grille = analyse0 + 20.dp.toPx()
    val bandeMoment = grille + 18.dp.toPx()
    val courbes0 = bandeMoment + hBande + 6.dp.toPx()
    val hCourbes = 60.dp.toPx()
    val reperes0 = courbes0 + hCourbes + 6.dp.toPx()
    val axe = reperes0 + 34.dp.toPx()

    fun texte(s: String, style: TextStyle, x: Float, y: Float, largeurMax: Float = Float.MAX_VALUE): Float {
        val r = m.measure(s, style, maxLines = 1, overflow = TextOverflow.Ellipsis,
            constraints = androidx.compose.ui.unit.Constraints(maxWidth = largeurMax.coerceIn(1f, 100000f).toInt()))
        drawText(r, topLeft = Offset(x, y))
        return r.size.width.toFloat()
    }
    fun largeur(s: String, style: TextStyle) = m.measure(s, style, maxLines = 1).size.width.toFloat()

    // Mesures en fond : un trait par mesure, plus marqué en début de phrase.
    for (r in vue.reperes) {
        if (r.kind != Timeline.Kind.BEAT || r.value % 4 != 0) continue
        val xb = x(r.t)
        if (xb < gauche || xb > droite) continue
        drawLine(if (r.value == 0) Neutre.copy(alpha = 0.55f) else Ligne2, Offset(xb, bandeEffet), Offset(xb, reperes0), 1.dp.toPx())
    }

    // Titres d'étage et noms de ligne dans la gouttière.
    texte("EFFETS", Etiquette.copy(color = Tertiaire, fontSize = 10.sp), 12.dp.toPx(), titreEffets)
    texte("Figure", petit, 12.dp.toPx(), bandeEffet + 4.dp.toPx(), gauche - 16.dp.toPx())
    texte("MUSIQUE", Etiquette.copy(color = Tertiaire, fontSize = 10.sp), 12.dp.toPx(), titreMusique)
    texte("ANALYSE", Etiquette.copy(color = Tertiaire, fontSize = 10.sp), 12.dp.toPx(), titreAnalyse)

    // Figure jouée.
    for (s in vue.segments) {
        val x0 = max(gauche, x(s.t0))
        val x1 = min(droite, x(s.t1))
        if (x1 - x0 < 1f) continue
        val nom = effetDe(s.mode, s.figure)
        val (fond, encre) = when (s.mode) {
            5, 6 -> accent to Fond
            4 -> accent.copy(alpha = 0.4f) to Texte
            0 -> Eteinte to Tertiaire
            else -> SurfaceHaute to Texte
        }
        drawRoundRect(fond, Offset(x0, bandeEffet), Size(x1 - x0 - 1f, hBande), CornerRadius(5.dp.toPx()))
        if (x1 - x0 > largeur(nom, petit) + 12.dp.toPx()) texte(nom, petit.copy(color = encre), x0 + 6.dp.toPx(), bandeEffet + 4.dp.toPx())
    }

    // Couleur envoyée à chaque lampe ; le stroboscope en blanc fixe, l'écran ne clignote jamais.
    for ((j, lampe) in ordre.withIndex()) {
        val ly = lampes0 + j * (hLampe + 4.dp.toPx())
        texte(lampe.name, petit, 12.dp.toPx(), ly + (hLampe - 15.sp.toPx()) / 2, gauche - 16.dp.toPx())
        drawRoundRect(Color(0xFF141417), Offset(gauche, ly), Size(pw, hLampe), CornerRadius(3.dp.toPx()))
        for (c in 0 until vue.cols) {
            val argb = vue.lampes[j][c]
            if (argb == 0 && !vue.strobe[c]) continue
            val col = if (vue.strobe[c]) Color.White.copy(alpha = 0.85f) else Color(argb)
            drawRect(col, Offset(gauche + c * colW, ly), Size(colW + 0.5f, hLampe))
        }
    }

    // La musique : le son au-dessus de l'axe, les basses en dessous.
    val milieu = (onde0 + onde1) / 2
    val demi = (onde1 - onde0) / 2
    texte("Son", petit, 12.dp.toPx(), milieu - demi / 2 - 8.dp.toPx())
    texte("Basses", petit, 12.dp.toPx(), milieu + demi / 2 - 8.dp.toPx())
    drawLine(Ligne2, Offset(gauche, milieu), Offset(droite, milieu), 1f)
    for (c in 0 until vue.cols) {
        val xc = gauche + c * colW
        val hn = vue.niveau[c].coerceIn(0f, 1f) * demi
        val hb = vue.basses[c].coerceIn(0f, 1f) * demi
        if (hn > 0.5f) drawRect(Secondaire.copy(alpha = 0.75f), Offset(xc, milieu - hn), Size(colW * 0.8f, hn))
        if (hb > 0.5f) drawRect(accent.copy(alpha = 0.9f), Offset(xc, milieu + 1f), Size(colW * 0.8f, hb))
    }

    // Temps et mesures : un trait par temps, le numéro de la mesure dans la phrase.
    texte("Mesure", petit, 12.dp.toPx(), grille + 1.dp.toPx())
    var finNumero = -1f
    for (r in vue.reperes) {
        if (r.kind != Timeline.Kind.BEAT) continue
        val xb = x(r.t)
        if (xb < gauche || xb > droite) continue
        val mesure = r.value % 4 == 0
        val phrase = r.value == 0
        val h = if (phrase) 16.dp.toPx() else if (mesure) 10.dp.toPx() else 4.dp.toPx()
        drawLine(if (phrase) Texte else if (mesure) Secondaire else Neutre, Offset(xb, grille + 16.dp.toPx() - h), Offset(xb, grille + 16.dp.toPx()), if (phrase) 2f else 1.5f)
        if (mesure && (fenetre <= 30.0 || phrase) && xb > finNumero) {
            val w = texte("${r.value / 4 + 1}", mono.copy(color = if (phrase) Texte else Tertiaire), xb + 3.dp.toPx(), grille)
            finNumero = xb + w + 6.dp.toPx()
        }
    }

    // Moment compris par l'analyse.
    texte("Moment", petit, 12.dp.toPx(), bandeMoment + 4.dp.toPx())
    val gris = listOf(Eteinte, Color(0xFF1F1F24), Color(0xFF2B2B32), Color(0xFF3C3C45))
    val fusion = ArrayList<Segment>()
    for (s in vue.segments) {
        val prec = fusion.lastOrNull()
        if (prec != null && momentDe(prec.mode) == momentDe(s.mode)) prec.t1 = s.t1
        else fusion += Segment(s.t0, s.t1, s.mode, -1)
    }
    for (s in fusion) {
        val x0 = max(gauche, x(s.t0))
        val x1 = min(droite, x(s.t1))
        if (x1 - x0 < 1f) continue
        val mo = momentDe(s.mode)
        val (fond, encre) = when (mo) {
            5 -> accent to Fond
            4 -> accent.copy(alpha = 0.4f) to Texte
            else -> gris[mo] to (if (mo == 0) Tertiaire else Texte)
        }
        drawRoundRect(fond, Offset(x0, bandeMoment), Size(x1 - x0 - 1f, hBande), CornerRadius(5.dp.toPx()))
        val nom = MOMENTS[mo]
        if (x1 - x0 > largeur(nom, petit) + 12.dp.toPx()) texte(nom, petit.copy(color = encre), x0 + 6.dp.toPx(), bandeMoment + 4.dp.toPx())
    }

    // Tension (montée vers un drop) et intensité (ce qui fait passer de calme à énergie).
    texte("Tension", petit.copy(color = accent), 12.dp.toPx(), courbes0 + 2.dp.toPx())
    texte("Intensité", petit, 12.dp.toPx(), courbes0 + hCourbes - 16.dp.toPx())
    drawRoundRect(Color(0xFF141417), Offset(gauche, courbes0), Size(pw, hCourbes), CornerRadius(4.dp.toPx()))
    fun courbe(v: FloatArray, couleur: Color, epaisseur: Float, remplir: Boolean) {
        val p = Path()
        var ouvert = false
        var dernierX = gauche
        for (c in 0 until vue.cols) {
            if (v[c] < 0f) continue
            val xc = gauche + (c + 0.5f) * colW
            val yc = courbes0 + hCourbes - 2f - v[c].coerceIn(0f, 1f) * (hCourbes - 4f)
            if (!ouvert) { p.moveTo(xc, yc); ouvert = true } else p.lineTo(xc, yc)
            dernierX = xc
        }
        if (!ouvert) return
        drawPath(p, couleur, style = Stroke(epaisseur))
        if (remplir) {
            p.lineTo(dernierX, courbes0 + hCourbes)
            p.lineTo(gauche, courbes0 + hCourbes)
            p.close()
            drawPath(p, couleur.copy(alpha = 0.16f))
        }
    }
    courbe(vue.intensite, Secondaire, 1.5.dp.toPx(), false)
    courbe(vue.tension, accent, 2.dp.toPx(), true)

    // Repères : drops, faux drops écartés, recalages de la phrase, nouveaux morceaux. Deux lignes d'étiquettes.
    texte("Repères", petit, 12.dp.toPx(), reperes0 + 2.dp.toPx())
    val finLigne = floatArrayOf(-1f, -1f)
    for (r in vue.reperes) {
        if (r.kind == Timeline.Kind.BEAT) continue
        val xr = x(r.t)
        if (xr < gauche - 2f || xr > droite + 2f) continue
        val (couleur, style) = when (r.kind) {
            Timeline.Kind.DROP -> accent to petit.copy(color = accent, fontWeight = FontWeight.Medium)
            Timeline.Kind.TRACK -> Texte to petit.copy(color = Texte)
            Timeline.Kind.NOT_DROP -> Secondaire to petit.copy(color = Secondaire)
            else -> Tertiaire to petit
        }
        when (r.kind) {
            Timeline.Kind.DROP -> drawLine(accent, Offset(xr, bandeEffet), Offset(xr, reperes0 + 30.dp.toPx()), 2.dp.toPx())
            Timeline.Kind.TRACK -> drawLine(
                Secondaire, Offset(xr, bandeEffet), Offset(xr, reperes0 + 30.dp.toPx()), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )
            Timeline.Kind.NOT_DROP -> drawCircle(Secondaire, 3.5.dp.toPx(), Offset(xr, reperes0 + 8.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            else -> drawLine(couleur, Offset(xr, reperes0), Offset(xr, reperes0 + 12.dp.toPx()), 1.5.dp.toPx())
        }
        val w = largeur(r.label, style)
        val lx = min(xr + 5.dp.toPx(), droite - w)
        val ligne = when {
            lx > finLigne[0] -> 0
            lx > finLigne[1] -> 1
            else -> -1
        }
        if (ligne < 0) continue
        texte(r.label, style, lx, reperes0 + ligne * 15.dp.toPx())
        finLigne[ligne] = lx + w + 6.dp.toPx()
    }

    // Axe du temps : en secondes avant le présent.
    val maintenant = if (vivant) "MAINTENANT" else "FIGÉ"
    val debutMaintenant = droite - largeur(maintenant, mono)
    texte(maintenant, mono.copy(color = Secondaire), debutMaintenant, axe + 5.dp.toPx())
    val pas = if (fenetre <= 30.0) 5.0 else 30.0
    var s = pas
    while (s < fenetre - 0.1) {
        val xs = x(fin - s)
        drawLine(Repere, Offset(xs, axe), Offset(xs, axe + 4.dp.toPx()), 1f)
        val lib = "-${s.roundToInt()} s"
        val w = largeur(lib, mono)
        if (xs + w / 2 < debutMaintenant - 8.dp.toPx()) texte(lib, mono, xs - w / 2, axe + 5.dp.toPx())
        s += pas
    }
    if (vivant) drawLine(Texte.copy(alpha = 0.35f), Offset(droite, bandeEffet), Offset(droite, reperes0 + 30.dp.toPx()), 1.dp.toPx())
}
