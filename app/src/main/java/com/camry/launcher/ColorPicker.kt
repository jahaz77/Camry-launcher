package com.camry.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Rueda de color: el ángulo es el tono y la distancia al centro la intensidad (centro = blanco). */
class ColorWheel(ctx: Context, initial: Int, private val onChange: (Int) -> Unit) : View(ctx) {
    private val hsv = FloatArray(3).also { Color.colorToHSV(initial, it); it[2] = 1f }
    private var wheel: Bitmap? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    val color: Int get() = Color.HSVToColor(hsv)

    fun set(c: Int) { Color.colorToHSV(c, hsv); hsv[2] = 1f; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val r = min(w, h) / 2f
        if (r <= 0) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f; val cy = h / 2f
        val hues = IntArray(13) { Color.HSVToColor(floatArrayOf((360f - it * 30f) % 360f, 1f, 1f)) }
        p.shader = SweepGradient(cx, cy, hues, null)
        c.drawCircle(cx, cy, r, p)
        p.shader = RadialGradient(cx, cy, r, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = null
        wheel = bmp
    }

    override fun onDraw(c: Canvas) {
        wheel?.let { c.drawBitmap(it, 0f, 0f, null) }
        val r = min(width, height) / 2f
        val a = Math.toRadians(-hsv[0].toDouble())
        val x = width / 2f + (cos(a) * hsv[1] * r).toFloat()
        val y = height / 2f + (sin(a) * hsv[1] * r).toFloat()
        p.style = Paint.Style.FILL; p.color = color
        c.drawCircle(x, y, r * 0.09f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.025f; p.color = Color.WHITE
        c.drawCircle(x, y, r * 0.09f, p)
        p.strokeWidth = r * 0.01f; p.color = Color.argb(120, 0, 0, 0)
        c.drawCircle(x, y, r * 0.105f, p)
        p.style = Paint.Style.FILL
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        val r = min(width, height) / 2f
        val dx = e.x - width / 2f; val dy = e.y - height / 2f
        var deg = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())).toFloat()
        if (deg < 0) deg += 360f
        hsv[0] = deg
        hsv[1] = (hypot(dx, dy) / r).coerceIn(0f, 1f)
        invalidate()
        onChange(color)
        return true
    }
}

object ColorPicker {
    /** Diálogo con la rueda, una vista previa y los colores favoritos. */
    fun show(act: Activity, skin: LauncherTheme, s: Float, title: String, initial: Int, onPick: (Int) -> Unit) {
        fun px(v: Float) = (v * s).roundToInt()
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(24f), px(12f), px(24f), px(4f))
        }
        val preview = View(act)
        val hex = TextView(act).apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * s); setTextColor(skin.text) }
        fun show(c: Int) {
            preview.background = GradientDrawable().apply { cornerRadius = 16f * s; setColor(c); setStroke(1, Color.argb(60, 255, 255, 255)) }
            hex.text = String.format("#%06X", c and 0xFFFFFF)
        }
        val wheel = ColorWheel(act, initial) { show(it) }
        box.addView(wheel, LinearLayout.LayoutParams(px(260f), px(260f)))
        val side = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(px(22f), 0, 0, 0) }
        side.addView(preview, LinearLayout.LayoutParams(px(150f), px(70f)))
        side.addView(View(act), LinearLayout.LayoutParams(1, px(8f)))
        side.addView(hex)
        side.addView(View(act), LinearLayout.LayoutParams(1, px(18f)))
        val grid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        Profiles.PALETTE.toList().chunked(3).forEach { row ->
            val r = LinearLayout(act)
            row.forEach { col ->
                r.addView(FrameLayout(act).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col); setStroke(1, Color.argb(60, 255, 255, 255)) }
                    setOnClickListener { wheel.set(col); show(col) }
                }, LinearLayout.LayoutParams(px(38f), px(38f)).apply { setMargins(0, 0, px(10f), px(10f)) })
            }
            grid.addView(r)
        }
        side.addView(grid)
        box.addView(side)
        show(initial)
        AlertDialog.Builder(act, if (skin.isLight) android.R.style.Theme_DeviceDefault_Light_Dialog_Alert else android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title).setView(box)
            .setPositiveButton("Usar este color") { _, _ -> onPick(wheel.color) }
            .setNegativeButton("Cancelar", null).show()
    }
}
