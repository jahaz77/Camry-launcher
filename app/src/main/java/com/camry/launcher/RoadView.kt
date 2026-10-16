package com.camry.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Panel central: velocidad, camino en perspectiva con líneas que avanzan
 * según la velocidad del GPS, y el Camry visto desde atrás.
 */
class RoadView(ctx: Context, private val skin: LauncherTheme) : View(ctx) {

    var speedKmh = 0f
        set(v) {
            if (field != v) { field = v; if (photo != null) invalidate() }
        }
    var heading = "Esperando GPS"
        set(v) {
            if (field != v) { field = v; if (photo != null) invalidate() }
        }

    /** Foto del auto para los temas con centro "foto" (sin animación). */
    private val photo: Bitmap? = if (skin.centerMode == "foto") loadPhoto(ctx, skin.carImage) else null
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Foto del Camry visto desde atrás para el tema del camino (si no hay, se dibuja uno). */
    private val rearPhoto: Bitmap? = if (photo == null) loadPhoto(ctx, "asset:carros/camry_atras.png") else null

    private var offset = 0f
    private var bobTime = 0f
    private var lastFrame = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val roadPath = Path()
    private val car = CarPainter()

    override fun onDraw(c: Canvas) {
        photo?.let { drawPhotoMode(c, it); return }
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1000f).coerceAtMost(0.1f)
        lastFrame = now
        val moving = speedKmh >= 1f
        offset = (offset + (speedKmh / 3.6f) * dt * 0.45f) % 10000f
        if (moving) bobTime += dt

        val w = width.toFloat()
        val h = height.toFloat()
        val s = h / 484f
        val cx = w / 2f
        val vpY = h * 0.31f

        c.drawColor(skin.road)

        // Resplandor en el horizonte
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, vpY, w * 0.55f,
            intArrayOf(alpha(skin.accent, 90), alpha(skin.accent, 0)), null, Shader.TileMode.CLAMP
        )
        c.save()
        c.scale(1f, 0.28f, cx, vpY)
        c.drawCircle(cx, vpY, w * 0.55f, paint)
        c.restore()

        // Asfalto
        val topHalf = 8f * s
        val botHalf = 266f * s
        roadPath.reset()
        roadPath.moveTo(cx - topHalf, vpY)
        roadPath.lineTo(cx + topHalf, vpY)
        roadPath.lineTo(cx + botHalf, h)
        roadPath.lineTo(cx - botHalf, h)
        roadPath.close()
        paint.shader = LinearGradient(0f, vpY, 0f, h, skin.road, mix(skin.road, Color.WHITE, 0.08f), Shader.TileMode.CLAMP)
        c.drawPath(roadPath, paint)
        paint.shader = null

        // Orillas iluminadas
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 2.5f * s
        paint.color = alpha(skin.accent, 230)
        c.drawLine(cx - topHalf, vpY, cx - botHalf, h, paint)
        c.drawLine(cx + topHalf, vpY, cx + botHalf, h, paint)

        // Líneas de carril (avanzan con la velocidad)
        paint.strokeCap = Paint.Cap.BUTT
        dashes(c, cx - 3f * s, cx - 92f * s, vpY, h, 4f, 2f, 4f * s, alpha(skin.text, 200))
        dashes(c, cx + 3f * s, cx + 92f * s, vpY, h, 4f, 2f, 4f * s, alpha(skin.text, 200))
        // Reflectores en las orillas
        dashes(c, cx - 10f * s, cx - 284f * s, vpY, h, 7f, 0.5f, 3f * s, alpha(Color.WHITE, 110))
        dashes(c, cx + 10f * s, cx + 284f * s, vpY, h, 7f, 0.5f, 3f * s, alpha(Color.WHITE, 110))

        // Desvanecido hacia el horizonte
        paint.style = Paint.Style.FILL
        val fadeTop = vpY - 10f * s
        paint.shader = LinearGradient(
            0f, fadeTop, 0f, fadeTop + (h - fadeTop) * 0.45f,
            skin.road, alpha(skin.road, 0), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, fadeTop, w, h, paint)
        paint.shader = null

        drawSpeed(c, cx, s)

        // El auto
        val bob = if (moving) (sin(bobTime * 2.0 * Math.PI / 0.7) * 1.5 * s).toFloat() else 0f
        val rear = rearPhoto
        if (rear != null) {
            val carW = 236f * s
            val carH = carW * rear.height / rear.width
            val bottom = h - 18f * s
            paint.style = Paint.Style.FILL
            // sombra y reflejo de las calaveras en el asfalto
            paint.color = Color.argb(170, 0, 0, 0)
            c.drawOval(RectF(cx - carW * 0.52f, bottom - 10f * s, cx + carW * 0.52f, bottom + 8f * s), paint)
            paint.color = Color.argb(if (moving) 45 else 85, 255, 40, 60)
            c.drawOval(RectF(cx - carW * 0.45f, bottom - 4f * s, cx + carW * 0.45f, bottom + 12f * s), paint)
            c.drawBitmap(rear, null, RectF(cx - carW / 2f, bottom - carH + bob, cx + carW / 2f, bottom + bob), photoPaint)
        } else {
            val carW = 210f * s
            val k = carW / 200f
            c.save()
            c.translate(cx - carW / 2f, h - 26f * s - 126f * s + bob)
            c.scale(k, k)
            car.draw(c, skin.carBody, skin.carRoof, braking = !moving)
            c.restore()
        }

        if (isShown) postInvalidateOnAnimation()
    }

    /** Tema "foto": la foto real del auto, quieta, con el velocímetro encima. */
    private fun drawPhotoMode(c: Canvas, bmp: Bitmap) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = h / 484f
        val cx = w / 2f
        c.drawColor(skin.road)

        // Resplandor detrás del auto
        val glowY = h * 0.66f
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, glowY, w * 0.6f,
            intArrayOf(alpha(skin.accent, if (skin.isLight) 45 else 80), alpha(skin.accent, 0)), null, Shader.TileMode.CLAMP
        )
        c.save()
        c.scale(1f, 0.55f, cx, glowY)
        c.drawCircle(cx, glowY, w * 0.6f, paint)
        c.restore()
        paint.shader = null

        // Tamaño del auto: lo más grande posible debajo del velocímetro
        val aspect = bmp.width.toFloat() / bmp.height
        var carW = w * 0.94f
        var carH = carW / aspect
        val maxH = h - 150f * s - 24f * s
        if (carH > maxH) { carH = maxH; carW = carH * aspect }
        val bottom = h - 24f * s
        val left = cx - carW / 2f

        // Sombra en el piso
        paint.shader = RadialGradient(
            cx, bottom - 6f * s, carW * 0.5f,
            intArrayOf(Color.argb(if (skin.isLight) 90 else 170, 0, 0, 0), Color.argb(0, 0, 0, 0)), null, Shader.TileMode.CLAMP
        )
        c.save()
        c.scale(1f, 0.08f, cx, bottom - 6f * s)
        c.drawCircle(cx, bottom - 6f * s, carW * 0.5f, paint)
        c.restore()
        paint.shader = null

        c.drawBitmap(bmp, null, RectF(left, bottom - carH, left + carW, bottom), photoPaint)
        drawSpeed(c, cx, s)
    }

    private fun drawSpeed(c: Canvas, cx: Float, s: Float) {
        textPaint.color = skin.text
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.textSize = 72f * s
        textPaint.letterSpacing = -0.02f
        c.drawText(speedKmh.roundToInt().toString(), cx, 22f * s + 58f * s, textPaint)
        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textPaint.color = skin.textDim
        textPaint.textSize = 14f * s
        textPaint.letterSpacing = 0.14f
        c.drawText("KM/H", cx, 22f * s + 84f * s, textPaint)
        textPaint.textSize = 13f * s
        textPaint.letterSpacing = 0f
        c.drawText(heading, cx, 22f * s + 108f * s, textPaint)
    }

    /** Dibuja segmentos en perspectiva real: z = 1 es la parte baja de la pantalla. */
    private fun dashes(
        c: Canvas, xTop: Float, xBot: Float, vpY: Float, bottom: Float,
        period: Float, len: Float, baseWidth: Float, color: Int
    ) {
        paint.style = Paint.Style.STROKE
        paint.color = color
        var z = period - (offset % period) - period
        while (z < 45f) {
            val z2 = z + len
            if (z2 > 1f) {
                val f1 = 1f / max(z, 1f)
                val f2 = 1f / z2
                val y1 = vpY + (bottom - vpY) * f1
                val y2 = vpY + (bottom - vpY) * f2
                val x1 = xTop + (xBot - xTop) * f1
                val x2 = xTop + (xBot - xTop) * f2
                paint.strokeWidth = max(1f, baseWidth * f1)
                c.drawLine(x1, y1, x2, y2, paint)
            }
            z += period
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrame = 0L
        postInvalidateOnAnimation()
    }

    companion object {
        fun loadPhoto(ctx: Context, path: String?): Bitmap? = runCatching {
            when {
                path == null -> null
                path.startsWith("asset:") -> ctx.assets.open(path.removePrefix("asset:")).use { BitmapFactory.decodeStream(it) }
                else -> BitmapFactory.decodeFile(path)
            }
        }.getOrNull()

        fun alpha(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        fun mix(a: Int, b: Int, t: Float) = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt()
        )
    }
}

/** Toyota Camry 2008 visto desde atrás, en un lienzo de 200 x 120. */
class CarPainter {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private val mirrorL = Path().apply { moveTo(17f, 50f); quadTo(8f, 49f, 8f, 56f); lineTo(19f, 58f); close() }
    private val roof = Path().apply {
        moveTo(42f, 62f); lineTo(58f, 26f); quadTo(62f, 18f, 72f, 18f); lineTo(128f, 18f)
        quadTo(138f, 18f, 142f, 26f); lineTo(158f, 62f); close()
    }
    private val glass = Path().apply {
        moveTo(54f, 59f); lineTo(67f, 30f); quadTo(69f, 25f, 76f, 25f); lineTo(124f, 25f)
        quadTo(131f, 25f, 133f, 30f); lineTo(146f, 59f); close()
    }
    private val body = Path().apply {
        moveTo(12f, 98f); quadTo(10f, 73f, 27f, 63f); quadTo(42f, 58f, 100f, 58f); quadTo(158f, 58f, 173f, 63f)
        quadTo(190f, 73f, 188f, 98f); quadTo(188f, 108f, 178f, 108f); lineTo(22f, 108f); quadTo(12f, 108f, 12f, 98f); close()
    }
    private val shutLine = Path().apply { moveTo(28f, 63.5f); quadTo(100f, 59.5f, 172f, 63.5f) }
    private val tailL = Path().apply {
        moveTo(14f, 71f); quadTo(16f, 64f, 27f, 63f); lineTo(56f, 64.5f); quadTo(61f, 66f, 59f, 72f)
        lineTo(57f, 80f); quadTo(55f, 83f, 50f, 83f); lineTo(19f, 84.5f); quadTo(13f, 84.5f, 13f, 80f); close()
    }
    private val lensL = Path().apply { moveTo(17f, 72f); lineTo(54f, 71f); lineTo(53f, 76f); lineTo(17f, 77f); close() }
    private val bumper = Path().apply { moveTo(16f, 95f); quadTo(100f, 99f, 184f, 95f) }

    private val mirrorR = flip(mirrorL)
    private val tailR = flip(tailL)
    private val lensR = flip(lensL)

    private fun flip(src: Path): Path {
        val out = Path()
        val m = Matrix()
        m.setScale(-1f, 1f, 100f, 0f)
        src.transform(m, out)
        return out
    }

    fun draw(c: Canvas, bodyColor: Int, roofColor: Int, braking: Boolean) {
        p.shader = null
        p.style = Paint.Style.FILL

        // Sombra y reflejo rojo en el piso
        p.color = Color.argb(166, 0, 0, 0)
        c.drawOval(RectF(6f, 103f, 194f, 121f), p)
        p.color = Color.argb(if (braking) 70 else 40, 255, 42, 61)
        c.drawOval(RectF(22f, 108f, 178f, 120f), p)

        // Llantas
        p.color = Color.rgb(13, 15, 19)
        c.drawRoundRect(RectF(20f, 92f, 48f, 114f), 5f, 5f, p)
        c.drawRoundRect(RectF(152f, 92f, 180f, 114f), 5f, 5f, p)

        // Espejos
        p.color = RoadView.mix(bodyColor, Color.BLACK, 0.08f)
        c.drawPath(mirrorL, p)
        c.drawPath(mirrorR, p)

        // Techo y medallón
        p.shader = LinearGradient(0f, 18f, 0f, 62f, roofColor, RoadView.mix(roofColor, Color.BLACK, 0.25f), Shader.TileMode.CLAMP)
        c.drawPath(roof, p)
        p.shader = LinearGradient(0f, 25f, 0f, 59f, Color.rgb(42, 58, 82), Color.rgb(11, 14, 19), Shader.TileMode.CLAMP)
        c.drawPath(glass, p)

        // Carrocería
        p.shader = LinearGradient(
            0f, 58f, 0f, 108f,
            intArrayOf(RoadView.mix(bodyColor, Color.WHITE, 0.6f), RoadView.mix(bodyColor, Color.BLACK, 0.06f), RoadView.mix(bodyColor, Color.BLACK, 0.36f)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawPath(body, p)
        p.shader = null

        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f
        p.color = Color.WHITE
        c.drawPath(shutLine, p)
        p.style = Paint.Style.FILL

        // Calaveras
        if (braking) {
            p.color = Color.argb(60, 255, 40, 60)
            c.drawOval(RectF(4f, 58f, 70f, 92f), p)
            c.drawOval(RectF(130f, 58f, 196f, 92f), p)
        }
        p.color = Color.rgb(200, 16, 46)
        c.drawPath(tailL, p)
        c.drawPath(tailR, p)
        p.color = if (braking) Color.argb(230, 255, 110, 125) else Color.argb(190, 255, 90, 110)
        c.drawPath(lensL, p)
        c.drawPath(lensR, p)
        p.color = Color.argb(204, 241, 243, 245)
        c.drawRect(40f, 77f, 52f, 81f, p)
        c.drawRect(148f, 77f, 160f, 81f, p)

        // Moldura cromada y placa
        p.shader = LinearGradient(0f, 69f, 0f, 74f, Color.rgb(244, 246, 248), Color.rgb(127, 136, 146), Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(60f, 69f, 140f, 74f), 2.5f, 2.5f, p)
        p.shader = null
        p.color = Color.rgb(223, 228, 234)
        c.drawRoundRect(RectF(81f, 77f, 119f, 91f), 2f, 2f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.8f
        p.color = Color.rgb(155, 163, 173)
        c.drawRoundRect(RectF(81f, 77f, 119f, 91f), 2f, 2f, p)

        // Defensa
        p.strokeWidth = 1f
        c.drawPath(bumper, p)
        p.style = Paint.Style.FILL
        p.color = Color.argb(153, 42, 48, 56)
        c.drawRoundRect(RectF(26f, 101f, 174f, 105f), 2f, 2f, p)
    }
}
