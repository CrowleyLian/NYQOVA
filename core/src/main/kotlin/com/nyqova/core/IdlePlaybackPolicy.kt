package com.nyqova.core

/** Monotonic foreground inactivity. Zero disables the feature; media events are not user input. */
class IdlePlaybackPolicy {
    private var lastInteractionMs=0L
    fun interact(nowMs: Long) { lastInteractionMs=nowMs }
    fun remainingMs(nowMs: Long, seconds: Int): Long? {
        if(seconds<=0) return null
        return (seconds.coerceAtMost(3600)*1000L-(nowMs-lastInteractionMs).coerceAtLeast(0)).coerceAtLeast(0)
    }
}
