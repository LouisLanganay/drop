package dev.langanay.drop

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.palette.graphics.Palette
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/** Sa seule présence, une fois autorisée dans les réglages, donne accès aux lecteurs en cours (Spotify compris). */
class NowPlayingListener : NotificationListenerService()

data class Track(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    /** Couleurs de la pochette, saturées pour des ampoules : vide si la pochette est en noir et blanc. */
    val colors: List<FloatArray>,
    val genre: String? = null,
    val mood: Mood? = null,
)

/**
 * Morceau en cours, lu dans la session média de Spotify (ou du lecteur actif), sans API Spotify :
 * titre, artiste et jaquette. Les couleurs viennent de la jaquette, le genre de l'API publique de Deezer.
 */
object NowPlaying {
    val track = MutableStateFlow<Track?>(null)

    @Volatile private var controller: MediaController? = null
    private var started = false
    private val io = Executors.newSingleThreadExecutor()
    private val genreCache = HashMap<String, String?>()

    fun enabled(ctx: Context): Boolean = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = update(metadata)
        override fun onPlaybackStateChanged(state: PlaybackState?) {}
    }

    /** Position de lecture du morceau en cours, en secondes, ou null si rien ne joue. */
    fun positionSec(): Double? {
        val st = controller?.playbackState ?: return null
        if (st.state != PlaybackState.STATE_PLAYING || st.position < 0) return null
        val elapsed = SystemClock.elapsedRealtime() - st.lastPositionUpdateTime
        return (st.position + elapsed * st.playbackSpeed) / 1000.0
    }

    /** À appeler depuis le fil principal. */
    fun start(ctx: Context) {
        if (!enabled(ctx)) return
        val msm = ctx.getSystemService(MediaSessionManager::class.java)
        val cn = ComponentName(ctx, NowPlayingListener::class.java)
        runCatching {
            if (!started) {
                msm.addOnActiveSessionsChangedListener({ attach(it) }, cn)
                started = true
            }
            attach(msm.getActiveSessions(cn))
        }.onFailure { Log.w(TAG, "sessions média illisibles", it) }
    }

    private fun attach(list: List<MediaController>?) {
        val pick = list?.firstOrNull { it.packageName == "com.spotify.music" }
            ?: list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list?.firstOrNull()
        if (pick?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = pick
        pick?.registerCallback(callback)
        update(pick?.metadata)
    }

    private fun update(md: MediaMetadata?) {
        if (md == null) return
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val cur = track.value
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        // Spotify renvoie le même morceau en rafale : on ne refait rien tant que la pochette n'arrive pas.
        val same = cur != null && cur.title == title && cur.artist == artist
        if (same && (cur!!.art != null || art == null)) return
        val colors = art?.let { coverColors(it) } ?: emptyList()
        track.value = Track(title, artist, art, colors, if (same) cur!!.genre else null, if (same) cur!!.mood else null)
        Log.i(TAG, "morceau : $artist, $title (${colors.size} couleurs de pochette)")
        if (same && cur!!.genre != null) return
        val key = "$artist|$title"
        io.execute {
            val genre = if (genreCache.containsKey(key)) genreCache[key] else runCatching { deezerGenre(artist, title) }.getOrNull().also { genreCache[key] = it }
            val t = track.value
            if (t != null && t.title == title && t.artist == artist) {
                track.value = t.copy(genre = genre, mood = genre?.let { moodOf(it) })
                Log.i(TAG, "genre : ${genre ?: "inconnu"}")
            }
        }
    }

    /** Recherche du morceau sur Deezer, puis genre de son album (ex. « Rap/Hip Hop », « Electro »). */
    private fun deezerGenre(artist: String, title: String): String? {
        val q = URLEncoder.encode("$artist $title", "UTF-8")
        val hit = JSONObject(get("https://api.deezer.com/search?q=$q&limit=1")).optJSONArray("data")?.optJSONObject(0) ?: return null
        val albumId = hit.optJSONObject("album")?.optLong("id") ?: return null
        val genres = JSONObject(get("https://api.deezer.com/album/$albumId")).optJSONObject("genres")?.optJSONArray("data")
        return genres?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 5000
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }

    fun moodOf(genre: String): Mood = when {
        // Le reggae de Deezer couvre aussi le dancehall et la shatta : ça se joue comme du rap, pas comme une ballade.
        listOf("rap", "hip", "trap", "reggae", "dancehall").any { genre.contains(it, true) } -> Mood.TRAP
        listOf("electro", "dance", "techno", "house").any { genre.contains(it, true) } -> Mood.HOUSE
        listOf("rock", "metal", "punk", "alternative").any { genre.contains(it, true) } -> Mood.ROCK
        listOf("jazz", "classi", "folk", "blues", "soul", "lounge").any { genre.contains(it, true) } -> Mood.CHILL
        else -> Mood.GROOVE
    }

    /**
     * Couleurs de la pochette pour des lampes : teintes distinctes (30° d'écart au moins), saturées
     * et à pleine valeur, classées de la plus vive à la plus présente. Une pochette sans couleur rend une liste vide.
     */
    fun coverColors(bmp: Bitmap): List<FloatArray> {
        val p = Palette.from(bmp).maximumColorCount(24).generate()
        val swatches = listOfNotNull(p.vibrantSwatch, p.darkVibrantSwatch, p.lightVibrantSwatch, p.mutedSwatch,
            p.darkMutedSwatch, p.lightMutedSwatch, p.dominantSwatch) + p.swatches
        val ranked = swatches.distinct().map { sw ->
            val hsv = FloatArray(3)
            Color.colorToHSV(sw.rgb, hsv)
            hsv to hsv[1] * sw.population.toFloat().pow(0.35f)
        }.filter { it.first[1] > 0.18f && it.first[2] > 0.15f }.sortedByDescending { it.second }
        val hues = ArrayList<FloatArray>()
        for ((hsv, _) in ranked) {
            if (hues.none { hueDist(it[0], hsv[0]) < 30f }) hues += hsv
            if (hues.size == 4) break
        }
        return hues.map { hsv(it[0], max(0.75f, it[1]), 1f) }
    }

    private fun hueDist(a: Float, b: Float): Float { val d = abs(a - b) % 360f; return if (d > 180f) 360f - d else d }

    fun hsv(h: Float, s: Float, v: Float): FloatArray {
        val c = Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))
        return floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
    }
}
