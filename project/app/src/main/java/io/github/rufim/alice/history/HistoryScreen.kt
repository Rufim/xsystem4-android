package io.github.rufim.alice.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rufim.alice.ui.AliceColors
import io.github.rufim.alice.ui.ScreenScaffold

/** Полноэкранная история последних сообщений (новые внизу, автопрокрутка). */
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val entries by MessageHistory.entries.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.size - 1)
    }

    ScreenScaffold(title = "История", onBack = onBack) {
        if (entries.isEmpty()) {
            Text(
                "Сообщений пока нет",
                color = AliceColors.TextSecondary,
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                items(entries) { e ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        e.speaker?.let {
                            Text(it, color = AliceColors.Accent, fontSize = 13.sp)
                        }
                        Text(e.text, color = AliceColors.TextPrimary, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}
