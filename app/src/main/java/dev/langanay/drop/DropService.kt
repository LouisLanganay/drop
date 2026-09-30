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
)

/**
 * Service au premier plan : il garde le micro, le Wi-Fi et le processeur éveillés écran éteint,
 * relie micro, analyse et effets, et envoie les couleurs au pont 50 fois par seconde.
 */
class DropService : Service() {

    companion object {
        val live = MutableStateFlow(LiveState())
        private const val STOP = "dev.langanay.drop.STOP"

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, DropService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, DropService::class.java).setAction(STOP))
    }

    @Volatile private var running = false
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
            var drops = 0
            val analyzer = Analyzer(48000, 512) { ev ->
                effects.post(ev)
                if (ev == AudioEvent.Drop) drops++
            }
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
                s.send(effects.frame(analyzer.now(), snap))
                tick++
                if (tick % 25L == 0L) applyPrefs()
                if (tick % 100L == 0L) Log.i(TAG, "show ${effects.mode} ${effects.figureLabel} excitation ${"%.2f".format(effects.excitement)}")
                if (tick % 5L == 0L) {
                    beats++
                    live.value = LiveState(
                        running = true, status = "Synchro en cours", area = area.name, bpm = snap.bpm,
                        level = snap.level, mode = effects.mode, figure = effects.figureLabel,
                        mood = (NowPlaying.track.value?.genre ?: snap.mood.label), beat = beats, drops = drops,
                    )
                }
                val spent = (System.nanoTime() - t0) / 1_000_000
                if (spent < 20) Thread.sleep(20 - spent)
            }
            live.value = live.value.copy(running = false, status = "Arrêté")
        } catch (e: Exception) {
            Log.e(TAG, "arrêt sur erreur", e)
            live.value = LiveState(running = false, status = "Erreur", error = e.message ?: e.javaClass.simpleName)
        } finally {
            audio?.stop()
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
            .setSmallIcon(R.drawable.ic_drop)
            .setContentTitle("Drop")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_drop), "Arrêter", stopIntent).build())
            .build()
    }

    private fun notify(text: String) = getSystemService(NotificationManager::class.java).notify(1, notification(text))
}
