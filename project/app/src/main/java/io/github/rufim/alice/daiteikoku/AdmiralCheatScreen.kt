package io.github.rufim.alice.daiteikoku

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rufim.alice.ui.AliceColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Редактор активных адмиралов Daiteikoku поверх файла сейва (.asd).
 * Тулбар: «‹ Назад» — выйти без сохранения; «✓ Сохранить» — применить и записать слот.
 * Редактируемые поля: ОК, опыт, ОЗ (текущее). Имя — только чтение.
 * Чтение/парс и запись сейва — на Dispatchers.IO (не блокируем UI).
 */
private class Editable(val adm: AdmiralRoster.Admiral) {
    var ok by mutableStateOf(adm.ok.toString())
    var xp by mutableStateOf(adm.xp.toString())
    var hp by mutableStateOf(adm.hp.toString())
}

@Composable
fun AdmiralCheatScreen(saveFile: File, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var raw by remember(saveFile) { mutableStateOf<ByteArray?>(null) }
    var enc by remember(saveFile) { mutableStateOf(false) }
    var rows by remember(saveFile) { mutableStateOf<List<Editable>?>(null) }  // null = загрузка
    var error by remember(saveFile) { mutableStateOf<String?>(null) }
    var status by remember(saveFile) { mutableStateOf("") }
    var saving by remember(saveFile) { mutableStateOf(false) }

    LaunchedEffect(saveFile) {
        val res = withContext(Dispatchers.IO) {
            runCatching {
                val (d, e) = AsdSave.read(saveFile)
                Triple(d, e, AdmiralRoster.parseActive(d))
            }
        }
        res.fold(
            { (d, e, list) -> raw = d; enc = e; rows = list.map { Editable(it) } },
            { error = it.message ?: it.toString(); rows = emptyList() },
        )
    }

    fun save() {
        val d = raw ?: return
        val rws = rows ?: return
        if (saving) return
        var changed = 0
        for (r in rws) {  // правка байтов в памяти — дёшево, можно на UI
            r.ok.toIntOrNull()?.let { if (it != r.adm.ok) { AdmiralRoster.putI32(d, r.adm.pos + AdmiralRoster.OK_OFF, it); changed++ } }
            r.xp.toIntOrNull()?.let { if (it != r.adm.xp) { AdmiralRoster.putI32(d, r.adm.pos + AdmiralRoster.XP_OFF, it); changed++ } }
            r.hp.toIntOrNull()?.let { if (it != r.adm.hp) { AdmiralRoster.putI32(d, r.adm.pos + AdmiralRoster.HP_OFF, it); changed++ } }
        }
        saving = true
        status = "сохранение…"
        scope.launch {
            val w = withContext(Dispatchers.IO) { runCatching { AsdSave.write(saveFile, d, enc) } }
            status = w.fold({ "сохранено правок: $changed — перезайди в слот" },
                            { "ошибка записи: ${it.message}" })
            saving = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(AliceColors.Background)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(AliceColors.Surface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹ Назад", fontSize = 17.sp, color = AliceColors.TextPrimary,
                modifier = Modifier.clickable { onClose() }.padding(6.dp))
            Spacer(Modifier.width(10.dp))
            Text("Адмиралы · ${saveFile.name}", fontSize = 15.sp, color = AliceColors.TextSecondary)
            Spacer(Modifier.weight(1f))
            if (rows?.isNotEmpty() == true)
                Text("✓ Сохранить", fontSize = 17.sp, color = AliceColors.Accent,
                    modifier = Modifier.clickable { save() }.padding(horizontal = 8.dp, vertical = 6.dp))
        }

        when {
            error != null ->
                Text("Не удалось прочитать сейв: $error",
                    color = AliceColors.TextSecondary, modifier = Modifier.padding(16.dp))
            rows == null ->
                Text("Загрузка…", color = AliceColors.TextSecondary, modifier = Modifier.padding(16.dp))
            rows!!.isEmpty() ->
                Text("Активных адмиралов не найдено в ${saveFile.name}",
                    color = AliceColors.TextSecondary, modifier = Modifier.padding(16.dp))
            else -> {
                if (status.isNotEmpty())
                    Text(status, color = AliceColors.Accent, fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(rows!!, key = { it.adm.pos }) { r ->
                        AdmiralRow(r)
                    }
                }
            }
        }
    }
}

/** Одна карточка адмирала. Вынесена в отдельный @Composable — рекомпозиция
 *  замкнута на свою строку (чтение r.ok/r.xp/r.hp внутри NumField). */
@Composable
private fun AdmiralRow(r: Editable) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(r.adm.name, fontSize = 16.sp, color = AliceColors.TextPrimary)
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            NumField("ОК", r.ok, Modifier.weight(1f)) { r.ok = it }
            Spacer(Modifier.width(8.dp))
            NumField("Опыт", r.xp, Modifier.weight(1f)) { r.xp = it }
            Spacer(Modifier.width(8.dp))
            NumField("ОЗ · макс ${r.adm.hpMax}", r.hp, Modifier.weight(1.3f)) { r.hp = it }
        }
    }
}

/** Лёгкое числовое поле на BasicTextField (Material3 OutlinedTextField тяжёл —
 *  51 штука в списке ощутимо лагали при скролле/вводе). */
@Composable
private fun NumField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = AliceColors.TextSecondary)
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.filter { c -> c.isDigit() }) },
            singleLine = true,
            textStyle = TextStyle(color = AliceColors.TextPrimary, fontSize = 16.sp),
            cursorBrush = SolidColor(AliceColors.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                .background(AliceColors.Surface, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}
