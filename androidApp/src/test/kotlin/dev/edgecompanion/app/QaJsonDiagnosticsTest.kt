package dev.edgecompanion.app

import dev.edgecompanion.app.support.escapeQaJsonSurrogates
import org.junit.Assert.assertEquals
import org.junit.Test

class QaJsonDiagnosticsTest {
    @Test fun interruptedAndCompleteUnicodeUseLosslessJsonEscapes() {
        assertEquals("{\"raw\":\"\\ud83d\"}", escapeQaJsonSurrogates("{\"raw\":\"\uD83D\"}"))
        assertEquals("{\"raw\":\"\\ud83d\\ude00\"}", escapeQaJsonSurrogates("{\"raw\":\"😀\"}"))
        assertEquals("{\"raw\":\"\\\\ud83d\"}", escapeQaJsonSurrogates("{\"raw\":\"\\\\ud83d\"}"))
    }
}
