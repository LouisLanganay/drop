package dev.langanay.drop

import android.util.Log
import org.bouncycastle.tls.BasicTlsPSKIdentity
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DTLSClientProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.UDPTransport
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

const val TAG = "Drop"

data class Channel(val id: Int, val x: Float, val y: Float, val z: Float)
data class EntArea(val id: String, val name: String, val active: Boolean, val channels: List<Channel>)
class HueException(msg: String) : Exception(msg)

/** Appels REST au pont. Il présente un certificat à lui : on ne le vérifie pas, on ne parle qu'à son adresse locale. */
class HueBridge(private val ip: String) {

    private val ssl: SSLContext = SSLContext.getInstance("TLS").apply {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        init(null, trustAll, SecureRandom())
    }

    private class Resp(val code: Int, val body: String, val headers: Map<String, List<String>>)

    private fun call(method: String, path: String, body: String? = null, key: String? = null): Resp {
        val c = URL("https://$ip$path").openConnection() as HttpsURLConnection
        c.sslSocketFactory = ssl.socketFactory
        c.setHostnameVerifier { _, _ -> true }
        c.requestMethod = method
        c.connectTimeout = 4000
        c.readTimeout = 6000
        key?.let { c.setRequestProperty("hue-application-key", it) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        val headers = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }
        c.disconnect()
        return Resp(code, text, headers)
    }

    /** Association : le bouton du pont doit avoir été pressé dans les 30 secondes. Rend (username, clientkey). */
    fun pair(): Pair<String, String> {
        val r = call("POST", "/api", JSONObject().put("devicetype", "drop#s24").put("generateclientkey", true).toString())
        val first = JSONArray(r.body).getJSONObject(0)
        first.optJSONObject("error")?.let {
            if (it.optInt("type") == 101) throw HueException("Appuie d'abord sur le bouton rond du pont, puis réessaie dans les 30 secondes.")
            throw HueException(it.optString("description"))
        }
        val ok = first.getJSONObject("success")
        return ok.getString("username") to ok.getString("clientkey")
    }

    /** Identifiant d'application, identité attendue par le flux chiffré en API v2. */
    fun applicationId(username: String): String? =
        call("GET", "/auth/v1", key = username).headers["hue-application-id"]?.firstOrNull()

    fun areas(username: String): List<EntArea> {
        val r = call("GET", "/clip/v2/resource/entertainment_configuration", key = username)
        if (r.code != 200) throw HueException("Zones de synchro illisibles (${r.code})")
        val data = JSONObject(r.body).getJSONArray("data")
        return (0 until data.length()).map { i ->
            val o = data.getJSONObject(i)
            val chs = o.optJSONArray("channels") ?: JSONArray()
            EntArea(
                id = o.getString("id"),
                name = o.getJSONObject("metadata").getString("name"),
                active = o.optString("status") == "active",
                channels = (0 until chs.length()).map { k ->
                    val c = chs.getJSONObject(k)
                    val p = c.getJSONObject("position")
                    Channel(c.getInt("channel_id"), p.getDouble("x").toFloat(), p.getDouble("y").toFloat(), p.getDouble("z").toFloat())
                },
            )
        }
    }

    fun setStreaming(username: String, areaId: String, start: Boolean) {
        val r = call("PUT", "/clip/v2/resource/entertainment_configuration/$areaId",
            JSONObject().put("action", if (start) "start" else "stop").toString(), username)
        if (start && r.code != 200) throw HueException("Le pont refuse de démarrer la synchro (${r.code}) : ${r.body.take(160)}")
    }
}

/**
 * Flux temps réel vers le pont : DTLS 1.2, clé partagée, port UDP 2100, messages HueStream v2.
 * Message : « HueStream », version 2.0, séquence, 2 octets réservés, espace couleur (0 = RVB),
 * 1 octet réservé, identifiant de la zone (36 caractères), puis 7 octets par canal :
 * numéro du canal et R, V, B sur 16 bits.
 */
class HueStream(private val ip: String, private val areaId: String) {
    private var socket: DatagramSocket? = null
    private var transport: DTLSTransport? = null
    private var seq = 0

    fun connect(identity: String, clientKeyHex: String) {
        val s = DatagramSocket()
        s.connect(InetAddress.getByName(ip), 2100)
        s.soTimeout = 4000
        val psk = BasicTlsPSKIdentity(identity, hex(clientKeyHex))
        val client = object : PSKTlsClient(BcTlsCrypto(SecureRandom()), psk) {
            override fun getSupportedCipherSuites(): IntArray = intArrayOf(CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256)
            override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.DTLSv12.only()
        }
        try {
            transport = DTLSClientProtocol().connect(client, UDPTransport(s, 1400))
            socket = s
            Log.i(TAG, "flux chiffré ouvert (identité ${identity.take(8)}…)")
        } catch (e: Exception) {
            s.close()
            throw e
        }
    }

    /** colors : pour chaque canal, R, V, B entre 0 et 1. */
    fun send(colors: Map<Int, FloatArray>) {
        val t = transport ?: return
        val msg = ByteArray(52 + 7 * colors.size)
        "HueStream".toByteArray(Charsets.US_ASCII).copyInto(msg, 0)
        msg[9] = 2; msg[10] = 0
        msg[11] = (seq++ and 0xff).toByte()
        areaId.toByteArray(Charsets.US_ASCII).copyInto(msg, 16)
        var o = 52
        for ((ch, rgb) in colors) {
            msg[o] = ch.toByte()
            for (i in 0..2) {
                val v = (rgb[i].coerceIn(0f, 1f) * 65535f).toInt()
                msg[o + 1 + i * 2] = (v shr 8).toByte()
                msg[o + 2 + i * 2] = (v and 0xff).toByte()
            }
            o += 7
        }
        t.send(msg, 0, msg.size)
    }

    fun close() {
        try { transport?.close() } catch (_: Exception) {}
        socket?.close()
        transport = null
        socket = null
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
