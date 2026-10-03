package dev.langanay.drop

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/*
 * Verre liquide de Drop : la recette validée pour Fynex (30/09/2026, tranche adoucie le 01/10), reprise en shader.
 * Une lentille claire et presque nette, dont la déviation croît vers le contour (t^2.2), toujours vers l'intérieur,
 * une seule passe (pas d'irisation), un léger gain de lumière ; la lumière blanche sur la tranche, vive en haut à
 * gauche, reprise en bas à droite. Aucune réaction au toucher : le verre vit par ce qui passe dessous.
 *
 * Ce qui passe dessous, c'est le fond vivant : un halo lent aux couleurs de la pochette. Comme c'est nous qui le
 * dessinons, chaque élément de verre le redessine à sa place, à travers son propre filtre (Android 13 et plus ;
 * avant, un verre teinté simple).
 */

/** Le fond vivant : deux couleurs et la phase de la dérive des halos, lues pendant le dessin. */
internal class FondVivant(val c1: State<Color>, val c2: State<Color>, val phase: State<Float>) {
    var taille by mutableStateOf(Size.Zero)

    fun dessiner(s: DrawScope) = with(s) {
        val w = taille.width
        val h = taille.height
        if (w <= 0f || h <= 0f) return@with
        drawRect(Fond, size = taille)
        val p = phase.value
        val m = maxOf(w, h)
        fun halo(c: Color, x: Float, y: Float, r: Float, a: Float) =
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = a), c.copy(alpha = a * 0.35f), Color.Transparent), Offset(x, y), r), r, Offset(x, y))
        halo(c1.value, w * (0.05f + 0.25f * p), h * (0.02f + 0.12f * p), m * 0.62f, 0.55f)
        halo(c2.value, w * (1.0f - 0.22f * p), h * (0.45f + 0.15f * p), m * 0.55f, 0.45f)
        halo(c1.value, w * (0.35f + 0.3f * (1 - p)), h * (1.05f - 0.1f * p), m * 0.5f, 0.32f)
    }
}

internal val LocalFond = staticCompositionLocalOf<FondVivant?> { null }

/** Pose le fond vivant derrière tout l'écran ; les couleurs glissent en 2,5 s quand le morceau change. */
@Composable
internal fun AvecFondVivant(c1: Color, c2: Color, content: @Composable () -> Unit) {
    val a1 = animateColorAsState(c1, tween(2500), label = "halo1")
    val a2 = animateColorAsState(c2, tween(2500), label = "halo2")
    val phase = rememberInfiniteTransition(label = "fond").animateFloat(
        0f, 1f, infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Reverse), label = "derive",
    )
    val fond = remember { FondVivant(a1, a2, phase) }
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { fond.taille = Size(it.size.width.toFloat(), it.size.height.toFloat()) }
            .drawBehind { fond.dessiner(this) },
    ) {
        CompositionLocalProvider(LocalFond provides fond) { content() }
    }
}

private const val LENTILLE = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float scale;
half4 main(float2 p) {
    float2 d = p - size * 0.5;
    float2 q = max(abs(d) - (size * 0.5 - radius), 0.0);
    float len = length(q);
    float dedans = radius - len;
    float2 off = float2(0.0);
    if (dedans > 0.0 && len > 0.0) {
        float2 n = q * sign(d) / len;
        off = -n * pow(1.0 - dedans / radius, 2.2) * scale * 0.5;
    }
    half4 c = content.eval(p + off);
    half l = dot(c.rgb, half3(0.2126, 0.7152, 0.0722));
    c.rgb = mix(half3(l), c.rgb, 1.5);
    c.rgb = c.rgb * 1.06 + half3(0.035, 0.035, 0.04) * c.a;
    return c;
}
"""

/** Tranche : l'anneau conique de la recette (vif à 315°, repris à 135°), en angles Compose (0 = à droite). */
private fun tranche(o: Float) = arrayOf(
    0f to Color.White.copy(alpha = 0.19f * o), 0.125f to Color.White.copy(alpha = 0.5f * o),
    0.305f to Color.White.copy(alpha = 0.05f * o), 0.625f to Color.White.copy(alpha = 0.9f * o),
    0.75f to Color.White.copy(alpha = 0.18f * o), 0.945f to Color.White.copy(alpha = 0.05f * o),
    1f to Color.White.copy(alpha = 0.19f * o),
)

/**
 * Verre liquide sur un élément de rayon [rayon]. [lentille] : la lentille de la recette (éléments flottants,
 * boutons) ; sans, un verre dépoli plus calme pour les grandes cartes. [teinte] colore le verre (bouton Go).
 */
internal fun Modifier.verre(rayon: Dp, lentille: Boolean = true, teinte: Color = Color.White.copy(alpha = 0.06f)): Modifier = composed {
    val fond = LocalFond.current
    var pos by remember { mutableStateOf(Offset.Zero) }
    val couche = rememberGraphicsLayer()
    val cache = remember { arrayOfNulls<Any>(2) }
    this
        .onGloballyPositioned { pos = it.positionInRoot() }
        .drawBehind {
            val r = min(rayon.toPx(), size.minDimension / 2)
            val forme = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r))) }
            if (fond != null && Build.VERSION.SDK_INT >= 33) {
                val cle = "${size.width}x${size.height}x$r"
                if (cache[0] != cle) {
                    val flou = 1.6f * density * (if (lentille) 1f else 10f)
                    val blur = RenderEffect.createBlurEffect(flou, flou, Shader.TileMode.CLAMP)
                    val shader = RuntimeShader(LENTILLE).apply {
                        setFloatUniform("size", size.width, size.height)
                        setFloatUniform("radius", r)
                        setFloatUniform("scale", if (lentille) min(size.height, r * 2) * 0.8f else 0f)
                    }
                    cache[0] = cle
                    cache[1] = RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"), blur)
                    couche.renderEffect = (cache[1] as RenderEffect).asComposeRenderEffect()
                }
                couche.record { translate(-pos.x, -pos.y) { fond.dessiner(this) } }
                clipPath(forme) { drawLayer(couche) }
            }
            // Corps clair, filet, reflets en haut à gauche et en bas à droite, puis la tranche conique.
            drawPath(forme, teinte)
            val px = density
            drawRoundRect(Color.White.copy(alpha = 0.12f), cornerRadius = CornerRadius(r), style = Stroke(0.5f * px))
            drawRoundRect(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = if (lentille) 0.4f else 0.22f), Color.Transparent, Color.Transparent, Color.White.copy(alpha = if (lentille) 0.16f else 0.08f)),
                    Offset.Zero, Offset(size.width, size.height),
                ),
                topLeft = Offset(0.75f * px, 0.75f * px), size = Size(size.width - 1.5f * px, size.height - 1.5f * px),
                cornerRadius = CornerRadius(r - 0.75f * px), style = Stroke(1.5f * px),
            )
            drawRoundRect(
                Brush.sweepGradient(colorStops = tranche(if (lentille) 0.4f else 0.22f), center = center),
                topLeft = Offset(0.75f * px, 0.75f * px), size = Size(size.width - 1.5f * px, size.height - 1.5f * px),
                cornerRadius = CornerRadius(r - 0.75f * px), style = Stroke(1.5f * px),
            )
        }
}
