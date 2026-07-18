package io.github.rufim.alice

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Color
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * Содержимое чит-диалога: браузер глобальных переменных с фильтром по имени,
 * скан по значению в стиле Cheat Engine (Поиск → изменить в игре → Сузить),
 * редактирование значения по тапу.
 */
class CheatPanel(private val activity: Activity, private val prefs: SharedPreferences) {
    data class Row(val slot: Int, val varno: Int, val name: String, var value: Int) {
        var display: String = name
        override fun toString() = "$display = $value"
    }

    private val rows = mutableListOf<Row>()
    private val adapter = object : ArrayAdapter<Row>(
        activity, android.R.layout.simple_list_item_1, rows) {
        override fun getView(pos: Int, convert: android.view.View?, parent: ViewGroup): android.view.View {
            val v = super.getView(pos, convert, parent) as TextView
            v.setTextColor(Color.WHITE)
            v.textSize = 14f
            return v
        }
    }

    private val info = TextView(activity).apply {
        setTextColor(Color.rgb(150, 200, 160))
    }

    private val filterInput = EditText(activity).apply {
        hint = "фильтр по имени"
        setHintTextColor(Color.GRAY)
        setTextColor(Color.BLACK)   // фон поля светлый — текст чёрным, иначе не видно
    }

    private val scanInput = EditText(activity).apply {
        hint = "значение"
        setHintTextColor(Color.GRAY)
        setTextColor(Color.BLACK)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
    }

    // элементы ввода (поиск/фильтр/статус)
    private val controls = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(scanInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(activity).apply {
                text = "Поиск"; setOnClickListener { scan(narrow = false) }
            })
            addView(Button(activity).apply {
                text = "Сузить"; setOnClickListener { scan(narrow = true) }
            })
        })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(filterInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(activity).apply {
                text = "Список"; setOnClickListener { reload() }
            })
        })
        addView(info)
    }

    // список значений переменных
    private val list = ListView(activity).apply {
        adapter = this@CheatPanel.adapter
        setOnItemClickListener { _, _, pos, _ -> edit(rows[pos]) }
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    private fun divider(horizontal: Boolean) = android.view.View(activity).apply {
        setBackgroundColor(Color.rgb(80, 90, 110))
        layoutParams = if (horizontal)
            LinearLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT)
                .apply { leftMargin = dp(8); rightMargin = dp(8) }
        else
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
                .apply { topMargin = dp(6); bottomMargin = dp(6) }
    }

    /**
     * Ландшафт: элементы ввода слева, список значений справа, между ними разделитель.
     * Портрет: ввод сверху, список снизу.
     */
    val view: LinearLayout = LinearLayout(activity).apply {
        val landscape = activity.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            orientation = LinearLayout.HORIZONTAL
            addView(controls, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f))
            addView(divider(true))
            addView(list, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f))
        } else {
            orientation = LinearLayout.VERTICAL
            addView(controls)
            addView(divider(false))
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    /** Хук для перевода имён (Task ML Kit); по умолчанию — без перевода. */
    var translateNames: ((List<Row>, () -> Unit) -> Unit)? = null

    /** Добавить элемент (напр. переключатель перевода) в начало блока ввода. */
    fun prependControl(v: android.view.View) = controls.addView(v, 0)

    private fun parse(lines: Array<String>): List<Row> = lines.mapNotNull {
        val p = it.split('\t')
        if (p.size == 4) Row(p[0].toInt(), p[1].toInt(), p[2], p[3].toInt()) else null
    }

    private fun applyRows(newRows: List<Row>) {
        rows.clear()
        rows.addAll(newRows)
        adapter.notifyDataSetChanged()
        translateNames?.invoke(rows) { adapter.notifyDataSetChanged() }
    }

    fun reload() {
        val res = NativeBridge.cheatList(filterInput.text.toString())
        info.text = "глобалов: ${res.size}"
        applyRows(parse(res))
    }

    private fun scan(narrow: Boolean) {
        val v = scanInput.text.toString().toIntOrNull()
        if (v == null) {
            Toast.makeText(activity, "введите число", Toast.LENGTH_SHORT).show()
            return
        }
        val res = NativeBridge.cheatScan(v, narrow)
        val total = res.firstOrNull()?.removePrefix("TOTAL:")?.toIntOrNull() ?: 0
        info.text = "кандидатов: $total" +
            if (total > res.size - 1) " (показаны первые ${res.size - 1})" else ""
        applyRows(parse(res.drop(1).toTypedArray()))
    }

    private fun edit(row: Row) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(row.value.toString())
            setTextColor(Color.BLACK)   // фон диалога светлый — текст чёрным
        }
        AlertDialog.Builder(activity)
            .setTitle(row.display)
            .setView(input)
            .setPositiveButton("Записать") { _, _ ->
                input.text.toString().toIntOrNull()?.let { v ->
                    if (NativeBridge.cheatWrite(row.slot, row.varno, v)) {
                        row.value = v
                        adapter.notifyDataSetChanged()
                    } else {
                        Toast.makeText(activity, "не записалось", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
