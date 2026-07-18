package io.github.rufim.alice.engine

import io.github.rufim.alice.R
import io.github.rufim.alice.NativeBridge
import io.github.rufim.alice.tts.*
import io.github.rufim.alice.cheats.*
import io.github.rufim.alice.launcher.*
import io.github.rufim.alice.engine.*
import io.github.rufim.alice.ui.*

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Боковая панель поверх игры: тонкая полупрозрачная «ручка» у правого края,
 * тап по ней выдвигает узкий drawer со списком кнопок (TTS, Читы).
 * Каждая кнопка открывает свой полноэкранный диалог. Тап мимо панели закрывает её.
 */
class EdgePanel(activity: Activity, root: ViewGroup) {
    val prefs: SharedPreferences = activity.getSharedPreferences("bridge_prefs", 0)
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val buttons = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.argb(235, 30, 34, 48))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        visibility = View.GONE
        isClickable = true   // не пропускать тапы в игру
    }

    private val scrim = View(activity).apply {
        setBackgroundColor(Color.argb(60, 0, 0, 0))
        visibility = View.GONE
        setOnClickListener { close() }
    }

    init {
        val handle = View(activity).apply {
            setBackgroundColor(Color.argb(90, 255, 255, 255))
            setOnClickListener { toggle() }
        }
        root.addView(handle, FrameLayout.LayoutParams(dp(10), dp(48),
            Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(buttons, FrameLayout.LayoutParams(dp(220),
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
    }

    private fun toggle() {
        if (buttons.visibility == View.VISIBLE) close() else open()
    }

    private fun open() {
        buttons.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
    }

    fun close() {
        buttons.visibility = View.GONE
        scrim.visibility = View.GONE
    }

    /** Добавить кнопку в панель. onClick вызывается после закрытия панели. */
    fun addButton(title: String, onClick: () -> Unit) {
        buttons.addView(Button(buttons.context).apply {
            text = title
            setOnClickListener { close(); onClick() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4); bottomMargin = dp(4)
        })
    }
}
