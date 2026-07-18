package io.github.rufim.alice.engine

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rufim.alice.R
import io.github.rufim.alice.ui.AliceColors

/** Кнопка боковой панели. */
data class PanelAction(val title: String, val onClick: () -> Unit)

/** Состояние оверлея игры (меняется из EngineActivity). */
class EnginePanelState {
    var ttsOn by mutableStateOf(false)
    var ttsStopped by mutableStateOf(false)
    /** Текст индикатора «стр N · окно M»; null — не показывать (System 4). */
    var indicator by mutableStateOf<String?>(null)
}

/**
 * Постоянный Compose-оверлей поверх игры: ручка у правого края, выдвижная
 * панель кнопок со скримом, кнопка стоп/плей TTS и отладочный индикатор.
 * Некликабельные области событий не потребляют — тапы уходят в игру.
 */
@Composable
fun EnginePanelOverlay(
    state: EnginePanelState,
    actions: List<PanelAction>,
    onPlayPause: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        // индикатор «стр · окно» (только при включённом TTS и если движок отдаёт)
        if (state.ttsOn) state.indicator?.let { text ->
            Text(
                text,
                color = AliceColors.Indicator,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(Color(0xA0000000))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        // стоп/плей чтения
        if (state.ttsOn) {
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .size(52.dp)
                    .background(Color(0xAA1E2230)),
            ) {
                Icon(
                    painter = painterResource(
                        if (state.ttsStopped) R.drawable.ic_tts_play else R.drawable.ic_tts_stop),
                    contentDescription = if (state.ttsStopped) "Продолжить чтение" else "Остановить чтение",
                    tint = AliceColors.TextPrimary,
                )
            }
        }

        if (open) {
            // скрим: тап мимо панели закрывает её
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AliceColors.Scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { open = false }
            )
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(230.dp)
                    .background(Color(0xEB1E2230))
                    .padding(10.dp)
            ) {
                actions.forEach { action ->
                    Button(
                        onClick = { open = false; action.onClick() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Text(action.title)
                    }
                }
            }
        }

        // ручка панели
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(12.dp)
                .height(48.dp)
                .background(Color(0x5AFFFFFF))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { open = !open }
        )
    }
}
