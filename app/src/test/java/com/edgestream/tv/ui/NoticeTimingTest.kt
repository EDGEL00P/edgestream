package com.edgestream.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NoticeTimingTest {
    @Test fun noticeStaysLongEnoughToRead() {
        assertEquals(3_000L, noticeMs("Saved"))
        assertEquals(5_000L, noticeMs("x".repeat(60)))
        assertEquals(7_000L, noticeMs("x".repeat(400)))
    }
}
