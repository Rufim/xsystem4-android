package io.github.rufim.alice

// Игровая активити движка xsystem4 (System 4). Вся обвязка TTS/читов — в
// EngineActivity; здесь — только нативная библиотека, аргументы запуска и
// специфичная для System 4 команда открытия игрового мануала.
class XSystem4Activity : EngineActivity() {
    companion object {
        const val COMMAND_OPEN_PLAYING_MANUAL = 0x8000  // xsystem4/src/hll/SystemService.c
    }

    override fun getLibraries(): Array<String> {
        return arrayOf("SDL2", "xsystem4")
    }

    override fun getArguments(): Array<String> {
        val saveFolder = intent.getStringExtra(EXTRA_SAVE_DIR)!!
        val gameRoot = intent.getStringExtra(EXTRA_GAME_ROOT)!!
        return arrayOf("--save-folder", saveFolder, "--save-format=rsm", gameRoot)
    }

    override fun onUnhandledMessage(command: Int, param: Any): Boolean {
        when (command) {
            COMMAND_OPEN_PLAYING_MANUAL -> {
                val gameRoot = intent.getStringExtra(EXTRA_GAME_ROOT)!!
                val manualDir = java.io.File(gameRoot, "Manual")
                if (manualDir.isDirectory) {
                    val intent = android.content.Intent(this, ManualActivity::class.java).apply {
                        val url = "file://${manualDir.absolutePath}/index.html"
                        putExtra(ManualActivity.EXTRA_URL, url)
                    }
                    startActivity(intent)
                }
                return true
            }
        }
        return super.onUnhandledMessage(command, param)
    }
}
