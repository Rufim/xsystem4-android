package io.github.rufim.alice

import io.github.rufim.alice.tts.*
import io.github.rufim.alice.launcher.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsSegmenterTest {
    @Test fun cyrillicIsRu() =
        assertEquals(listOf("ru" to "Привет, мир!"), TtsSegmenter.segments("Привет, мир!"))

    @Test fun kanaIsJa() =
        assertEquals(listOf("ja" to "こんにちは"), TtsSegmenter.segments("こんにちは"))

    @Test fun latinIsEn() =
        assertEquals(listOf("en" to "Hello world"), TtsSegmenter.segments("Hello world"))

    @Test fun mixedSplits() {
        val s = TtsSegmenter.segments("Привет 「こんにちは」 friend")
        assertEquals(listOf("ru", "ja", "en"), s.map { it.first })
    }

    @Test fun punctuationSticksToPrevious() =
        assertEquals(listOf("ru" to "Да… да."), TtsSegmenter.segments("Да… да."))

    @Test fun leadingFullwidthSpaceDropped() =
        assertEquals(listOf("ru" to "*Помехи*..."), TtsSegmenter.segments("　　*Помехи*..."))

    // Имя говорящего: строки вида 【Имя】 (см. Linux-лог Daiteikoku)
    @Test fun speakerLineParsed() =
        assertEquals("Адмирал Хигути", TtsSegmenter.speakerOf("【Адмирал Хигути】"))

    @Test fun normalLineIsNotSpeaker() =
        assertNull(TtsSegmenter.speakerOf("Так точно."))
}
