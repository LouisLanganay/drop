package dev.langanay.drop

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
internal fun PadsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val act = ctx as? Activity
        val avant = act?.requestedOrientation
        act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            Pads.noir = false; Pads.flash = false; Pads.strobe = false
            act?.requestedOrientation = avant ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    BackHandler(onBack = onBack)
    val state by DropService.live.collectAsState()
    val track by NowPlaying.track.collectAsState()
    val accent = if (state.running) accentDe(state.lead) else Texte
    var figure by remember { mutableStateOf(Pads.figure) }
    var couleur by remember { mutableStateOf(Pads.couleur) }

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
            val manuel = figure != null || couleur != null
            Box(
                Modifier.height(44.dp).width(120.dp)
                    .verre(22.dp, teinte = if (manuel) Color.White.copy(alpha = 0.06f) else accent.copy(alpha = 0.85f))
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(role = Role.Button, onClickLabel = "Rendre la main à l'analyse") {
                        Pads.auto(); figure = null; couleur = null
                    },
                contentAlignment = Alignment.Center,
            ) { Text("Auto", style = corps(16, FontWeight.SemiBold, color = if (manuel) Texte else Fond)) }
        }

        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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

/** Pad qui reste allumé jusqu'au prochain appui. */
@Composable
private fun PadBascule(nom: String, actif: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier.fillMaxHeight()
            .verre(20.dp, teinte = if (actif) accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.06f))
            .clip(RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClickLabel = nom) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        contentAlignment = Alignment.Center,
    ) { Etiquettes(nom, if (actif) "verrouillée" else "figure", if (actif) Fond else Texte) }
}

/** Pad qui déclenche une fois, avec un éclair de la couleur du morceau au toucher. */
@Composable
private fun PadCoup(nom: String, sous: String, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var appuye by remember { mutableStateOf(false) }
    Box(
        modifier.fillMaxHeight()
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
