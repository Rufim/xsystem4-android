package io.github.rufim.alice.launcher

import io.github.rufim.alice.R
import io.github.rufim.alice.NativeBridge
import io.github.rufim.alice.tts.*
import io.github.rufim.alice.cheats.*
import io.github.rufim.alice.launcher.*
import io.github.rufim.alice.engine.*
import io.github.rufim.alice.ui.*

import java.io.File

/** Движок AliceSoft, на котором работает игра. */
enum class Engine { XSYSTEM4, XSYSTEM35 }

/**
 * Определяет движок по маркер-файлам в каталоге игры:
 * System 3.x (xsystem35) — по `System39.ain`;
 * System 4 (xsystem4) — по `System40.ini` или `AliceStart.ini`.
 * System39.ain имеет приоритет. `null`, если ни то, ни другое.
 */
fun detectEngine(dir: File): Engine? = when {
    File(dir, "System39.ain").exists() -> Engine.XSYSTEM35
    File(dir, "System40.ini").exists() || File(dir, "AliceStart.ini").exists() -> Engine.XSYSTEM4
    else -> null
}
