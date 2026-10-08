package dev.langanay.drop

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * Serveur de Drop, joignable seulement par Tailscale (qui identifie le téléphone). L'adresse vient de la compilation
 * (propriété Gradle `drop.server`, `local.properties` ou variable `DROP_SERVER_URL`) ; vide, rien n'est envoyé.
 */
val SERVER: String = BuildConfig.DROP_SERVER_URL.trimEnd('/')

private fun http(method: String, path: String, body: ByteArray? = null, type: String = "application/json"): Pair<Int, String> {
    check(SERVER.isNotBlank()) { "adresse du serveur non configurée à la compilation" }
    val c = URL(SERVER + path).openConnection() as HttpURLConnection
    c.requestMethod = method
    c.connectTimeout = 6000
    c.readTimeout = 60000
    if (body != null) {
        c.doOutput = true
        c.setRequestProperty("Content-Type", type)
        c.setFixedLengthStreamingMode(body.size)
        c.outputStream.use { it.write(body) }
    }
    val code = c.responseCode
    val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
    c.disconnect()
    return code to text
}

/**
 * Boîte d'envoi des sessions : chaque tranche (30 s) est d'abord écrite sur le téléphone, puis envoyée dans l'ordre.
 * Sans réseau (Tailscale coupé, hors de chez soi), elle attend et repart à la prochaine occasion ; rien n'est perdu
 * tant que la boîte reste sous 150 Mo.
 */
object Uploader {
    data class State(val pending: Int = 0, val lastOk: Long = 0L, val error: String? = null)

    val state = MutableStateFlow(State())
    private val exec = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var retryScheduled = false

    private fun box(ctx: Context) = File(ctx.filesDir, "outbox").apply { mkdirs() }

    /** Écrit une tranche (compressée hors de la boucle du show) puis lance l'envoi ; tout passe dans l'ordre. */
    fun enqueue(ctx: Context, sid: String, seq: Int, text: String) {
        if (text.isBlank()) return
        val app = ctx.applicationContext
        exec.execute {
            runCatching {
                val f = File(box(app), "${sid}_${"%05d".format(seq)}.csv.gz")
                GZIPOutputStream(f.outputStream()).use { it.write(text.toByteArray()) }
                trim(app)
            }.onFailure { Log.w(TAG, "tranche non écrite : ${it.message}") }
            drain(app)
        }
    }

    /** Fin de session, après sa dernière tranche : le serveur analyse alors les morceaux entendus en entier. */
    fun enqueueEnd(ctx: Context, sid: String) {
        val app = ctx.applicationContext
        exec.execute {
            runCatching { File(box(app), "${sid}_99999.end").writeText("") }
            drain(app)
        }
    }

    fun kick(ctx: Context) {
        val app = ctx.applicationContext
        exec.execute { drain(app) }
    }

    private fun trim(ctx: Context) {
        val files = box(ctx).listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total < 150L * 1024 * 1024) break
            total -= f.length()
            f.delete()
        }
    }

    private fun drain(ctx: Context) {
        if (!Prefs(ctx).uploadEnabled) { state.value = State(); return }
        val files = box(ctx).listFiles()?.sortedBy { it.name } ?: return
        state.value = state.value.copy(pending = files.size)
        var sessionEnded = false
        for ((i, f) in files.withIndex()) {
            val sid = f.name.substringBefore('_')
            val ok = runCatching {
                if (f.name.endsWith(".end")) {
                    http("POST", "/api/sessions/$sid/end", ByteArray(0), "text/plain").first == 200
                } else {
                    val seq = f.name.substringAfter('_').substringBefore('.').toInt()
                    http("POST", "/api/sessions/$sid/chunks?seq=$seq", f.readBytes(), "application/gzip").first == 200
                }
            }.onFailure { Log.w(TAG, "envoi impossible : ${it.message}") }.getOrDefault(false)
            if (!ok) {
                state.value = State(pending = files.size - i, lastOk = state.value.lastOk, error = "Serveur injoignable, nouvel essai dans une minute")
                if (!retryScheduled) {
                    retryScheduled = true
                    exec.schedule({ retryScheduled = false; drain(ctx) }, 60, TimeUnit.SECONDS)
                }
                return
            }
            if (f.name.endsWith(".end")) sessionEnded = true
            f.delete()
            state.value = State(pending = files.size - i - 1, lastOk = System.currentTimeMillis())
        }
        // Une session analysée peut avoir appris de nouveaux morceaux.
        if (sessionEnded) TrackMemory.syncNow(ctx)
    }
}

/**
 * Direct : l'état du show part au serveur deux fois par seconde, pour le suivre sur le dashboard pendant qu'il tourne.
 * Rien n'est gardé : si un envoi est encore en vol ou échoue, l'état suivant le remplace.
 */
object LivePush {
    private val exec = Executors.newSingleThreadExecutor()
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastT = -1.0
    private var lastMark = 0L

    /** Nouveaux échantillons de la frise depuis le dernier envoi, un sur deux (25 par seconde), en colonnes. */
    private fun timeline(): JSONObject = synchronized(Timeline) {
        val o = JSONObject()
        val n = Timeline.size
        if (n == 0 || Timeline.last() < lastT) { lastT = -1.0; lastMark = 0L }
        val t = org.json.JSONArray(); val lv = org.json.JSONArray(); val b = org.json.JSONArray()
        val te = org.json.JSONArray(); val inn = org.json.JSONArray(); val mo = org.json.JSONArray(); val fx = org.json.JSONArray()
        val lamps = Timeline.channels.map { org.json.JSONArray() }
        var lo = 0
        var hi = n
        while (lo < hi) { val mid = (lo + hi) / 2; if (Timeline.time[Timeline.at(mid)] <= lastT) lo = mid + 1 else hi = mid }
        var i = lo
        while (i < n) {
            val k = Timeline.at(i)
            t.put(Math.round(Timeline.time[k] * 1000) / 1000.0)
            lv.put(Math.round(Timeline.level[k] * 1000) / 1000.0)
            b.put(Math.round(Timeline.bass[k] * 1000) / 1000.0)
            te.put(Math.round(Timeline.tension[k] * 1000) / 1000.0)
            inn.put(Math.round(Timeline.intensity[k] * 1000) / 1000.0)
            mo.put(Timeline.mode[k].toInt())
            fx.put(effetDe(Timeline.mode[k].toInt(), Timeline.figure[k].toInt()))
            for ((j, arr) in lamps.withIndex()) arr.put(hex(Timeline.lamps[j][k]))
            lastT = Timeline.time[k]
            i += 2
        }
        o.put("t", t).put("level", lv).put("bass", b).put("tension", te).put("intensity", inn).put("mode", mo).put("fx", fx)
        o.put("lamps", JSONObject().apply { Timeline.channels.forEachIndexed { j, ch -> put(ch.toString(), lamps[j]) } })
        val nouveaux = (Timeline.marksAdded - lastMark).coerceIn(0L, Timeline.marks.size.toLong()).toInt()
        o.put("marks", org.json.JSONArray().apply {
            Timeline.marks.takeLast(nouveaux).forEach {
                put(JSONObject().put("t", Math.round(it.t * 1000) / 1000.0).put("k", it.kind.name).put("l", it.label).put("v", it.value))
            }
        })
        lastMark = Timeline.marksAdded
        o.put("now", Timeline.last())
        o
    }

    fun send(ctx: Context, s: LiveState, sid: String?, lamps: List<Lamp>) {
        if (!Prefs(ctx).uploadEnabled || !busy.compareAndSet(false, true)) return
        val prevT = lastT
        val prevMark = lastMark
        val tr = NowPlaying.track.value
        val o = JSONObject()
            .put("running", s.running).put("status", s.status).put("area", s.area)
            .put("bpm", s.bpm.toDouble()).put("level", s.level.toDouble()).put("mode", s.mode).put("figure", s.figure)
            .put("mood", s.mood).put("drops", s.drops).put("error", s.error ?: JSONObject.NULL)
            .put("beatInBar", s.beatInBar).put("barInPhrase", s.barInPhrase).put("tension", s.tension.toDouble())
            .put("lead", hex(s.lead)).put("second", hex(s.second)).put("lastStrobe", s.lastStrobe.toDouble())
            .put("lamps", JSONObject().apply { s.lamps.forEach { (k, v) -> put(k.toString(), hex(v)) } })
            .put("plan", org.json.JSONArray().apply {
                lamps.forEach { put(JSONObject().put("ch", it.channel).put("name", it.name).put("strip", it.strip).put("play", it.play).put("x", it.x.toDouble()).put("y", it.y.toDouble())) }
            })
            .put("title", tr?.title ?: JSONObject.NULL).put("artist", tr?.artist ?: JSONObject.NULL)
            .put("position", NowPlaying.positionSec() ?: JSONObject.NULL)
            .put("session", sid ?: JSONObject.NULL).put("sent_at", System.currentTimeMillis())
            .put("figureName", FIGURES[s.figure]?.first ?: s.figure)
            .put("memory", TrackMemory.drops(tr?.let { TrackMemory.key(it.artist, it.title) })?.let { org.json.JSONArray(it) } ?: JSONObject.NULL)
            .put("timeline", timeline())
            .put("cover", tr?.takeIf { it.art != null }?.let { TrackMemory.key(it.artist, it.title) } ?: JSONObject.NULL)
            .put("palette", org.json.JSONArray().apply {
                tr?.colors?.forEach { c -> put("#%02X%02X%02X".format((c[0].coerceIn(0f, 1f) * 255).toInt(), (c[1].coerceIn(0f, 1f) * 255).toInt(), (c[2].coerceIn(0f, 1f) * 255).toInt())) }
            })
        val art = tr?.art
        val coverKey = tr?.let { TrackMemory.key(it.artist, it.title) }
        exec.execute {
            try {
                if (art != null && coverKey != null) envoyerPochette(art, coverKey)
                // Envoi raté : la frise repartira de là au prochain envoi, rien ne manque côté dashboard.
                val ok = runCatching { http("POST", "/api/live", o.toString().toByteArray()).first == 200 }.getOrDefault(false)
                if (!ok) synchronized(Timeline) { lastT = prevT; lastMark = prevMark }
            } finally { busy.set(false) }
        }
    }

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    /** Pochette déjà envoyée (même image) : elle ne repart qu'au changement de morceau. */
    @Volatile private var coverSent: android.graphics.Bitmap? = null

    private fun envoyerPochette(art: android.graphics.Bitmap, key: String) {
        if (art === coverSent) return
        val out = java.io.ByteArrayOutputStream()
        val side = 640
        val b = if (art.width > side) android.graphics.Bitmap.createScaledBitmap(art, side, art.height * side / art.width, true) else art
        b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
        val ok = runCatching {
            http("POST", "/api/cover?key=${URLEncoder.encode(key, "UTF-8")}", out.toByteArray(), "image/jpeg").first == 200
        }.getOrDefault(false)
        if (ok) coverSent = art
    }
}

/**
 * Mémoire des morceaux : pour chaque morceau entendu en entier, la position (dans le morceau) de ses vrais drops,
 * calculée par le serveur sur tout le morceau. Gardée sur le téléphone pour marcher sans réseau, mise à jour au Go
 * et après chaque session. Un morceau connu ne joue un drop qu'à une position mémorisée, et seulement si le son le
 * confirme (voir Analyzer).
 */
object TrackMemory {
    @Volatile var tracks: Map<String, List<Double>> = emptyMap()
        private set
    /** Change à chaque mise à jour, pour que le show recharge la mémoire du morceau en cours. */
    val version = MutableStateFlow(0L)
    private val exec = Executors.newSingleThreadExecutor()

    /** Même clé que le serveur : titre et artistes triés, en minuscules. */
    fun key(artist: String, title: String): String {
        val arts = artist.split(',', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.sorted()
        return title.trim().lowercase() + "|" + arts.joinToString(", ")
    }

    fun drops(key: String?): List<Double>? = key?.let { tracks[it] }

    private fun file(ctx: Context) = File(ctx.filesDir, "memory.json")

    fun load(ctx: Context) {
        if (tracks.isNotEmpty()) return
        runCatching { parse(file(ctx).readText()) }
    }

    private fun parse(json: String) {
        val o = JSONObject(json).optJSONObject("tracks") ?: return
        val out = HashMap<String, List<Double>>()
        for (k in o.keys()) {
            val d = o.getJSONObject(k).optJSONArray("drops") ?: continue
            out[k] = (0 until d.length()).map { d.getDouble(it) }
        }
        tracks = out
        version.value = System.currentTimeMillis()
    }

    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        exec.execute { syncNow(app) }
    }

    fun syncNow(ctx: Context) {
        runCatching {
            val (code, text) = http("GET", "/api/memory")
            if (code == 200) {
                parse(text)
                file(ctx).writeText(text)
                Log.i(TAG, "mémoire : ${tracks.size} morceaux connus")
            }
        }.onFailure { Log.w(TAG, "mémoire injoignable : ${it.message}") }
    }

    /** « Oublier ce morceau » : il sera réappris à sa prochaine écoute complète. */
    fun forget(ctx: Context, key: String) {
        tracks = tracks - key
        version.value = System.currentTimeMillis()
        val app = ctx.applicationContext
        exec.execute {
            runCatching { http("POST", "/api/tracks/${URLEncoder.encode(key, "UTF-8").replace("+", "%20")}/forget", ByteArray(0)) }
            syncNow(app)
        }
    }
}
