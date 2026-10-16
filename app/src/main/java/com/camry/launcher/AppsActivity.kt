package com.camry.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

/** Cajón con todas las apps instaladas. */
class AppsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val skin = ThemeManager.current(this)
        val dm = resources.displayMetrics
        val s = min(maxOf(dm.widthPixels, dm.heightPixels) / 1024f, min(dm.widthPixels, dm.heightPixels) / 600f)
        fun px(v: Float) = (v * s).roundToInt()

        val apps = AppUtils.launchables(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(skin.background)
            setPadding(px(24f), px(16f), px(24f), px(16f))
        }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "Aplicaciones"
            setTextColor(skin.text)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 24f * s)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "Cerrar"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f * s)
            background = GradientDrawable().apply { cornerRadius = 14f * s; setColor(skin.accent) }
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(px(120f), px(48f)))
        root.addView(header)

        val grid = GridView(this).apply {
            numColumns = 7
            verticalSpacing = px(12f)
            horizontalSpacing = px(12f)
            setPadding(0, px(16f), 0, 0)
            clipToPadding = false
            adapter = object : BaseAdapter() {
                override fun getCount() = apps.size
                override fun getItem(position: Int) = apps[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val app = apps[position]
                    val cell = LinearLayout(this@AppsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(px(4f), px(10f), px(4f), px(10f))
                        background = GradientDrawable().apply { cornerRadius = 18f * s; setColor(skin.card) }
                        layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(112f))
                    }
                    cell.addView(ImageView(this@AppsActivity).apply { setImageDrawable(app.icon) }, LinearLayout.LayoutParams(px(56f), px(56f)))
                    cell.addView(TextView(this@AppsActivity).apply {
                        text = app.label
                        setTextColor(skin.text)
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f * s)
                        gravity = Gravity.CENTER
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, px(8f), 0, 0)
                    })
                    return cell
                }
            }
            setOnItemClickListener { _, _, position, _ ->
                AppUtils.launch(this@AppsActivity, apps[position].component)
                finish()
            }
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }
}
