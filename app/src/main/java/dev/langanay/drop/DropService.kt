package dev.langanay.drop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

data class LiveState(
    val running: Boolean = false,
    val status: String = "Arrêté",
    val area: String = "",
    val bpm: Float = 0f,
    val level: Float = 0f,
    val mode: String = "",
    val figure: String = "",
    val mood: String = "",
    val beat: Long = 0,
    val drops: Int = 0,
    val error: String? = null,
    /** Temps dans la mesure (1 à 4) et mesure dans la phrase de 8. */
    val beatInBar: Int = 0,
    val barInPhrase: Int = 0,
    /** Montée en cours, de 0 à 1. */
    val tension: Float = 0f,
    /** Couleurs principale et secondaire du moment (ARGB), 0 à l'arrêt. */
    val lead: Int = 0,
    val second: Int = 0,
    /** Ce que joue chaque lampe, canal → couleur ARGB (luminosité comprise). */
    val lamps: Map<Int, Int> = emptyMap(),
    /** Durée du dernier stroboscope, en secondes. */
    val lastStrobe: Float = 0f,
)

private fun argb(c: FloatArray): Int = android.graphics.Color.rgb(
    (c[0].coerceIn(0f, 1f) * 255).toInt(),
    (c[1].coerceIn(0f, 1f) * 255).toInt(),
    (c[2].coerceIn(0f, 1f) * 255).toInt(),
)

/**
 * Service au premier plan : il garde le micro, le Wi-Fi et le processeur éveillés écran éteint,
 * relie micro, analyse et effets, et envoie les couleurs au pont 50 fois par seconde.
 */
class DropService : Service() {

    companion object {
        val live = MutableStateFlow(LiveState())
        @Volatile private var strobeTest = false

        /** Bouton « Tester le stroboscope » : la boucle lance la séquence du drop à l'image suivante. */
        fun testStrobe() { strobeTest = true }
        private const val STOP = "dev.langanay.drop.STOP"

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, DropService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, DropService::class.java).setAction(STOP))
    }

    @Volatile private var running = false
    /** Session en cours d'envoi au serveur (null si l'envoi est coupé) et rang de la prochaine tranche. */
    private var sessionId: String? = null
    private var sessionSeq = 0
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            running = false
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        startForeground(1, notification("Démarrage…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        running = true
        NowPlaying.start(this)
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "drop:run").apply { acquire(6 * 60 * 60 * 1000L) }
        @Suppress("DEPRECATION")
        wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "drop").apply { acquire() }
        worker = Thread({ run() }, "drop-main").also { it.start() }
        return START_NOT_STICKY
    }

    private fun run() {
        val p = Prefs(this)
        val bridge = HueBridge(p.bridgeIp)
        var stream: HueStream? = null
        var audio: AudioEngine? = null
        var areaId: String? = null
        var room: HueBridge.RoomInfo? = null
        val user = p.username
        val key = p.clientKey
        try {
            if (user == null || key == null) throw HueException("Associe d'abord l'app au pont.")
            live.value = LiveState(running = true, status = "Connexion au pont…")
            val areas = bridge.areas(user)
            val area = areas.firstOrNull { it.id == p.configId } ?: areas.firstOrNull { it.name == "Salon" } ?: areas.firstOrNull()
                ?: throw HueException("Aucune zone de synchro sur le pont.")
            areaId = area.id
            // Go : les lampes de la pièce qui ne suivent pas la musique s'éteignent en fondu.
            room = runCatching { bridge.roomInfo(user, area.id) }.getOrNull()
            room?.let { r -> runCatching { bridge.fadeOff(user, r.others, 2500) } }
            bridge.setStreaming(user, area.id, true)

            val s = HueStream(p.bridgeIp, area.id)
            val appId = runCatching { bridge.applicationId(user) }.getOrNull()
            try {
                s.connect(appId ?: user, key)
            } catch (e: Exception) {
                Log.w(TAG, "connexion avec l'identifiant d'application refusée, essai avec la clé d'application", e)
                if (appId == null) throw e
                s.connect(user, key)
            }
            stream = s

            val effects = Effects(area.channels)
            // Plan du salon pour le direct du dashboard (noms des lampes et positions du pont).
            val plan = runCatching { bridge.lamps(user, area) }.getOrElse { area.channels.mapIndexed { i, c -> Lamp(c.id, "Lampe ${i + 1}", c.x, c.y, false) } }
            Timeline.reset(area.channels.map { it.id })
            Recorder.reset()
            // Envoi de la session au serveur (page Réglages), par tranches de 30 s.
            val upload = p.uploadEnabled
            sessionId = if (upload) java.util.UUID.randomUUID().toString().replace("-", "") else null
            sessionSeq = 0
            if (upload) {
                Recorder.startStreaming(linkedMapOf(
                    "started_at" to System.currentTimeMillis().toString(),
                    "app" to "Drop ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"}",
                    "device" to android.os.Build.MODEL,
                    "zone" to area.name,
                ))
                Uploader.kick(this)
            }
            TrackMemory.load(this)
            TrackMemory.sync(this)
            var drops = 0
            val analyzer = Analyzer(48000, 512) { ev ->
                effects.post(ev)
                if (ev == AudioEvent.Drop) drops++
            }
            var trackKey: String? = null
            var memKey: String? = null
            var memVersion = -1L
            var prefsTick = 0
            fun applyPrefs() {
                // Réglages fixes, choisis pour que tout soit juste sans rien toucher ; le volume de la
                // pièce est suivi automatiquement par l'analyse.
                analyzer.sensitivity = 1f
                analyzer.latencySec = 0.07f
                effects.maxBrightness = 1f
                effects.dropFx = true
                effects.useCover = true
                effects.strobe = if (p.strobeOn) 1f else 0f
                val tr = NowPlaying.track.value
                effects.cover = tr?.colors ?: emptyList()
                effects.genreMood = tr?.mood
                // Nouveau morceau : l'analyse recale la phrase sur son premier temps franc.
                val key = tr?.let { "${it.artist}|${it.title}" }
                if (key != null && key != trackKey) {
                    if (trackKey != null) analyzer.newTrack()
                    trackKey = key
                    memKey = TrackMemory.key(tr.artist, tr.title)
                    memVersion = -1L
                    val now = analyzer.now()
                    Timeline.mark(now, Timeline.Kind.TRACK, tr.title)
                    Recorder.event(now, "title", "${tr.artist} - ${tr.title}")
                    Recorder.event(now, "track_key", memKey!!)
                    if (tr.durationMs > 0) Recorder.event(now, "dur", "%.1f".format(java.util.Locale.US, tr.durationMs / 1000.0))
                }
                // Mémoire du morceau (rechargée quand elle change) : ses drops connus, ou rien s'il est inconnu.
                val v = TrackMemory.version.value
                if (memKey != null && v != memVersion) {
                    memVersion = v
                    analyzer.setMemory(memKey!!, TrackMemory.drops(memKey))
                }
                // Position de lecture : les mesures se comptent depuis le début du morceau, et elle sert à la mémoire.
                if (key != null) NowPlaying.positionSec()?.let { pos ->
                    analyzer.trackPosition(pos, key)
                    if (prefsTick % 4 == 0) Recorder.event(analyzer.now(), "pos", "%.2f".format(java.util.Locale.US, pos))
                }
                prefsTick++
            }
            applyPrefs()
            audio = AudioEngine { analyzer.process(it) }.also { it.start(this) }
            Log.i(TAG, "synchro lancée sur « ${area.name} » (${area.channels.size} canaux)")
            notify("Synchro sur ${area.name}")

            var tick = 0L
            var beats = 0L
            while (running) {
                val t0 = System.nanoTime()
                val snap = analyzer.snapshot
                val out = effects.frame(analyzer.now(), snap)
                s.send(out)
                Timeline.sample(analyzer.now(), analyzer.takeLevelPeak(), snap.bass, snap.buildup, effects.excitement, effects.mode, effects.figureCode, out)
                tick++
                if (strobeTest) {
                    strobeTest = false
                    effects.testDrop()
                }
                if (tick % 25L == 0L) applyPrefs()
                if (upload && tick % 1500L == 0L) sessionId?.let { Uploader.enqueue(this, it, sessionSeq++, Recorder.takePending()) }
                if (tick % 100L == 0L) Log.i(TAG, "show ${effects.mode} ${effects.figureLabel} excitation ${"%.2f".format(effects.excitement)}")
                if (tick % 5L == 0L) {
                    beats++
                    live.value = LiveState(
                        running = true, status = "Synchro en cours", area = area.name, bpm = snap.bpm,
                        level = snap.level, mode = effects.mode, figure = effects.figureLabel,
                        mood = (NowPlaying.track.value?.genre ?: snap.mood.label), beat = beats, drops = drops,
                        beatInBar = effects.beatInBar, barInPhrase = effects.barInPhrase, tension = snap.buildup,
                        lead = argb(effects.lead), second = argb(effects.second),
                        lamps = out.mapValues { argb(it.value) }, lastStrobe = effects.lastStrobeLength.toFloat(),
                    )
                    if (beats % 5L == 0L) LivePush.send(this, live.value, sessionId, plan)
                }
                val spent = (System.nanoTime() - t0) / 1_000_000
                if (spent < 20) Thread.sleep(20 - spent)
            }
            live.value = live.value.copy(running = false, status = "Arrêté")
            LivePush.send(this, live.value, sessionId, plan)
        } catch (e: Exception) {
            Log.e(TAG, "arrêt sur erreur", e)
            live.value = LiveState(running = false, status = "Erreur", error = messageErreur(e))
        } finally {
            audio?.stop()
            // Dernière tranche et fin de session : le serveur analyse les morceaux entendus en entier.
            runCatching {
                sessionId?.let {
                    Uploader.enqueue(this, it, sessionSeq++, Recorder.takePending())
                    Uploader.enqueueEnd(this, it)
                }
                sessionId = null
                Recorder.stopStreaming()
            }
            // L'enregistrement des 5 dernières minutes, pour régler la détection hors ligne.
            runCatching { Recorder.save(this)?.let { Log.i(TAG, "enregistrement : ${it.name}") } }
            stream?.close()
            if (user != null && areaId != null) {
                runCatching { bridge.setStreaming(user, areaId, false) }
                // Arrêt : la pièce repasse en douceur sur la scène Apéro.
                room?.let { r ->
                    runCatching {
                        Thread.sleep(400)
                        val ok = bridge.recallScene(user, r.roomId, "Apéro", 3500)
                        Log.i(TAG, if (ok) "retour sur la scène Apéro" else "scène Apéro introuvable dans la pièce")
                    }
                }
            }
            wake?.let { if (it.isHeld) it.release() }
            wifi?.let { if (it.isHeld) it.release() }
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("drop", "Synchro", NotificationManager.IMPORTANCE_LOW))
        val stopIntent = PendingIntent.getService(this, 0, Intent(this, DropService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "drop")
            .setSmallIcon(R.drawable.ic_drop_notif)
            .setContentTitle("Drop")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_drop_notif), "Arrêter", stopIntent).build())
            .build()
    }

    private fun notify(text: String) = getSystemService(NotificationManager::class.java).notify(1, notification(text))
}
