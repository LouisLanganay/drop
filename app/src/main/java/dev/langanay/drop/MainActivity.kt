package dev.langanay.drop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

// Identité de Drop : le noir pour le fond, la musique pour la couleur. La seule teinte de l'écran
// vient de la pochette en cours, celle que jouent les lampes ; tout le reste est en niveaux de gris.
internal val Fond = Color(0xFF0A0A0B)
internal val Surface = Color(0xFF121214)
internal val SurfaceHaute = Color(0xFF18181B)
internal val Plan = Color(0xFF101012)
internal val Ligne = Color(0xFF1F1F23)
internal val Ligne2 = Color(0xFF26262B)
internal val LigneActive = Color(0xFF3A3A40)
internal val Eteinte = Color(0xFF1C1C20)
internal val EteinteBord = Color(0xFF2E2E33)
internal val Repere = Color(0xFF2A2A30)
internal val Neutre = Color(0xFF4A4A52)
internal val Texte = Color(0xFFF4F4F5)
internal val Secondaire = Color(0xFFA1A1AA)
internal val Tertiaire = Color(0xFF8E8E96)
internal val Alerte = Color(0xFFFF8A80)

internal val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
)
internal val GeistMono = FontFamily(Font(R.font.geist_mono_medium, FontWeight.Medium))

/** Repères en capitales : zone, tempo, phrase, étapes. */
internal val Etiquette = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.9.sp)

internal fun corps(size: Int, weight: FontWeight = FontWeight.Normal, color: Color = Texte) =
    TextStyle(fontFamily = Geist, fontWeight = weight, fontSize = size.sp, color = color)

// Icônes au trait, dessinées ici pour ne pas embarquer une bibliothèque d'icônes.
internal fun trait(vararg d: String): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        d.forEach {
            addPath(
                addPathNodes(it), stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

internal val Goutte: ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        addPath(addPathNodes("M12 2.5C12 2.5 5 10.1 5 14.7a7 7 0 0 0 14 0C19 10.1 12 2.5 12 2.5Z"), fill = SolidColor(Color.White))
    }.build()
internal val Chevron = trait("M6 9l6 6 6-6")
internal val Eclair = trait("M13 2L4 14h7l-1 8 9-12h-7l1-8z")
internal val Coche = trait("M5 12l5 5L20 7")
internal val Micro = trait("M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z", "M19 11a7 7 0 0 1-14 0", "M12 18v3")
internal val Note = trait("M9 18V5l12-2v13", "M9 18a3 3 0 1 1-6 0a3 3 0 0 1 6 0z", "M21 16a3 3 0 1 1-6 0a3 3 0 0 1 6 0z")

/** Couleur de la pochette, éclaircie si besoin pour porter du texte noir et se lire sur le fond. */
internal fun accentDe(argb: Int): Color {
    if (argb == 0) return Texte
    var c = Color(argb)
    repeat(8) { if (c.luminance() < 0.3f) c = lerp(c, Color.White, 0.2f) }
    return c
}

/** Nom court et description de chaque figure, tirés des commentaires d'Effects. */
internal val FIGURES = mapOf(
    "Unisson" to ("Unisson" to "Toutes les lampes ensemble, accent sur le premier temps."),
    "Poursuite" to ("Poursuite" to "Une lampe à la fois autour du canapé, suivie d'une traîne."),
    "Ping-pong gauche-droite" to ("Ping-pong" to "Gauche sur un temps, droite sur le suivant, l'autre côté dans le noir."),
    "Balayage aller-retour" to ("Balayage" to "Un faisceau traverse la pièce de gauche à droite, puis revient."),
    "Couleurs croisées" to ("Couleurs croisées" to "Une lampe sur deux dans chaque couleur, échangées à chaque mesure."),
    "Noir et flash" to ("Noir et flash" to "Presque noir, et tout le groupe claque sur chaque grosse caisse."),
)

/** Repère, titre et phrase du moment affiché sous le plan. */
private fun decrire(s: LiveState): Triple<String, String, String> = when {
    s.status.startsWith("Connexion") -> Triple("Connexion", "Connexion au pont", "Les autres lampes de la pièce s'éteignent en fondu.")
    s.mode == "Montée" -> Triple("Montée", "Accélération", "La poursuite accélère, jusqu'à quatre lampes par temps, et blanchit avant le drop.")
    s.mode == "DROP" -> Triple("Drop", "Explosion", "Toutes les lampes à fond, en couleurs alternées qui changent à chaque temps.")
    s.mode == "Stroboscope" -> Triple("Drop", "Stroboscope", "Flash blanc sur toutes les lampes, de 2,5 à 4 secondes.")
    s.mode == "Calme" -> Triple("Calme", "Respiration", "Chaque lampe dans sa couleur, qui glisse lentement vers celle de sa voisine.")
    s.mode == "Silence" -> Triple("Silence", "En veille", "Les lampes restent en veilleuse jusqu'au retour de la musique.")
    else -> {
        val f = FIGURES[s.figure]
        Triple(s.mode.ifEmpty { "Écoute" }, f?.first ?: s.figure.ifEmpty { "Écoute" }, f?.second ?: "Drop se cale sur le tempo de la musique.")
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Fond, surface = Surface, primary = Texte, onPrimary = Fond)) {
                Box(Modifier.fillMaxSize().background(Fond).systemBarsPadding()) {
                    App(prefs)
                }
            }
        }
    }

    private fun micAccorde() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ouvrirAccesMorceau() = startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    @Composable
    private fun App(prefs: Prefs) {
        val ctx = this@MainActivity
        var paired by remember { mutableStateOf(prefs.paired) }
        var zoneId by remember { mutableStateOf(prefs.configId) }
        var micOk by remember { mutableStateOf(micAccorde()) }
        var morceauOk by remember { mutableStateOf(NowPlaying.enabled(ctx)) }
        // Première ouverture (ou pont oublié) : les trois étapes, jusqu'à « C'est prêt ».
        var setup by remember { mutableStateOf(!prefs.paired || prefs.configId == null || !micAccorde()) }
        var areas by remember { mutableStateOf<List<EntArea>>(emptyList()) }
        var lamps by remember { mutableStateOf<List<Lamp>>(emptyList()) }
        var bridgeErr by remember { mutableStateOf<String?>(null) }
        var analyse by remember { mutableStateOf(false) }

        val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { micOk = micAccorde() }
        LaunchedEffect(Unit) {
            // La notification du service d'emblée ; le micro à l'étape 3, ou ici si l'app est déjà prête.
            val ask = mutableListOf(Manifest.permission.POST_NOTIFICATIONS)
            if (!setup) ask += Manifest.permission.RECORD_AUDIO
            perms.launch(ask.toTypedArray())
        }
        LaunchedEffect(Unit) {
            // Les accès se donnent dans les réglages du téléphone : on les voit ici au retour, sans relancer l'app.
            while (true) {
                val now = NowPlaying.enabled(ctx)
                if (now) NowPlaying.start(ctx)
                morceauOk = now
                micOk = micAccorde()
                delay(if (now) 5000 else 1000)
            }
        }
        LaunchedEffect(paired) {
            if (!paired) {
                areas = emptyList()
                return@LaunchedEffect
            }
            val res = withContext(Dispatchers.IO) { runCatching { HueBridge(prefs.bridgeIp).areas(prefs.username!!) } }
            res.onSuccess { list ->
                areas = list
                bridgeErr = null
                if (!setup && (zoneId == null || list.none { it.id == zoneId })) {
                    zoneId = (list.firstOrNull { it.name == "Salon" } ?: list.firstOrNull())?.id
                    prefs.configId = zoneId
                }
            }.onFailure { bridgeErr = messageErreur(it) }
        }
        LaunchedEffect(areas, zoneId) {
            val area = areas.firstOrNull { it.id == zoneId } ?: return@LaunchedEffect
            lamps = area.channels.mapIndexed { i, c -> Lamp(c.id, "Lampe ${i + 1}", c.x, c.y, false) }
            val res = withContext(Dispatchers.IO) { runCatching { HueBridge(prefs.bridgeIp).lamps(prefs.username!!, area) } }
            res.onSuccess { lamps = it }
        }

        val choisirZone: (String) -> Unit = { id ->
            zoneId = id
            prefs.configId = id
        }
        if (setup) {
            SetupScreen(
                prefs, paired, onPaired = { paired = true },
                areas, zoneId, choisirZone,
                micOk, askMic = { perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) },
                morceauOk, bridgeErr,
            ) { setup = false }
        } else if (analyse) {
            AnalyseScreen(lamps) { analyse = false }
        } else {
            ShowScreen(prefs, areas, zoneId, choisirZone, lamps, morceauOk, bridgeErr, onAnalyse = { analyse = true }) {
                DropService.stop(ctx)
                prefs.username = null
                prefs.clientKey = null
                paired = false
                setup = true
            }
        }
    }

    // ─── Première ouverture ────────────────────────────────────────────────────

    @Composable
    private fun SetupScreen(
        prefs: Prefs, paired: Boolean, onPaired: () -> Unit,
        areas: List<EntArea>, zoneId: String?, onZone: (String) -> Unit,
        micOk: Boolean, askMic: () -> Unit, morceauOk: Boolean, bridgeErr: String?,
        onDone: () -> Unit,
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) { Logo() }
            Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Préparer le salon", style = corps(28, FontWeight.SemiBold).copy(letterSpacing = (-0.5).sp))
                Text("Trois étapes, une seule fois.", style = corps(15, color = Secondaire))
            }
            if (paired) EtapeFaite("Pont Hue", "Associé · ${prefs.bridgeIp}") else EtapePont(prefs, onPaired)
            Etape(2, "Zone de synchro", active = paired) {
                when {
                    !paired -> Text("Après l'association du pont.", style = corps(13, color = Secondaire))
                    bridgeErr != null -> Text(bridgeErr, style = corps(13, color = Alerte))
                    areas.isEmpty() -> Text("Lecture des zones du pont…", style = corps(13, color = Secondaire))
                    else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        areas.forEach { a -> LigneZone(a, a.id == zoneId, running = false) { onZone(a.id) } }
                    }
                }
            }
            Etape(3, "Micro et morceau en cours", active = paired && zoneId != null) {
                LigneAcces(Micro, "Micro", "Pour entendre le tempo et les drops.", micOk, askMic)
                LigneAcces(Note, "Morceau en cours", "Pour lire la pochette Spotify et le style.", morceauOk) { ouvrirAccesMorceau() }
            }
            Spacer(Modifier.height(4.dp))
            GrandBouton("C'est prêt", Texte, enabled = paired && zoneId != null && micOk, onClick = onDone)
            val reste = when {
                !paired -> "Commence par associer le pont."
                zoneId == null -> "Choisis la zone où jouer."
                !micOk -> "Il reste une autorisation."
                !morceauOk -> "Sans le morceau, les couleurs suivent le style."
                else -> null
            }
            reste?.let {
                Text(it, Modifier.fillMaxWidth().padding(bottom = 12.dp), style = corps(13, color = Secondaire).copy(textAlign = TextAlign.Center))
            }
        }
    }

    @Composable
    private fun EtapePont(prefs: Prefs, onPaired: () -> Unit) {
        val scope = rememberCoroutineScope()
        var ip by remember { mutableStateOf(prefs.bridgeIp) }
        var msg by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        Etape(1, "Pont Hue", active = true) {
            Text(
                "Appuie sur le bouton rond au centre du pont, puis touche Associer dans les 30 secondes.",
                style = corps(13, color = Secondaire).copy(lineHeight = 19.sp),
            )
            OutlinedTextField(
                value = ip, onValueChange = { ip = it }, singleLine = true,
                label = { Text("Adresse du pont", style = corps(13, color = Color.Unspecified)) },
                textStyle = corps(15), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Texte, unfocusedBorderColor = Ligne2, focusedLabelColor = Secondaire,
                    unfocusedLabelColor = Tertiaire, cursorColor = Texte, focusedTextColor = Texte, unfocusedTextColor = Texte,
                ),
            )
            PetitBouton(if (busy) "Association…" else "Associer", enabled = !busy) {
                busy = true
                msg = null
                scope.launch {
                    val res = withContext(Dispatchers.IO) { runCatching { HueBridge(ip.trim()).pair() } }
                    busy = false
                    res.onSuccess { (u, k) ->
                        prefs.bridgeIp = ip.trim(); prefs.username = u; prefs.clientKey = k
                        onPaired()
                    }.onFailure { msg = messageErreur(it) }
                }
            }
            msg?.let { Text(it, style = corps(13, color = Alerte)) }
        }
    }

    // ─── Le show ───────────────────────────────────────────────────────────────

    @Composable
    private fun ShowScreen(
        prefs: Prefs, areas: List<EntArea>, zoneId: String?, onZone: (String) -> Unit,
        lamps: List<Lamp>, morceauOk: Boolean, bridgeErr: String?, onAnalyse: () -> Unit, onUnpair: () -> Unit,
    ) {
        val ctx = this@MainActivity
        val state by DropService.live.collectAsState()
        val running = state.running
        val accent = if (running) accentDe(state.lead) else Texte
        var sheet by remember { mutableStateOf(false) }
        val area = areas.firstOrNull { it.id == zoneId }
        Column(
            Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Logo()
                Spacer(Modifier.weight(1f))
                BoutonAnalyse(onAnalyse)
                Spacer(Modifier.width(8.dp))
                if (running) EnDirect(state.area.ifEmpty { area?.name.orEmpty() }, accent, state.beatInBar)
                else BoutonZone(area) { sheet = true }
            }
            PlanSalon(state.area.ifEmpty { area?.name ?: "Zone" }, lamps, state, Modifier.fillMaxWidth().weight(1f))
            if (running) {
                Moment(state, accent)
                Compteurs(state, accent)
            } else {
                Statut(state.error ?: bridgeErr)
            }
            EnLecture(compact = running, morceauOk = morceauOk)
            Stroboscope(prefs, running)
            GrandBouton(if (running) "Stop" else "Go", accent) {
                if (running) DropService.stop(ctx) else DropService.start(ctx)
            }
        }
        if (sheet) {
            FeuilleZone(
                areas, zoneId, running,
                onZone = { onZone(it); sheet = false },
                onUnpair = { sheet = false; onUnpair() },
            ) { sheet = false }
        }
    }

    @Composable
    private fun EnLecture(compact: Boolean, morceauOk: Boolean) {
        val track by NowPlaying.track.collectAsState()
        val t = track
        val contenu: @Composable RowScope.() -> Unit = {
            Pochette(t, if (compact) 40.dp else 52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp)) {
                val (titre, sous) = when {
                    !morceauOk -> "Morceau inconnu" to "Autorise Drop à lire le morceau en cours."
                    t == null -> "Rien en lecture" to "Lance un morceau sur Spotify."
                    else -> t.title to listOfNotNull(t.artist.takeIf { it.isNotBlank() }, t.genre).joinToString(" · ")
                }
                Text(titre, style = corps(if (compact) 14 else 15, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sous, style = corps(if (compact) 12 else 13, color = Secondaire), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!morceauOk) PetitBouton("Autoriser") { ouvrirAccesMorceau() }
            else if (!compact && t != null) Text("EN LECTURE", style = Etiquette.copy(color = Tertiaire))
        }
        if (compact) {
            Row(
                Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp), content = contenu,
            )
        } else {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface)
                    .border(1.dp, Ligne, RoundedCornerShape(16.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), content = contenu,
            )
        }
    }
}

// ─── Morceaux d'écran ──────────────────────────────────────────────────────────

@Composable
private fun Logo() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Goutte, contentDescription = null, tint = Texte, modifier = Modifier.size(20.dp))
        Text("Drop", style = corps(19, FontWeight.SemiBold).copy(letterSpacing = (-0.4).sp))
    }
}

@Composable
private fun BoutonZone(area: EntArea?, onClick: () -> Unit) {
    val n = area?.channels?.size ?: 0
    Row(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(Surface)
            .border(1.dp, Ligne2, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "Changer de zone", onClick = onClick)
            .padding(start = 14.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (area == null) "Choisir la zone" else "${area.name} · $n lampe${if (n > 1) "s" else ""}",
            style = corps(14, FontWeight.Medium),
        )
        Icon(Chevron, contentDescription = null, tint = Secondaire, modifier = Modifier.size(16.dp))
    }
}

/** Ouvre l'analyse en direct : la musique, ce que Drop en comprend et ce que jouent les lampes. */
@Composable
private fun BoutonAnalyse(onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Surface).border(1.dp, Ligne2, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "Ouvrir l'analyse en direct", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Courbe, contentDescription = "Analyse en direct", tint = Texte, modifier = Modifier.size(20.dp)) }
}

/** Pastille « En direct », dont le point bat les temps. */
@Composable
private fun EnDirect(zone: String, accent: Color, temps: Int) {
    val a by animateFloatAsState(if (temps % 2 == 1) 1f else 0.45f, tween(140), label = "pouls")
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(10.dp)).background(Surface)
            .border(1.dp, Ligne, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(accent.copy(alpha = a)))
        Text(if (zone.isEmpty()) "En direct" else "En direct · $zone", style = corps(13))
    }
}

/**
 * Plan de la zone vu de dessus : le canapé à l'origine des positions du pont (c'est là qu'on écoute),
 * l'avant de la pièce en haut, et chaque lampe dans la couleur qu'elle joue. Le plan garde les proportions
 * de la pièce : même échelle sur les deux axes, étirée en hauteur d'au plus moitié pour occuper le cadre.
 * Pendant le stroboscope les lampes restent blanches à l'écran : le téléphone ne clignote jamais.
 */
@Composable
private fun PlanSalon(zone: String, lamps: List<Lamp>, state: LiveState, modifier: Modifier) {
    val strobe = state.running && state.mode == "Stroboscope"
    val n = lamps.size
    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(20.dp)).background(Plan).border(1.dp, Ligne, RoundedCornerShape(20.dp))
            .semantics { contentDescription = "Plan de la zone $zone, $n lampes" },
    ) {
        val w = maxWidth
        val h = maxHeight
        Text(zone.uppercase(), Modifier.offset(16.dp, 14.dp), style = Etiquette.copy(color = Tertiaire))
        Text(
            "$n LAMPE${if (n > 1) "S" else ""}", Modifier.align(Alignment.TopEnd).offset((-16).dp, 14.dp),
            style = Etiquette.copy(color = Tertiaire),
        )
        // Emprise des lampes et du canapé (environ 0,6 sur 0,16 autour de l'origine), en coordonnées du pont.
        val xs = lamps.map { it.x.coerceIn(-1f, 1f) } + listOf(-0.3f, 0.3f)
        val ys = lamps.map { it.y.coerceIn(-1f, 1f) } + listOf(-0.08f, 0.08f)
        val minX = xs.min()
        val maxX = xs.max()
        val minY = ys.min()
        val maxY = ys.max()
        val left = 56.dp
        val top = 64.dp
        val plotW = w - 112.dp
        val plotH = h - top - 44.dp
        val sx0 = plotW / max(maxX - minX, 0.2f)
        val sy0 = plotH / max(maxY - minY, 0.2f)
        val base = minOf(sx0, sy0)
        val sx = minOf(sx0, base * 1.5f)
        val sy = minOf(sy0, base * 1.5f)
        val ox = left + (plotW - sx * (maxX - minX)) / 2
        val oy = top + (plotH - sy * (maxY - minY)) / 2
        fun px(x: Float) = ox + sx * (x.coerceIn(-1f, 1f) - minX)
        fun py(y: Float) = oy + sy * (maxY - y.coerceIn(-1f, 1f))
        val cx0 = px(0f)
        val cy0 = py(0f)
        Box(
            Modifier.offset(cx0 - 56.dp, cy0 - 22.dp).size(112.dp, 44.dp).clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF141417)).border(1.dp, EteinteBord, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("Canapé", style = corps(12, color = Tertiaire)) }
        lamps.forEach { lamp ->
            val cx = px(lamp.x)
            val cy = py(lamp.y)
            val couleur = when {
                !state.running -> null
                strobe -> Color.White
                else -> state.lamps[lamp.channel]?.let { Color(it) }
            }
            // Une lampe collée au canapé porte son nom au-dessus, pour ne pas écrire dessus.
            val pres = (cx - cx0).value.let { kotlin.math.abs(it) } < 56f + 52f && (cy - cy0).value.let { kotlin.math.abs(it) } < 48f
            Lampe(lamp, couleur, cx, cy, nomDessus = pres)
        }
    }
}

@Composable
private fun Lampe(lamp: Lamp, cible: Color?, cx: Dp, cy: Dp, nomDessus: Boolean = false) {
    val c by animateColorAsState(cible ?: Eteinte, tween(110), label = "lampe")
    val force = max(c.red, max(c.green, c.blue))
    val allumee = cible != null && force > 0.08f
    val lw = if (lamp.strip) 150.dp else 20.dp
    val lh = if (lamp.strip) 8.dp else 20.dp
    Box(
        Modifier.offset(cx - lw / 2, cy - lh / 2).size(lw, lh)
            .drawBehind {
                if (allumee) {
                    val r = size.maxDimension * (if (lamp.strip) 0.6f else 1.4f) + 18.dp.toPx()
                    drawCircle(
                        Brush.radialGradient(listOf(c.copy(alpha = 0.5f * force), Color.Transparent), center = center, radius = r),
                        radius = r, center = center,
                    )
                }
            }
            .clip(RoundedCornerShape(10.dp))
            .background(if (allumee) c else Eteinte)
            .border(1.dp, if (allumee) Color.Transparent else EteinteBord, RoundedCornerShape(10.dp)),
    )
    Text(
        lamp.name, Modifier.offset(cx - 52.dp, if (nomDessus) cy - lh / 2 - 22.dp else cy + lh / 2 + 6.dp).width(104.dp),
        style = corps(12, color = if (allumee) Texte else Tertiaire).copy(textAlign = TextAlign.Center),
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** Ce qui se passe : le moment de la musique, la figure, et ce qu'elle fait aux lampes. */
@Composable
private fun Moment(state: LiveState, accent: Color) {
    val (etiquette, titre, texte) = decrire(state)
    val fort = state.mode == "Montée" || state.mode == "DROP" || state.mode == "Stroboscope"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(etiquette.uppercase(), style = Etiquette.copy(color = if (fort) accent else Tertiaire))
        Text(
            titre, style = corps(30, FontWeight.SemiBold).copy(letterSpacing = (-0.6).sp, lineHeight = 33.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            texte, style = corps(13, color = Secondaire).copy(lineHeight = 19.sp),
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Tempo à gauche ; à droite la phrase de 8 mesures, la tension pendant une montée, les drops après un drop. */
@Composable
private fun Compteurs(state: LiveState, accent: Color) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(16.dp)).background(Surface)
            .border(1.dp, Ligne, RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("TEMPO", style = Etiquette.copy(color = Tertiaire))
            Chiffre(if (state.bpm > 0f) state.bpm.roundToInt().toString() else "--", "BPM")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 1..4) Box(Modifier.size(8.dp).clip(CircleShape).background(if (i == state.beatInBar) accent else Repere))
                Text("Temps ${state.beatInBar.coerceIn(1, 4)}", Modifier.padding(start = 4.dp), style = corps(12, color = Secondaire))
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Ligne))
        Column(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state.mode) {
                "Montée" -> {
                    val t = state.tension.coerceIn(0f, 1f)
                    Text("TENSION", style = Etiquette.copy(color = Tertiaire))
                    Chiffre("${(t * 100).roundToInt()}", "%")
                    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Repere)) {
                        Box(Modifier.fillMaxWidth(t).height(4.dp).background(accent))
                    }
                }
                "DROP", "Stroboscope" -> {
                    Text("DROPS", style = Etiquette.copy(color = Tertiaire))
                    Chiffre("${state.drops}", "depuis Go")
                    Text(
                        if (state.lastStrobe > 0f) "Stroboscope : ${String.format(Locale.FRANCE, "%.1f", state.lastStrobe)} s" else "Sans stroboscope",
                        style = corps(12, color = Secondaire),
                    )
                }
                else -> {
                    val bar = state.barInPhrase.coerceIn(1, 8)
                    Text("PHRASE", style = Etiquette.copy(color = Tertiaire))
                    Chiffre("$bar/8", "mesures")
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (i in 1..8) {
                            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (i <= bar) accent else Repere))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chiffre(valeur: String, unite: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            valeur,
            style = TextStyle(
                fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 34.sp,
                letterSpacing = (-1.2).sp, lineHeight = 36.sp, color = Texte,
            ),
        )
        Text(unite, Modifier.padding(bottom = 4.dp), style = corps(13, color = Secondaire))
    }
}

@Composable
private fun Statut(erreur: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (erreur != null) Alerte else Neutre))
        Text(
            erreur ?: "Prêt. Le micro n'écoute qu'après Go.",
            style = corps(13, color = if (erreur != null) Alerte else Secondaire), maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Pochette réelle, sinon ses couleurs en damier, sinon une note. */
@Composable
private fun Pochette(t: Track?, taille: Dp) {
    val forme = RoundedCornerShape(if (taille > 44.dp) 10.dp else 8.dp)
    val art = t?.art
    when {
        art != null -> Image(
            art.asImageBitmap(), contentDescription = "Pochette", contentScale = ContentScale.Crop,
            modifier = Modifier.size(taille).clip(forme),
        )
        t != null && t.colors.isNotEmpty() -> Column(Modifier.size(taille).clip(forme)) {
            val cs = List(4) { i -> t.colors[i % t.colors.size].let { Color(it[0], it[1], it[2]) } }
            Row(Modifier.weight(1f)) {
                Box(Modifier.weight(1f).fillMaxHeight().background(cs[0]))
                Box(Modifier.weight(1f).fillMaxHeight().background(cs[1]))
            }
            Row(Modifier.weight(1f)) {
                Box(Modifier.weight(1f).fillMaxHeight().background(cs[2]))
                Box(Modifier.weight(1f).fillMaxHeight().background(cs[3]))
            }
        }
        else -> Box(Modifier.size(taille).clip(forme).background(SurfaceHaute), contentAlignment = Alignment.Center) {
            Icon(Note, contentDescription = null, tint = Tertiaire, modifier = Modifier.size(taille / 2.5f))
        }
    }
}

/** Le seul réglage de l'app, avec le bouton de test pendant le show. */
@Composable
private fun Stroboscope(prefs: Prefs, running: Boolean) {
    var on by remember { mutableStateOf(prefs.strobeOn) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface).border(1.dp, Ligne, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = 8.dp, top = if (running) 6.dp else 10.dp, bottom = if (running) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (running) 8.dp else 12.dp),
    ) {
        Icon(Eclair, contentDescription = null, tint = Secondaire, modifier = Modifier.size(18.dp))
        if (running) {
            Text("Stroboscope au drop", Modifier.weight(1f), style = corps(14, FontWeight.Medium))
            ContourBouton("Tester") { DropService.testStrobe() }
        } else {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Stroboscope au drop", style = corps(15, FontWeight.Medium))
                Text(
                    "2,5 à 4 s de flashs. Déconseillé aux personnes photosensibles.",
                    style = corps(12, color = Secondaire).copy(lineHeight = 16.sp),
                )
            }
        }
        Switch(
            checked = on,
            onCheckedChange = {
                on = it
                prefs.strobeOn = it
            },
            modifier = Modifier.semantics { contentDescription = "Stroboscope au drop" },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Fond, checkedTrackColor = Texte, checkedBorderColor = Texte,
                uncheckedThumbColor = Secondaire, uncheckedTrackColor = Surface, uncheckedBorderColor = Neutre,
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeuilleZone(
    areas: List<EntArea>, zoneId: String?, running: Boolean,
    onZone: (String) -> Unit, onUnpair: () -> Unit, onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = Surface, contentColor = Texte,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Neutre) },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Zone de synchro", Modifier.padding(bottom = 6.dp), style = corps(18, FontWeight.SemiBold))
            if (areas.isEmpty()) Text("Aucune zone lue sur le pont.", style = corps(13, color = Secondaire))
            areas.forEach { a -> LigneZone(a, a.id == zoneId, running) { onZone(a.id) } }
            Text(
                "« Occupée » : une autre app (Hue Sync, iLightShow) pilote déjà cette zone, arrête-la avant.",
                Modifier.padding(top = 4.dp), style = corps(12, color = Secondaire).copy(lineHeight = 16.sp),
            )
            TextButton(onClick = onUnpair, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("Oublier ce pont", style = corps(14, color = Secondaire))
            }
        }
    }
}

@Composable
private fun Etape(numero: Int, titre: String, active: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface)
            .border(1.dp, if (active) LigneActive else Ligne, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(28.dp).border(1.dp, if (active) Texte else Neutre, CircleShape), contentAlignment = Alignment.Center) {
                Text(
                    "$numero",
                    style = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = if (active) Texte else Secondaire),
                )
            }
            Text(titre, style = corps(15, FontWeight.SemiBold))
        }
        content()
    }
}

@Composable
private fun EtapeFaite(titre: String, detail: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface).border(1.dp, Ligne, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Texte), contentAlignment = Alignment.Center) {
            Icon(Coche, contentDescription = null, tint = Fond, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titre, style = corps(15, FontWeight.SemiBold))
            Text(detail, style = corps(13, color = Secondaire))
        }
        Text("FAIT", style = Etiquette.copy(color = Tertiaire))
    }
}

@Composable
private fun LigneZone(a: EntArea, selected: Boolean, running: Boolean, onClick: () -> Unit) {
    val n = a.channels.size
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) SurfaceHaute else Color.Transparent)
            .border(1.dp, if (selected) Texte else Ligne2, RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(16.dp).border(if (selected) 5.dp else 1.dp, if (selected) Texte else Neutre, CircleShape))
        Text(a.name, Modifier.weight(1f), style = corps(15, FontWeight.Medium))
        Text(
            "$n lampe${if (n > 1) "s" else ""}${if (a.active && !running) " · occupée" else ""}",
            style = corps(13, color = Secondaire),
        )
    }
}

@Composable
private fun LigneAcces(icone: ImageVector, titre: String, texte: String, ok: Boolean, demander: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icone, contentDescription = null, tint = Secondaire, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titre, style = corps(14, FontWeight.Medium))
            Text(texte, style = corps(12, color = Secondaire))
        }
        if (ok) Text("AUTORISÉ", style = Etiquette.copy(color = Tertiaire)) else PetitBouton("Autoriser", onClick = demander)
    }
}

@Composable
private fun GrandBouton(label: String, couleur: Color, enabled: Boolean = true, onClick: () -> Unit) {
    val fond by animateColorAsState(couleur, tween(400), label = "bouton")
    Button(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = fond, contentColor = Fond, disabledContainerColor = Eteinte, disabledContentColor = Tertiaire,
        ),
    ) {
        Text(label, style = corps(20, FontWeight.SemiBold, color = Color.Unspecified).copy(letterSpacing = (-0.2).sp))
    }
}

@Composable
private fun PetitBouton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = Modifier.height(44.dp), shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Texte, contentColor = Fond, disabledContainerColor = Eteinte, disabledContentColor = Tertiaire,
        ),
    ) {
        Text(label, style = corps(14, FontWeight.SemiBold, color = Color.Unspecified))
    }
}

@Composable
private fun ContourBouton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.height(44.dp), shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 14.dp), border = BorderStroke(1.dp, Repere),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Texte, disabledContentColor = Tertiaire),
    ) {
        Text(label, style = corps(14, FontWeight.Medium, color = Color.Unspecified))
    }
}
