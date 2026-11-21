package com.nyqova.core

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class IdlePlaybackPolicyTest {
    @Test fun defaultDeadlineAndRemoteReset() {
        val policy=IdlePlaybackPolicy(); policy.interact(1000)
        assertEquals(1L,policy.remainingMs(30999,30)); assertEquals(0L,policy.remainingMs(31000,30))
        policy.interact(30999)
        assertEquals(29999L,policy.remainingMs(31000,30)); assertEquals(0L,policy.remainingMs(61000,30))
    }
    @Test fun disabledAndChangedDelayAndClockGuard() {
        val policy=IdlePlaybackPolicy(); policy.interact(2000)
        assertNull(policy.remainingMs(100000,0)); assertNull(policy.remainingMs(100000,-1))
        assertEquals(45000L,policy.remainingMs(17000,60))
        assertEquals(30000L,policy.remainingMs(1000,30))
        assertEquals(3600000L,policy.remainingMs(2000,Int.MAX_VALUE))
    }
}
