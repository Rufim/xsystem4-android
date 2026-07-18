package io.github.rufim.alice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EngineDetectTest {
    private fun dirWith(vararg names: String): File {
        val d = Files.createTempDirectory("game").toFile()
        for (n in names) File(d, n).writeText("x")
        return d
    }

    @Test fun detectsSystem39() =
        assertEquals(Engine.XSYSTEM35, detectEngine(dirWith("System39.ain")))

    @Test fun detectsSystem40Ini() =
        assertEquals(Engine.XSYSTEM4, detectEngine(dirWith("System40.ini")))

    @Test fun detectsAliceStart() =
        assertEquals(Engine.XSYSTEM4, detectEngine(dirWith("AliceStart.ini")))

    @Test fun system39WinsOverIni() =
        assertEquals(Engine.XSYSTEM35, detectEngine(dirWith("System39.ain", "AliceStart.ini")))

    @Test fun unknownIsNull() = assertNull(detectEngine(dirWith("readme.txt")))
}
