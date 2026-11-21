package com.nyqova.core

import org.junit.Assert.*
import org.junit.Test

class CueParserTest {
    @Test fun `preserves album metadata and index01 boundaries at 75 frames per second`() {
        val tracks = CueParser.parse("""
            PERFORMER "艺术家"
            TITLE "测试专辑"
            FILE "整轨 音乐.wav" WAVE
              TRACK 01 AUDIO
                TITLE "第一首"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "第二首"
                INDEX 00 01:58:00
                INDEX 01 02:00:01
        """.trimIndent())
        assertEquals(2, tracks.size)
        assertEquals("艺术家", tracks[0].artist)
        assertEquals("测试专辑", tracks[1].album)
        assertEquals("整轨 音乐.wav", tracks[0].file)
        assertEquals(120_013_333L, tracks[0].endUs)
        assertEquals(tracks[0].endUs, tracks[1].startUs)
        assertNull(tracks[1].endUs)
    }
    @Test fun `multi file cue resets boundary and ignores missing indexes`() {
        val tracks = CueParser.parse("""
            FILE "a.flac" WAVE
            TRACK 01 AUDIO
            INDEX 01 00:00:00
            FILE "b.flac" WAVE
            TRACK 02 AUDIO
            INDEX 01 00:00:00
            TRACK 03 AUDIO
        """.trimIndent())
        assertEquals(2, tracks.size)
        assertNull(tracks[0].endUs)
        assertEquals(0L, tracks[1].startUs)
    }
    @Test(expected = IllegalArgumentException::class) fun `rejects backwards boundaries`() {
        CueParser.parse("FILE a.wav WAVE\nTRACK 01 AUDIO\nINDEX 01 02:00:00\nTRACK 02 AUDIO\nINDEX 01 01:00:00")
    }
}
