package dev.langanay.drop

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Nuit = Color(0xFF0A0A0C)
private val Carte = Color(0xFF15161B)
private val Craie = Color(0xFFFAFAFA)
private val Brume = Color(0xFFA1A1A1)
private val Abricot = Color(0xFFFDA06C)
private val Grenat = Color(0xFFC94961)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Nuit, surface = Carte, primary = Abricot, onPrimary = Nuit)) {
                Box(Modifier.fillMaxSize().background(Nuit).systemBarsPadding()) {
                    App(prefs)
                }
            }
        }
    }

    @Composable
    private fun App(prefs: Prefs) {
        var paired by remember { mutableStateOf(prefs.paired) }
        val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
        LaunchedEffect(Unit) {
            perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Drop", color = Craie, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            if (!paired) PairCard(prefs) { paired = true } else Controls(prefs) { paired = false }
        }
    }

    @Composable
    private fun PairCard(prefs: Prefs, onPaired: () -> Unit) {
        val scope = rememberCoroutineScope()
        var ip by remember { mutableStateOf(prefs.bridgeIp) }
        var msg by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        Card {
            Text("Associer le pont Hue", color = Craie, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("1. Appuie sur le bouton rond au centre du pont.\n2. Dans les 30 secondes, touche Associer.", color = Brume, fontSize = 14.sp)
            OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("Adresse du pont") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    msg = null
                    scope.launch {
                        val res = withContext(Dispatchers.IO) { runCatching { HueBridge(ip.trim()).pair() } }
                        busy = false
                        res.onSuccess { (u, k) ->
                            prefs.bridgeIp = ip.trim(); prefs.username = u; prefs.clientKey = k
                            onPaired()
                        }.onFailure { msg = it.message ?: "Échec de l'association" }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Abricot, contentColor = Nuit),
                shape = RoundedCornerShape(9.dp),
            ) { Text(if (busy) "Association…" else "Associer") }
            msg?.let { Text(it, color = Grenat, fontSize = 14.sp) }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Controls(prefs: Prefs, onUnpair: () -> Unit) {
        val state by DropService.live.collectAsState()
        val scope = rememberCoroutineScope()
        var areas by remember { mutableStateOf<List<EntArea>>(emptyList()) }
        var areaId by remember { mutableStateOf(prefs.configId) }
        var areaErr by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            val res = withContext(Dispatchers.IO) { runCatching { HueBridge(prefs.bridgeIp).areas(prefs.username!!) } }
            res.onSuccess { list ->
                areas = list
                if (areaId == null || list.none { it.id == areaId }) {
                    areaId = (list.firstOrNull { it.name == "Salon" } ?: list.firstOrNull())?.id
                    prefs.configId = areaId
                }
            }.onFailure { areaErr = it.message }
        }

        // Gros bouton et lecture en direct.
        Card {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                val beatColor by animateColorAsState(if (state.running && state.beat % 2L == 0L) Abricot else Carte, label = "beat")
                Box(
                    Modifier.size(92.dp).clip(CircleShape).background(if (state.running) Grenat else Abricot)
                        .clickable { if (state.running) DropService.stop(this@MainActivity) else DropService.start(this@MainActivity) },
                    contentAlignment = Alignment.Center,
                ) { Text(if (state.running) "Stop" else "Go", color = Nuit, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.status + if (state.area.isNotEmpty()) " · ${state.area}" else "", color = Brume, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (state.running) "%.0f BPM".format(state.bpm) else "--", color = Craie, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Box(Modifier.size(14.dp).clip(CircleShape).background(beatColor))
                    }
                    Text(listOf(state.mode, state.figure).filter { it.isNotEmpty() }.joinToString(" · "), color = if (state.mode == "DROP" || state.mode == "Stroboscope") Abricot else Craie, fontSize = 15.sp)
                    if (state.mood.isNotEmpty()) Text(state.mood, color = Brume, fontSize = 13.sp)
                    if (state.drops > 0) Text("${state.drops} drop${if (state.drops > 1) "s" else ""}", color = Brume, fontSize = 13.sp)
                }
            }
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Nuit)) {
                Box(Modifier.fillMaxWidth(state.level.coerceIn(0f, 1f)).height(6.dp).background(Abricot))
            }
            state.error?.let { Text(it, color = Grenat, fontSize = 14.sp) }
        }

        NowPlayingCard(prefs)

        Card {
            Text("Zone", color = Craie, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            areaErr?.let { Text(it, color = Grenat, fontSize = 14.sp) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                areas.forEach { a ->
                    Chip("${a.name} (${a.channels.size})${if (a.active && !state.running) " · occupée" else ""}", a.id == areaId) {
                        areaId = a.id; prefs.configId = a.id
                    }
                }
            }
            Text("« occupée » : une autre app (iLightShow, Hue Sync) pilote déjà cette zone ; arrête-la avant.", color = Brume, fontSize = 12.sp)
        }

        Card {
            var strobeOn by remember { mutableStateOf(prefs.strobeOn) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Stroboscope sur les drops", color = Craie, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(strobeOn, { strobeOn = it; prefs.strobeOn = it })
            }
            Text("2,5 à 4 secondes de flashs rapides au moment du drop. Déconseillé aux personnes photosensibles.", color = Brume, fontSize = 12.sp)
        }

        TextButton(onClick = {
            DropService.stop(this@MainActivity)
            prefs.username = null; prefs.clientKey = null
            scope.launch { onUnpair() }
        }) { Text("Oublier ce pont", color = Brume) }
        Spacer(Modifier.height(24.dp))
    }

    @Composable
    private fun NowPlayingCard(prefs: Prefs) {
        val ctx = this@MainActivity
        var enabled by remember { mutableStateOf(NowPlaying.enabled(ctx)) }
        val track by NowPlaying.track.collectAsState()
        LaunchedEffect(Unit) {
            // Au retour des réglages, l'accès vient d'être donné : on le voit ici sans relancer l'app.
            while (true) {
                val now = NowPlaying.enabled(ctx)
                if (now) NowPlaying.start(ctx)
                enabled = now
                delay(if (now) 5000 else 1000)
            }
        }
        Card {
            Text("Morceau en cours", color = Craie, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val t = track
            when {
                !enabled -> {
                    Text("Autorise Drop à lire les notifications : il y trouve le morceau que joue Spotify, sa jaquette pour les couleurs et son style.", color = Brume, fontSize = 13.sp)
                    Button(
                        onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Abricot, contentColor = Nuit),
                        shape = RoundedCornerShape(9.dp),
                    ) { Text("Autoriser") }
                }
                t == null -> Text("Lance un morceau sur Spotify.", color = Brume, fontSize = 14.sp)
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        t.art?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))) }
                        Column(Modifier.weight(1f)) {
                            Text(t.title, color = Craie, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artist, color = Brume, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.genre ?: "style en cours de recherche", color = Brume, fontSize = 12.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (t.colors.isEmpty()) Text("Pochette sans couleur : palette du style.", color = Brume, fontSize = 12.sp)
                        t.colors.forEach { c -> Box(Modifier.size(18.dp).clip(CircleShape).background(Color(c[0], c[1], c[2]))) }
                    }
                }
            }
        }
    }

    @Composable
    private fun Card(content: @Composable () -> Unit) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Carte)
                .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(12.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }

    @Composable
    private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
        Box(
            Modifier.clip(RoundedCornerShape(9.dp))
                .background(if (selected) Abricot else Nuit)
                .border(1.dp, if (selected) Abricot else Color(0x1AFFFFFF), RoundedCornerShape(9.dp))
                .clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) { Text(label, color = if (selected) Nuit else Craie, fontSize = 14.sp) }
    }

}
