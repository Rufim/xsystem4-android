package io.github.rufim.alice.engine

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rufim.alice.NativeBridge
import io.github.rufim.alice.ui.AliceColors
import io.github.rufim.alice.ui.LabeledSlider
import io.github.rufim.alice.ui.ScreenScaffold
import io.github.rufim.alice.ui.SectionLabel
import java.util.Locale

/** Дефолты межбуквенного интервала — совпадают с движковыми (src/text.c). */
const val LS_BASE_DEFAULT = 3       // 0.03 — доля кегля, в сотых
const val LS_LARGE_DEFAULT = 3      // 0.03
const val LS_THRESHOLD_DEFAULT = 25 // размер шрифта, с которого действует «крупный» интервал

/** Применить сохранённые значения интервала к движку (при старте активити). */
fun applyLetterSpacing(prefs: SharedPreferences) {
    NativeBridge.setLetterSpacing(
        prefs.getInt("ls_base", LS_BASE_DEFAULT),
        prefs.getInt("ls_large", LS_LARGE_DEFAULT),
        prefs.getInt("ls_threshold", LS_THRESHOLD_DEFAULT),
    )
}

/** Форматирование доли кегля («0.03») из сотых. */
private fun ratioLabel(hundredths: Int) = String.format(Locale.US, "%.2f", hundredths / 100f)

/**
 * Настройки шрифта: межбуквенный интервал (LS) с живым применением.
 * Двухступенчатый интервал движка: base для обычного кегля, large для крупного
 * (>= порога). Меняется на реальной игре — эффект виден на следующей перерисовке.
 */
@Composable
fun FontSettingsScreen(
    prefs: SharedPreferences,
    onBack: () -> Unit,
) {
    var base by remember { mutableIntStateOf(prefs.getInt("ls_base", LS_BASE_DEFAULT)) }
    var large by remember { mutableIntStateOf(prefs.getInt("ls_large", LS_LARGE_DEFAULT)) }
    var threshold by remember { mutableIntStateOf(prefs.getInt("ls_threshold", LS_THRESHOLD_DEFAULT)) }

    fun apply() {
        NativeBridge.setLetterSpacing(base, large, threshold)
    }

    ScreenScaffold(title = "Шрифт", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
        ) {
            SectionLabel("Межбуквенный интервал")
            Text(
                "Разрядка латиницы/кириллицы (доля кегля). Крупный интервал применяется " +
                    "к шрифту размером не меньше порога.",
                fontSize = 12.sp,
                color = AliceColors.TextSecondary,
            )

            LabeledSlider("Интервал (обычный)", base, 0..30, ::ratioLabel) { v ->
                base = v
                prefs.edit().putInt("ls_base", v).apply()
                apply()
            }
            LabeledSlider("Интервал (крупный)", large, 0..30, ::ratioLabel) { v ->
                large = v
                prefs.edit().putInt("ls_large", v).apply()
                apply()
            }
            LabeledSlider("Порог крупного кегля", threshold, 10..60, { "$it" }) { v ->
                threshold = v
                prefs.edit().putInt("ls_threshold", v).apply()
                apply()
            }

            Text(
                "0.00 полностью отключает интервал. Значение применяется сразу; " +
                    "на уже нарисованном тексте эффект появится при следующей перерисовке.",
                fontSize = 12.sp,
                color = AliceColors.TextSecondary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
