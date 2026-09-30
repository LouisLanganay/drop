package dev.langanay.drop

import android.content.Context

/** Réglages et identifiants du pont, gardés sur le téléphone. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("drop", Context.MODE_PRIVATE)

    var bridgeIp: String
        get() = sp.getString("ip", "192.168.1.14") ?: "192.168.1.14"
        set(v) = sp.edit().putString("ip", v).apply()

    /** Clé d'application du pont (le « username » de l'API v1). */
    var username: String?
        get() = sp.getString("username", null)
        set(v) = sp.edit().putString("username", v).apply()

    /** Clé partagée du flux temps réel, en hexadécimal. */
    var clientKey: String?
        get() = sp.getString("clientkey", null)
        set(v) = sp.edit().putString("clientkey", v).apply()

    var configId: String?
        get() = sp.getString("config", null)
        set(v) = sp.edit().putString("config", v).apply()

    /** 0,3 à 2 : combien la lumière réagit à un même volume. */
    var sensitivity: Float
        get() = sp.getFloat("sensitivity", 1f)
        set(v) = sp.edit().putFloat("sensitivity", v).apply()

    var maxBrightness: Float
        get() = sp.getFloat("maxbri", 1f)
        set(v) = sp.edit().putFloat("maxbri", v).apply()

    /** 0 = stroboscope coupé, 1 = au maximum autorisé. Ne sert que sur les drops. */
    var strobe: Float
        get() = sp.getFloat("strobe", 0.3f)
        set(v) = sp.edit().putFloat("strobe", v).apply()

    /** Avance donnée aux effets pour compenser le retard des ampoules, en ms. */
    var latencyMs: Int
        get() = sp.getInt("latency", 70)
        set(v) = sp.edit().putInt("latency", v).apply()

    var palette: String
        get() = sp.getString("palette", "auto") ?: "auto"
        set(v) = sp.edit().putString("palette", v).apply()

    var dropFx: Boolean
        get() = sp.getBoolean("dropfx", true)
        set(v) = sp.edit().putBoolean("dropfx", v).apply()

    /** 0 = ambiance (la lumière bouge à peine), 1 = fête (respiration marquée sur les temps). */
    var intensity: Float
        get() = sp.getFloat("intensity", 0.3f)
        set(v) = sp.edit().putFloat("intensity", v).apply()

    /** Couleurs tirées de la jaquette du morceau en cours plutôt que de la palette. */
    var useCover: Boolean
        get() = sp.getBoolean("cover", true)
        set(v) = sp.edit().putBoolean("cover", v).apply()

    /** Le seul réglage : stroboscope sur les drops, oui ou non (sécurité). */
    var strobeOn: Boolean
        get() = sp.getBoolean("strobeon", true)
        set(v) = sp.edit().putBoolean("strobeon", v).apply()

    val paired: Boolean get() = !username.isNullOrBlank() && !clientKey.isNullOrBlank()
}
