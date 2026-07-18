package io.github.rufim.alice.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rufim.alice.ui.AliceColors
import io.github.rufim.alice.ui.AliceTheme
import io.github.rufim.alice.ui.ScreenScaffold
import java.io.BufferedReader

/** Лицензии сторонних компонентов: список → текст (из assets/licenses). */
class LicensesActivity : ComponentActivity() {
    private data class Entry(val displayName: String, val fileName: String, val url: String)

    private val entries = listOf(
        Entry("xsystem4", "xsystem4", "https://github.com/nunuhara/xsystem4"),
        Entry("xsystem35-sdl2", "xsystem35", "https://github.com/kichikuou/xsystem35-sdl2"),
        Entry("libsys4", "libsys4", "https://github.com/nunuhara/libsys4"),
        Entry("SDL", "SDL", "https://www.libsdl.org/"),
        Entry("cglm", "cglm", "https://github.com/recp/cglm"),
        Entry("libFLAC", "flac", "https://xiph.org/flac/"),
        Entry("FreeType", "freetype", "https://freetype.org/"),
        Entry("libffi", "libffi", "https://sourceware.org/libffi/"),
        Entry("libjpeg-turbo", "libjpeg-turbo", "https://libjpeg-turbo.org/"),
        Entry("libogg", "libogg", "https://xiph.org/ogg/"),
        Entry("libpng", "libpng", "http://www.libpng.org/pub/png/libpng.html"),
        Entry("libsndfile", "libsndfile", "https://libsndfile.github.io/libsndfile/"),
        Entry("libvorbis", "libvorbis", "https://xiph.org/vorbis/"),
        Entry("libwebp", "libwebp", "https://chromium.googlesource.com/webm/libwebp/"),
        Entry("libopus", "opus", "https://opus-codec.org/downloads/"),
        Entry("VL Gothic Regular", "VL-Gothic-Regular", "http://vlgothic.dicey.org/"),
        Entry("Hanazono Mincho A", "HanaMinA", "http://fonts.jp/hanazono/"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AliceTheme {
                var selected by remember { mutableStateOf<Entry?>(null) }
                BackHandler(enabled = selected != null) { selected = null }

                val current = selected
                if (current == null) {
                    ScreenScaffold(title = "Лицензии", onBack = { finish() }) {
                        LazyColumn {
                            items(entries) { e ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selected = e }
                                        .padding(vertical = 10.dp)
                                ) {
                                    Text(e.displayName, color = AliceColors.TextPrimary, fontSize = 16.sp)
                                    Text(e.url, color = AliceColors.TextSecondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                } else {
                    val text = remember(current) {
                        runCatching {
                            assets.open("licenses/${current.fileName}")
                                .bufferedReader().use(BufferedReader::readText)
                        }.getOrElse { "(файл лицензии не найден: licenses/${current.fileName})" }
                    }
                    ScreenScaffold(title = current.displayName, onBack = { selected = null }) {
                        Text(
                            text,
                            color = AliceColors.TextPrimary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 16.dp),
                        )
                    }
                }
            }
        }
    }
}
