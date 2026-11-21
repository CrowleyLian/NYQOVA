package com.nyqova.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicTextTest {
    @Test fun `decodes common Chinese cue encodings`() {
        val text = "TITLE \"中文专辑\""
        assertEquals(text, MusicText.decode(text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, MusicText.decode(text.toByteArray(charset("GB18030"))))
        assertEquals(text, MusicText.decode(text.toByteArray(Charsets.UTF_16)))
    }
    @Test fun `handles tabs between cue commands`() {
        assertEquals("测试", CueParser.parse("FILE\t\"a.wav\" WAVE\nTRACK\t01 AUDIO\nTITLE\t\"测试\"\nINDEX\t01\t00:00:00").single().title)
    }
}
