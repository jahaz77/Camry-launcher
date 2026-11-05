package com.camry.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator

/**
 * Animación de arranque (~4 s): tu Camry en la oscuridad, se encienden los faros,
 * una luz lo recorre y aparece "CAMRY". Se quita sola; tocarla la salta.
 */
class StartupView(ctx: Context, private val accent: Int, private val onDone: () -> Unit) : View(ctx) {

    private val car: Bitmap? = runCatching { ctx.assets.open("carros/camry.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
    // posición de los faros dentro de la foto (fracción del ancho/alto)
    private val lamps = listOf(0.545f to 0.53f, 0.958f to 0.49f)
    private var t = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private var anim: ValueAnimator? = null
    private var done = false

    init {
        setBackgroundColor(Color.rgb(4, 6, 10))
        isClickable = true
        setOnClickListener { finish() }
    }

    fun play() {
        anim = ValueAnimator.ofFloat(0f, DURATION).apply {
            duration = (DURATION * 1000).toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; alpha = if (t > 3.4f) (1f - (t - 3.4f) / 0.6f).coerceIn(0f, 1f) else 1f; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) = finish()
            })
            start()
        }
    }

    private fun finish() {
        if (done) return
        done = true
        anim?.cancel()
        (parent as? ViewGroup)?.removeView(this)
        onDone()
    }

    private fun ramp(a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
    private fun ease(x: Float) = x * x * (3 - 2 * x)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val bmp = car ?: return
        val cw = w * 0.62f
        val ch = cw * bmp.height / bmp.width
        val left = (w - cw) / 2f
        val top = h * 0.70f - ch
        val dst = RectF(left, top, left + cw, top + ch)

        // faros: parpadean y se quedan prendidos
        val lamp = when {
            t < 0.5f -> 0f
            t < 0.6f -> 1f
            t < 0.7f -> 0.15f
            else -> 0.75f + 0.25f * ease(ramp(0.7f, 1.2f))
        }

        // reflejo en el piso
        if (lamp > 0f) {
            val fy = top + ch * 0.97f
            p.shader = RadialGradient(left + cw * 0.72f, fy, cw * 0.55f,
                intArrayOf(Style.alpha(Style.lighten(accent, 0.3f), (90 * lamp).toInt()), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.save(); c.scale(1f, 0.18f, 0f, fy)
            c.drawCircle(left + cw * 0.72f, fy, cw * 0.55f, p)
            c.restore()
            p.shader = null
        }

        // el carro, oscuro al principio y luego iluminado
        val k = 0.07f + 0.93f * ease(ramp(1.0f, 2.6f)) + 0.12f * lamp * (1 - ramp(1.0f, 2.6f))
        p.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(k, k, k * 1.05f, 1f) })
        val layer = c.saveLayer(dst, null)
        c.drawBitmap(bmp, null, dst, p)
        p.colorFilter = null
        // barrido de luz por la carrocería
        val sw = ramp(1.3f, 2.5f)
        if (sw > 0f && sw < 1f) {
            val x = left - cw * 0.3f + (cw * 1.6f) * ease(sw)
            p.shader = LinearGradient(x - cw * 0.12f, 0f, x + cw * 0.12f, 0f,
                intArrayOf(Color.TRANSPARENT, Color.argb(120, 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
            c.drawRect(dst, p)
            p.xfermode = null; p.shader = null
        }
        c.restoreToCount(layer)

        // destello de los faros
        if (lamp > 0f) for ((fx, fy) in lamps) {
            val x = left + cw * fx; val y = top + ch * fy
            val r = cw * 0.11f
            p.shader = RadialGradient(x, y, r, intArrayOf(Color.argb((255 * lamp).toInt(), 255, 255, 255),
                Color.argb((120 * lamp).toInt(), 190, 220, 255), Color.TRANSPARENT), floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, p)
            // destello horizontal
            p.shader = RadialGradient(x, y, cw * 0.3f, intArrayOf(Color.argb((70 * lamp).toInt(), 200, 225, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.save(); c.scale(1f, 0.06f, x, y); c.drawCircle(x, y, cw * 0.3f, p); c.restore()
            p.shader = null
        }

        // CAMRY
        val ta = ease(ramp(2.0f, 2.8f))
        if (ta > 0f) {
            tp.textSize = h * 0.085f
            tp.letterSpacing = 0.9f - 0.45f * ta
            tp.alpha = (255 * ta).toInt()
            c.drawText("CAMRY", w / 2f, h * 0.86f, tp)
            val lw = w * 0.22f * ta
            p.color = accent; p.alpha = (230 * ta).toInt()
            c.drawRect(w / 2f - lw / 2f, h * 0.89f, w / 2f + lw / 2f, h * 0.89f + h * 0.004f, p)
            p.alpha = 255
        }
    }

    companion object { const val DURATION = 4.0f }
}
