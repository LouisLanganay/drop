package dev.langanay.drop

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val Retour = trait("M15 6l-6 6 6 6")
internal val Grille = trait("M4 4h6v6H4z", "M14 4h6v6h-6z", "M4 14h6v6H4z", "M14 14h6v6h-6z")

private val FIGURES_PADS = listOf(
    Figure.UNISON to "Unisson", Figure.CHASE to "Poursuite", Figure.PINGPONG to "Ping-pong",
    Figure.SWEEP to "Balayage", Figure.CROSS to "Croisées", Figure.HITS to "Noir et flash",
)

/**
 * Light jockey : des pads pour jouer les lumières à la main pendant le show, en paysage. À gauche ce qui se
 * maintient (noir, stroboscope, flash), au centre les figures et les déclencheurs (drop, montée), à droite les
 * couleurs de la pochette. Auto rend tout à l'analyse.
 */
@Composable
internal fun PadsScreen(lamps: List<Lamp>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val act = ctx as? Activity
        val avant = act?.requestedOrientation
        act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            Pads.noir = false; Pads.flash = false; Pads.strobe = false
            Pads.gauche = -1f; Pads.droite = -1f; Pads.devant = -1f; Pads.derriere = -1f
            act?.requestedOrientation = avant ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    BackHandler(onBack = onBack)
    val state by DropService.live.collectAsState()
    val track by NowPlaying.track.collectAsState()
    val accent = if (state.running) accentDe(state.lead) else Texte
    var figure by remember { mutableStateOf(Pads.figure) }
    var couleur by remember { mutableStateOf(Pads.couleur) }
    var avance by remember { mutableStateOf(Pads.avance) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(44.dp).verre(22.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Revenir au show", onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Icon(Retour, contentDescription = "Retour", tint = Texte, modifier = Modifier.size(22.dp)) }
            Text("Pads", style = corps(19, FontWeight.SemiBold).copy(letterSpacing = (-0.4).sp))
            Spacer(Modifier.width(6.dp))
            if (state.running) {
                for (i in 1..4) Box(Modifier.size(9.dp).clip(CircleShape).background(if (i == state.beatInBar) accent else Repere))
                Text(
                    "${state.bpm.roundToInt()} BPM · ${state.barInPhrase.coerceIn(1, 8)}/8 · ${state.mode} ${state.figure.let { FIGURES[it]?.first ?: it }}",
                    Modifier.weight(1f), style = Etiquette.copy(color = Secondaire), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text("Lance Go sur l'écran du show : les pads jouent pendant le show.", Modifier.weight(1f), style = corps(13, color = Alerte), maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Avancé", style = corps(14, FontWeight.Medium))
                Switch(
                    checked = avance,
                    onCheckedChange = { avance = it; Pads.avance = it },
                    modifier = Modifier.semantics { contentDescription = "Mode avancé" },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Fond, checkedTrackColor = accent, checkedBorderColor = accent,
                        uncheckedThumbColor = Secondaire, uncheckedTrackColor = Color.White.copy(alpha = 0.06f), uncheckedBorderColor = Neutre,
                    ),
                )
            }
            val manuel = avance || figure != null || couleur != null
            Box(
                Modifier.height(44.dp).width(120.dp)
                    .verre(22.dp, teinte = if (manuel) Color.White.copy(alpha = 0.06f) else accent.copy(alpha = 0.85f))
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(role = Role.Button, onClickLabel = "Rendre la main à l'analyse") {
                        Pads.auto(); figure = null; couleur = null; avance = false
                    },
                contentAlignment = Alignment.Center,
            ) { Text("Auto", style = corps(16, FontWeight.SemiBold, color = if (manuel) Texte else Fond)) }
        }

        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (avance) {
                // Fader du fond, lampes au doigt, côtés et coup, puis figures, vitesse et déclencheurs.
                Fader(Modifier.width(56.dp).fillMaxHeight(), accent)
                Column(Modifier.weight(2.4f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.weight(1.5f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PadGroupe("Gauche", accent, Modifier.weight(1f)) { Pads.gauche = it }
                        PadGroupe("Droite", accent, Modifier.weight(1f)) { Pads.droite = it }
                    }
                    Row(Modifier.weight(1.5f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PadGroupe("Devant", accent, Modifier.weight(1f)) { Pads.devant = it }
                        PadGroupe("Derrière", accent, Modifier.weight(1f)) { Pads.derriere = it }
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PadTap(accent, state.bpm, Modifier.weight(1f))
                        PadCoup("Hit", "un coup", accent, Modifier.weight(1f)) { Pads.hit = true }
                    }
                }
                Column(Modifier.weight(1.8f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (ligne in FIGURES_PADS.chunked(3)) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for ((f, nom) in ligne) {
                                PadBascule(nom, figure == f, accent, Modifier.weight(1f)) {
                                    figure = if (figure == f) null else f
                                    Pads.figure = figure
                                }
                            }
                        }
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Vitesse(Modifier.weight(1f), accent)
                        PadCoup("Montée", "8 mesures", accent, Modifier.weight(1f)) { Pads.montee = true }
                        PadCoup("Drop", "explosion", accent, Modifier.weight(1f)) { Pads.drop = true }
                    }
                }
                Column(Modifier.weight(0.8f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PadMaintenu("Noir", "maintenir", Color(0xFF26262C), Modifier.weight(1f)) { Pads.noir = it }
                    PadMaintenu("Strobo", "maintenir", Color.White, Modifier.weight(1f)) { Pads.strobe = it }
                    PadCoup("Flash", "un coup", Color.White, Modifier.weight(1f)) { Pads.flashCoup = true }
                }
            } else {
            // Maintenus.
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PadMaintenu("Noir", "maintenir", Color(0xFF26262C), Modifier.weight(1f)) { Pads.noir = it }
                    PadMaintenu("Strobo", "maintenir", Color.White, Modifier.weight(1f)) { Pads.strobe = it }
                    PadMaintenu("Flash", "maintenir", Color.White, Modifier.weight(1f)) { Pads.flash = it }
                }
                // Figures et déclencheurs.
                Column(Modifier.weight(2.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (ligne in FIGURES_PADS.chunked(3)) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for ((f, nom) in ligne) {
                                PadBascule(nom, figure == f, accent, Modifier.weight(1f)) {
                                    figure = if (figure == f) null else f
                                    Pads.figure = figure
                                }
                            }
                        }
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PadCoup("Montée", "8 mesures", accent, Modifier.weight(1f)) { Pads.montee = true }
                        PadCoup("Drop", "explosion", accent, Modifier.weight(1f)) { Pads.drop = true }
                    }
                }
            }
            // Couleurs de la pochette, puis le blanc.
            val teintes = (track?.colors.orEmpty().take(5) + listOf(floatArrayOf(1f, 1f, 1f)))
            Column(Modifier.weight(0.8f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in teintes) {
                    val col = Color(c[0], c[1], c[2])
                    val choisi = couleur?.contentEquals(c) == true
                    Box(
                        Modifier.fillMaxWidth().weight(1f)
                            .verre(16.dp, teinte = col.copy(alpha = if (choisi) 0.95f else 0.55f))
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(role = Role.Button, onClickLabel = "Verrouiller cette couleur") {
                                couleur = if (choisi) null else c
                                Pads.couleur = couleur
                            },
                        contentAlignment = Alignment.Center,
                    ) { if (choisi) Text("VERROU", style = Etiquette.copy(color = Fond)) }
                }
            }
        }
    }
}

/** Pad actif tant que le doigt reste dessus. */
@Composable
private fun PadMaintenu(nom: String, sous: String, couleur: Color, modifier: Modifier, onChange: (Boolean) -> Unit) {
    val haptic = LocalHapticFeedback.current
    var appuye by remember { mutableStateOf(false) }
    Box(
        modifier.fillMaxWidth()
            .verre(20.dp, teinte = if (appuye) couleur.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.06f))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    appuye = true
                    onChange(true)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    tryAwaitRelease()
                    appuye = false
                    onChange(false)
                })
            },
        contentAlignment = Alignment.Center,
    ) { Etiquettes(nom, sous, if (appuye && couleur.red + couleur.green + couleur.blue > 1.5f) Fond else Texte) }
}

/**
 * Groupe de lampes tenu au doigt : plein à l'appui, glisser vers le bas baisse sa luminosité (jusqu'au noir), lâcher la rend
 * à la figure. Chaque appui repart de 100 %. -1 = relâché.
 */
@Composable
private fun PadGroupe(nom: String, couleur: Color, modifier: Modifier, onChange: (Float) -> Unit) {
    val haptic = LocalHapticFeedback.current
    var niveau by remember { mutableStateOf<Float?>(null) }
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(20.dp))
            .verre(20.dp, teinte = if (niveau != null) couleur.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.06f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val y0 = down.position.y
                    niveau = 1f; onChange(1f)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        val v = (1f - (c.position.y - y0) / (size.height * 0.8f)).coerceIn(0f, 1f)
                        if (v != niveau) { niveau = v; onChange(v) }
                    }
                    niveau = null; onChange(-1f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val v = niveau
        if (v != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(v).background(couleur.copy(alpha = 0.75f)))
        Etiquettes(nom, if (v != null) "${(v * 100).roundToInt()} %" else "glisser ↓ pour baisser", if (v != null && v > 0.5f && couleur.red + couleur.green + couleur.blue > 1.5f) Fond else Texte)
    }
}

/**
 * Tap tempo : taper en rythme sur au moins deux temps fixe le BPM (moyenne des derniers intervalles) et recale le
 * temps sur chaque tap. Auto rend le tempo à l'analyse.
 */
@Composable
private fun PadTap(accent: Color, bpm: Float, modifier: Modifier) {
    val haptic = LocalHapticFeedback.current
    val taps = remember { ArrayDeque<Long>() }
    var appuye by remember { mutableStateOf(false) }
    Box(
        modifier.fillMaxHeight()
            .verre(20.dp, teinte = if (appuye) accent.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.06f))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    appuye = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val t = System.nanoTime()
                    if (taps.isNotEmpty() && t - taps.last() > 2_000_000_000L) taps.clear()
                    taps.addLast(t)
                    while (taps.size > 8) taps.removeFirst()
                    if (taps.size >= 2) {
                        val bpmTap = 60f * (taps.size - 1) / ((taps.last() - taps.first()) / 1e9f)
                        if (bpmTap in 60f..200f) Pads.tapBpm = bpmTap
                    }
                    Pads.tapSync = true
                    tryAwaitRelease()
                    appuye = false
                })
            },
        contentAlignment = Alignment.Center,
    ) { Etiquettes("Tap", if (Pads.tapBpm > 0f) "${bpm.roundToInt()} bpm fixé" else "taper le tempo", if (appuye) Fond else Texte) }
}

/** Pad qui reste allumé jusqu'au prochain appui. */
@Composable
private fun PadBascule(nom: String, actif: Boolean, accent: Color, modifier: Modifier, sous: String? = null, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier.fillMaxHeight()
            .verre(20.dp, teinte = if (actif) accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.06f))
            .clip(RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClickLabel = nom) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        contentAlignment = Alignment.Center,
    ) { Etiquettes(nom, sous ?: if (actif) "verrouillée" else "figure", if (actif) Fond else Texte) }
}

/** Pad qui déclenche une fois, avec un éclair de la couleur du morceau au toucher. */
@Composable
private fun PadCoup(nom: String, sous: String, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var appuye by remember { mutableStateOf(false) }
    Box(
        modifier.fillMaxSize()
            .verre(20.dp, teinte = if (appuye) accent.copy(alpha = 0.95f) else accent.copy(alpha = 0.22f))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    appuye = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                    tryAwaitRelease()
                    appuye = false
                })
            },
        contentAlignment = Alignment.Center,
    ) { Etiquettes(nom, sous, if (appuye) Fond else Texte) }
}

@Composable
private fun Etiquettes(nom: String, sous: String, encre: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(nom, style = corps(17, FontWeight.SemiBold, color = encre).copy(textAlign = TextAlign.Center), maxLines = 1)
        Text(sous.uppercase(), style = Etiquette.copy(color = encre.copy(alpha = 0.7f), fontSize = 9.sp))
    }
}

/** Fader vertical du niveau du fond : glisser vers le haut pour monter, de noir à plein. */
@Composable
private fun Fader(modifier: Modifier, accent: Color) {
    var v by remember { mutableStateOf(Pads.niveau) }
    Box(
        modifier.verre(20.dp, lentille = false)
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, drag ->
                    change.consume()
                    v = (v - drag / size.height).coerceIn(0f, 1f)
                    Pads.niveau = v
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(v).clip(RoundedCornerShape(20.dp)).background(accent.copy(alpha = 0.55f)))
        Text("${(v * 100).roundToInt()}", Modifier.align(Alignment.TopCenter).padding(top = 10.dp), style = Etiquette.copy(color = Texte))
        Text("FOND", Modifier.padding(bottom = 10.dp), style = Etiquette.copy(color = Texte, fontSize = 9.sp))
    }
}

/** Vitesse des figures : un appui passe de normale à double puis quadruple (pas par temps), et revient. */
@Composable
private fun Vitesse(modifier: Modifier, accent: Color) {
    var v by remember { mutableStateOf(Pads.vitesse) }
    val nom = when (v) { 2 -> "Double"; 4 -> "Quadruple"; else -> "Normale" }
    PadBascule(nom, v > 1, accent, modifier, sous = "vitesse figures") {
        v = when (v) { 1 -> 2; 2 -> 4; else -> 1 }; Pads.vitesse = v
    }
}
