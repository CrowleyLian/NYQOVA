package com.nyqova.core

import org.junit.Assert.*
import org.junit.Test

class LrcParserTest {
    @Test fun `sorts multi timestamps and merges bilingual lines`() {
        val doc = LrcParser.parse("[00:20.25][00:10.5]重复\n[00:10.500]translation\n[00:30]结束")
        assertEquals(3, doc.lines.size)
        assertEquals(10500L, doc.lines[0].timeMs)
        assertEquals("重复\ntranslation", doc.lines[0].text)
        assertEquals(-1, doc.indexAt(10499))
        assertEquals(0, doc.indexAt(10500))
        assertEquals(2, doc.indexAt(40000))
    }
    @Test fun `positive offset hastens lyrics and milliseconds are preserved`() {
        val doc = LrcParser.parse("[offset:+250]\n[01:02.123]一句")
        assertEquals(61873L, doc.lines.single().timeMs)
    }
    @Test fun `plain lyrics remain readable`() {
        val doc = LrcParser.parse("[ar:艺术家]\n第一行\n第二行")
        assertTrue(doc.lines.isEmpty()); assertEquals("第一行\n第二行", doc.plainText)
    }
}
