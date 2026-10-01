package dev.langanay.drop

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

private val Retour = trait("M15 6l-6 6 6 6")
internal val Curseurs = trait("M4 7h9", "M17 7h3", "M15 5v4", "M4 17h3", "M11 17h9", "M9 15v4")

/** Il y a combien de temps, en clair. */
private fun ilYa(ms: Long): String {
    val s = (System.currentTimeMillis() - ms) / 1000
    return when {
        s < 60 -> "à l'instant"
        s < 3600 -> "il y a ${s / 60} min"
        s < 86400 -> "il y a ${s / 3600} h"
        else -> "il y a ${s / 86400} j"
    }
}

/**
 * Réglages : l'envoi des sessions au serveur (qui sert à mémoriser les morceaux et à régler la détection) et la
 * mémoire des morceaux. Le stroboscope reste sur l'écran du show.
 */
@Composable
internal fun ReglagesScreen(prefs: Prefs, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var envoi by remember { mutableStateOf(prefs.uploadEnabled) }
    val up by Uploader.state.collectAsState()
    val memV by TrackMemory.version.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        Uploader.kick(ctx)
        while (true) { delay(15_000); now = System.currentTimeMillis() }
    }
    val connus = remember(memV) { TrackMemory.tracks.size }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Revenir au show", onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Icon(Retour, contentDescription = "Retour", tint = Texte, modifier = Modifier.size(22.dp)) }
            Text("Réglages", Modifier.padding(start = 2.dp), style = corps(19, FontWeight.SemiBold).copy(letterSpacing = (-0.4).sp))
        }

        Carte("Améliorer Drop") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Envoyer mes sessions", style = corps(15, FontWeight.Medium))
                    Text(
                        "Pendant chaque show, Drop envoie à ton serveur ce qu'il mesure (niveaux, temps, drops), jamais le son du micro. " +
                            "C'est ce qui lui fait mémoriser tes morceaux et régler la détection.",
                        style = corps(12, color = Secondaire).copy(lineHeight = 17.sp),
                    )
                }
                Switch(
                    checked = envoi,
                    onCheckedChange = {
                        envoi = it
                        prefs.uploadEnabled = it
                        if (!it) File(ctx.filesDir, "outbox").listFiles()?.forEach { f -> f.delete() } else Uploader.kick(ctx)
                    },
                    modifier = Modifier.semantics { contentDescription = "Envoyer mes sessions" },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Fond, checkedTrackColor = Texte, checkedBorderColor = Texte,
                        uncheckedThumbColor = Secondaire, uncheckedTrackColor = Surface, uncheckedBorderColor = Neutre,
                    ),
                )
            }
            val etat = when {
                !envoi -> "Envoi coupé. Rien ne part, les envois en attente ont été effacés."
                up.error != null && up.pending > 0 -> "${up.pending} envoi${if (up.pending > 1) "s" else ""} en attente. ${up.error}. Tailscale est bien allumé ?"
                up.pending > 0 -> "${up.pending} envoi${if (up.pending > 1) "s" else ""} en cours."
                up.lastOk > 0 -> "Tout est envoyé, dernier envoi ${ilYa(up.lastOk).also { now }}."
                else -> "Rien en attente."
            }
            Text(etat, style = corps(12, color = if (up.error != null && envoi && up.pending > 0) Alerte else Tertiaire).copy(lineHeight = 17.sp))
        }

        Carte("Mémoire des morceaux") {
            Text(
                if (connus == 0) "Aucun morceau connu pour l'instant. Un morceau l'est après une écoute en entier pendant un show."
                else "$connus morceau${if (connus > 1) "x" else ""} connu${if (connus > 1) "s" else ""}. Sur ceux-là, Drop ne joue un drop qu'aux endroits mémorisés, et seulement si le son le confirme.",
                style = corps(13, color = Secondaire).copy(lineHeight = 19.sp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Bouton("Synchroniser") { TrackMemory.sync(ctx) }
                Bouton("Ouvrir le dashboard") { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SERVER))) }
            }
        }

        Text("Drop $version", Modifier.padding(top = 4.dp), style = Etiquette.copy(color = Tertiaire))
    }
}

@Composable
private fun Carte(titre: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface).border(1.dp, Ligne, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(titre.uppercase(), style = Etiquette.copy(color = Tertiaire))
        content()
    }
}

@Composable
private fun Bouton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Repere, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = corps(14, FontWeight.Medium)) }
}
