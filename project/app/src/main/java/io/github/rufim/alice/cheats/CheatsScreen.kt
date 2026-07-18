package io.github.rufim.alice.cheats

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rufim.alice.NativeBridge
import io.github.rufim.alice.ui.AliceColors
import io.github.rufim.alice.ui.LabeledSwitch
import io.github.rufim.alice.ui.ScreenScaffold

/**
 * Состояние читов — объект процесса: переживает закрытие экрана, чтобы работал
 * цикл «Поиск → изменить в игре → Сузить» и сохранялись введённые значения.
 */
object CheatSession {
    data class Row(val slot: Int, val varno: Int, val name: String, val value: Int,
                   val display: String = name)

    var filter by mutableStateOf("")
    var scanValue by mutableStateOf("")
    var rows by mutableStateOf(listOf<Row>())
    var status by mutableStateOf("")
    var translateNames by mutableStateOf(false)

    /** Перевод имён (ставится активити при включённом ML Kit); null — без перевода. */
    var translator: ((String, (String) -> Unit) -> Unit)? = null

    private fun parse(lines: Array<String>): List<Row> = lines.mapNotNull {
        val p = it.split('\t')
        if (p.size == 4) Row(p[0].toInt(), p[1].toInt(), p[2], p[3].toInt()) else null
    }

    private fun applyRows(newRows: List<Row>) {
        rows = newRows
        val tr = translator ?: return
        newRows.forEach { row ->
            tr(row.name) { translated ->
                rows = rows.map {
                    if (it.slot == row.slot && it.varno == row.varno) it.copy(display = translated)
                    else it
                }
            }
        }
    }

    fun reload() {
        val res = NativeBridge.cheatList(filter)
        status = "глобалов: ${res.size}"
        applyRows(parse(res))
    }

    /** @return false, если в поле поиска не число. */
    fun scan(narrow: Boolean): Boolean {
        val v = scanValue.toIntOrNull() ?: return false
        val res = NativeBridge.cheatScan(v, narrow)
        val total = res.firstOrNull()?.removePrefix("TOTAL:")?.toIntOrNull() ?: 0
        status = "кандидатов: $total" +
            if (total > res.size - 1) " (показаны первые ${res.size - 1})" else ""
        applyRows(parse(res.drop(1).toTypedArray()))
        return true
    }

    fun write(row: Row, value: Int): Boolean {
        if (!NativeBridge.cheatWrite(row.slot, row.varno, value)) return false
        rows = rows.map {
            if (it.slot == row.slot && it.varno == row.varno) it.copy(value = value) else it
        }
        return true
    }
}

/** Экран читов: скан по значению, браузер переменных с фильтром, правка по тапу. */
@Composable
fun CheatsScreen(
    onTranslateToggle: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var editRow by remember { mutableStateOf<CheatSession.Row?>(null) }

    ScreenScaffold(title = "Читы", onBack = onBack) {
        if (landscape) {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(2f)) {
                    CheatControls(onTranslateToggle)
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(2.dp)
                        .padding(vertical = 6.dp)
                        .background(AliceColors.TextSecondary.copy(alpha = 0.5f))
                )
                CheatList(modifier = Modifier.weight(3f).padding(start = 8.dp)) { editRow = it }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                CheatControls(onTranslateToggle)
                CheatList(modifier = Modifier.weight(1f)) { editRow = it }
            }
        }
    }

    editRow?.let { row ->
        EditValueDialog(row, onDismiss = { editRow = null })
    }
}

@Composable
private fun CheatControls(onTranslateToggle: (Boolean) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LabeledSwitch("Переводить имена (ML Kit)", CheatSession.translateNames) { on ->
            CheatSession.translateNames = on
            onTranslateToggle(on)
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = CheatSession.scanValue,
                onValueChange = { CheatSession.scanValue = it },
                placeholder = { Text("значение", color = AliceColors.TextSecondary) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { CheatSession.scan(narrow = false) },
                modifier = Modifier.padding(start = 6.dp)) { Text("Поиск") }
            Button(onClick = { CheatSession.scan(narrow = true) },
                modifier = Modifier.padding(start = 6.dp)) { Text("Сузить") }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = CheatSession.filter,
                onValueChange = { CheatSession.filter = it },
                placeholder = { Text("фильтр по имени", color = AliceColors.TextSecondary) },
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { CheatSession.reload() },
                modifier = Modifier.padding(start = 6.dp)) { Text("Список") }
        }
        Text(CheatSession.status, color = AliceColors.ValueGreen, fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun CheatList(modifier: Modifier = Modifier, onEdit: (CheatSession.Row) -> Unit) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(CheatSession.rows) { row ->
            Text(
                "${row.display} = ${row.value}",
                color = AliceColors.TextPrimary,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEdit(row) }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun EditValueDialog(row: CheatSession.Row, onDismiss: () -> Unit) {
    var value by remember(row) { mutableStateOf(row.value.toString()) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.display) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; error = false },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (error) Text("не записалось", color = AliceColors.Accent, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = value.toIntOrNull() ?: return@TextButton
                if (CheatSession.write(row, v)) onDismiss() else error = true
            }) { Text("Записать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
