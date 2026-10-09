package com.tabletdisplay.video

import org.junit.Assert.assertEquals
import org.junit.Test

class H264TimingTest {
    @Test
    fun renderLeadKeepsHalfAFrameOfHeadroom() {
        assertEquals(8_333L, renderLeadTimeUs(60, 1))
        assertEquals(16_666L, renderLeadTimeUs(30, 1))
    }
}
