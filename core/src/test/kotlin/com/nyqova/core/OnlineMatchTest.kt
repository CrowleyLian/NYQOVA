package com.nyqova.core

import org.junit.Assert.*
import org.junit.Test

class OnlineMatchTest {
    @Test fun `preserves numbered song titles when artist tags exist`() {
        assertEquals("18 And Life",TrackIdentity.from("18 And Life","Skid Row","",0).title)
        assertEquals("21 Guns",TrackIdentity.from("21 Guns","Green Day","",0).title)
        assertEquals("晴天",TrackIdentity.from("01. 晴天","周杰伦","",0).title)
        assertEquals("晴天",TrackIdentity.from("01 周杰伦 - 晴天","","",0).title)
    }
    @Test fun `accepts matching artist and duration without punctuation sensitivity`() {
        val q = TrackIdentity("Back In Black", "AC/DC", "", 255000)
        assertTrue(OnlineMatch.confident(q, "Back in Black", "AC／DC", 256000))
        assertFalse(OnlineMatch.confident(q, "Back in Black", "Cover Band", 255000))
        assertFalse(OnlineMatch.confident(q, "Back in Black", "AC/DC", 285000))
    }
    @Test fun `does not automatically substitute live recordings or unknown artists`() {
        assertFalse(OnlineMatch.confident(TrackIdentity("Song (Live)", "Artist", "", 0), "Song", "Artist", 200000))
        assertFalse(OnlineMatch.confident(TrackIdentity("Song", "", "", 200000), "Song", "Artist", 200000))
    }
    @Test fun `uses filename artist only when tags are missing`() {
        assertEquals("太多", TrackIdentity.from("01. 阿冗 - 太多", "未知艺术家", "散曲", 2000).title)
        assertEquals("阿冗", TrackIdentity.from("01. 阿冗 - 太多", "未知艺术家", "散曲", 2000).artist)
        assertEquals("Singer", TrackIdentity.from("Song - Live", "Singer", "Album", 2000).artist)
        assertEquals("太多", TrackIdentity.from("太多 (cover：陈冠蒲)", "阿冗", "", 2000).title)
    }
    @Test fun `matches traditional and simplified Chinese without changing display titles`() {
        assertTrue(OnlineMatch.confident(TrackIdentity("伤痕", "林忆莲", "", 367000), "傷痕", "林憶蓮", 367000))
        assertEquals("林憶蓮", ChineseKeys.traditional("林忆莲"))
        assertEquals("傷痕", TrackIdentity.from("林忆莲 - 傷痕", "林憶蓮", "", 367000).title)
    }
}
