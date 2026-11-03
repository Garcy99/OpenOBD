package com.openobd.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.openobd.MainActivity
import com.openobd.core.obd.SensorCatalog
import com.openobd.core.obd.SensorDef
import com.openobd.core.obd.UnitSystem
import com.openobd.core.obd.display
import kotlin.math.abs

/** Text for one value in the floating window, e.g. "2150" + "rpm". */
fun overlayValue(def: SensorDef?, v: Double?, units: UnitSystem): Pair<String, String> {
    if (def == null || v == null) return "–" to (def?.let { u -> u.unit.display(0.0, units).unit.symbol } ?: "")
    def.labeler?.let { return it(v) to "" }
    val d = def.unit.display(v, units)
    val decimals = if (abs(d.value) >= 100) 0 else def.decimals.coerceAtMost(1)
    return "%.${decimals}f".format(d.value) to (def.customUnit ?: d.unit.symbol)
}

/**
 * A small window over other apps (navigation, music) with three live values. Drag it anywhere; a tap opens
 * OpenOBD, the × closes it. Lives inside the connection service, so it disappears when the car disconnects.
 */
class FloatingGauges(private val context: Context) {
    companion object {
        fun canShow(context: Context) = Settings.canDrawOverlays(context)
        private const val BG = 0xE6101828.toInt()
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private val values = mutableListOf<Pair<TextView, TextView>>()
    private val names = mutableListOf<String>()
    private var keys: List<String> = emptyList()
    val isShown: Boolean get() = root != null

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)

    @SuppressLint("ClickableViewAccessibility")
    fun show(keys: List<String>, labels: (String) -> String) {
        if (root != null || !canShow(context)) return
        this.keys = keys
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(BG); cornerRadius = dp(16f) }
            val p = dp(10f).toInt()
            setPadding(p, p / 2, p / 2, p / 2)
            elevation = dp(6f)
        }
        values.clear(); names.clear()
        for (k in keys) {
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8f).toInt(), 0, dp(8f).toInt(), 0)
            }
            val value = TextView(context).apply {
                setTextColor(0xFFFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); typeface = Typeface.DEFAULT_BOLD; text = "–"
            }
            val unit = TextView(context).apply {
                setTextColor(0xFF9FB3C8.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                text = SensorCatalog.get(k)?.let { labels(it.shortName) } ?: k
            }
            names += unit.text.toString()
            cell.addView(value); cell.addView(unit)
            layout.addView(cell)
            values += value to unit
        }
        val close = TextView(context).apply {
            text = "×"; setTextColor(0xFF9FB3C8.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(dp(8f).toInt(), 0, dp(6f).toInt(), 0)
            contentDescription = labels("Close")
            setOnClickListener { hide() }
        }
        layout.addView(close)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12f).toInt(); y = dp(120f).toInt() }

        // Drag to move; a tap without movement opens the app.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        layout.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(6f) || abs(dy) > dp(6f)) moved = true
                    if (moved) { params.x = startX + dx.toInt(); params.y = startY + dy.toInt(); runCatching { wm.updateViewLayout(v, params) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        v.performClick()
                        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }
                    true
                }
                else -> false
            }
        }
        runCatching { wm.addView(layout, params) }.onSuccess { root = layout }
    }

    fun update(read: (String) -> Double?, units: UnitSystem) {
        if (root == null) return
        keys.forEachIndexed { i, k ->
            val def = SensorCatalog.get(k)
            val (text, unit) = overlayValue(def, read(k), units)
            values.getOrNull(i)?.let { (value, label) ->
                value.text = text
                val name = names.getOrElse(i) { k }
                label.text = if (unit.isBlank()) name else "$name · $unit"
            }
        }
    }

    /** What the window shows right now (value and label per cell), for tests. */
    internal fun texts(): List<String> = values.map { (v, l) -> "${v.text} ${l.text}" }

    fun hide() {
        root?.let { v -> runCatching { wm.removeView(v as View) } }
        root = null
        values.clear(); names.clear()
    }
}
