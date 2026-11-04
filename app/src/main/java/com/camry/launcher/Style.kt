package com.camry.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import kotlin.math.roundToInt

/** Estilo "vidrio" moderno, compartido por el inicio y el panel del vehículo. */
object Style {

    fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    fun mix(a: Int, b: Int, t: Float) = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).roundToInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt()
    )

    fun lighten(c: Int, t: Float) = mix(c, Color.WHITE, t)

    /** Tarjeta de vidrio: degradado vertical sutil y borde fino iluminado. */
    fun glass(skin: LauncherTheme, radiusPx: Float, strong: Boolean = false): GradientDrawable {
        val a = if (strong) 235 else 214
        val top = alpha(mix(skin.card, if (skin.isLight) Color.WHITE else Color.rgb(40, 52, 72), 0.45f), a)
        val bottom = alpha(skin.card, a)
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            cornerRadius = radiusPx
            setStroke(1, if (skin.isLight) Color.argb(26, 0, 0, 0) else Color.argb(22, 255, 255, 255))
        }
    }

    /** Relleno de botón principal: degradado del acento. */
    fun accentFill(skin: LauncherTheme, radiusPx: Float) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(lighten(skin.accent, 0.25f), skin.accent)).apply {
            cornerRadius = radiusPx
        }

    /** Sombra de color (brillo) debajo de una vista; Android 9+ permite colorearla. */
    fun glow(v: View, color: Int, elevationPx: Float) {
        v.elevation = elevationPx
        if (Build.VERSION.SDK_INT >= 28) {
            v.outlineSpotShadowColor = color
            v.outlineAmbientShadowColor = color
        }
    }
}

/** Fondo con luz ambiental: color base y dos resplandores suaves. */
class AmbientDrawable(private val skin: LauncherTheme) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(c: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        c.drawColor(skin.background)
        // resplandor principal detrás del auto
        p.shader = RadialGradient(
            w * 0.5f, h * 0.64f, w * 0.34f,
            intArrayOf(Style.alpha(skin.accent, if (skin.isLight) 40 else 62), Style.alpha(skin.accent, 0)), null, Shader.TileMode.CLAMP
        )
        c.save(); c.scale(1f, 0.9f, w * 0.5f, h * 0.64f); c.drawCircle(w * 0.5f, h * 0.64f, w * 0.34f, p); c.restore()
        // luz fría en la esquina
        p.shader = RadialGradient(
            0f, 0f, w * 0.32f,
            intArrayOf(Color.argb(if (skin.isLight) 10 else 20, 33, 212, 253), Color.argb(0, 33, 212, 253)), null, Shader.TileMode.CLAMP
        )
        c.drawCircle(0f, 0f, w * 0.32f, p)
        p.shader = null
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("API de Drawable")
    override fun getOpacity() = PixelFormat.OPAQUE
}
