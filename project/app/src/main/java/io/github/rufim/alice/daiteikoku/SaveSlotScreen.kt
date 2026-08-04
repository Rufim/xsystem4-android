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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rufim.alice.ui.AliceColors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Выбор слота сохранения перед редактором адмиралов. */
@Composable
fun SaveSlotScreen(saveDir: File, onPick: (File) -> Unit, onClose: () -> Unit) {
    val slots = remember(saveDir) { AdmiralRoster.listSlots(saveDir) }
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize().background(AliceColors.Background)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(AliceColors.Surface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹ Назад", fontSize = 17.sp, color = AliceColors.TextPrimary,
                modifier = Modifier.clickable { onClose() }.padding(6.dp))
            Spacer(Modifier.width(10.dp))
            Text("Выбор сохранения", fontSize = 16.sp, color = AliceColors.TextSecondary)
        }

        if (slots.isEmpty()) {
            Text("Сохранений не найдено", color = AliceColors.TextSecondary,
                modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(slots) { f ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(f) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Слот ${f.nameWithoutExtension}", fontSize = 17.sp,
                            color = AliceColors.TextPrimary)
                        Spacer(Modifier.weight(1f))
                        Text(fmt.format(Date(f.lastModified())), fontSize = 13.sp,
                            color = AliceColors.TextSecondary)
                    }
                }
            }
        }
    }
}
