package io.github.rufim.alice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rufim.alice.R

/** Полноэкранный каркас: шапка «стрелка назад + заголовок», содержимое ниже. */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AliceColors.Background)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = "Назад",
                    tint = AliceColors.TextPrimary,
                )
            }
            Text(title, fontSize = 20.sp, color = AliceColors.TextPrimary,
                modifier = Modifier.padding(start = 12.dp))
        }
        content()
    }
}

/** Строка «подпись + переключатель» во всю ширину. */
@Composable
fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 16.sp, color = AliceColors.TextPrimary,
            modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = AliceColors.Accent,
                checkedThumbColor = AliceColors.TextPrimary,
            ),
        )
    }
}

/** Подпись со значением + слайдер целых значений в диапазоне. */
@Composable
fun LabeledSlider(
    title: String,
    value: Int,
    range: IntRange,
    format: (Int) -> String = { "$it%" },
    onChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("$title: ${format(value)}", color = AliceColors.TextPrimary,
            modifier = Modifier.padding(top = 12.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

/** Заголовок-секция (стиль прежних dialogLabel). */
@Composable
fun SectionLabel(text: String) {
    Text(text, fontSize = 15.sp, color = AliceColors.TextSecondary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

/** Выпадающий список: рамка с текущим значением, по тапу — меню вариантов. */
@Composable
fun DropdownField(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxWidth()) {
        Text(
            text = options.getOrElse(selectedIndex) { "—" },
            color = AliceColors.TextPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, AliceColors.TextSecondary, RoundedCornerShape(6.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { expanded = false; onSelect(i) },
                )
            }
        }
    }
}
