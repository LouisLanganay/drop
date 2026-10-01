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

/** Serveur de Drop sur agentia, joignable seulement par Tailscale (qui identifie le téléphone). */
const val SERVER = "https://agentia.tail16ac81.ts.net:4445"

private fun http(method: String, path: String, body: ByteArray? = null, type: String = "application/json"): Pair<Int, String> {
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
