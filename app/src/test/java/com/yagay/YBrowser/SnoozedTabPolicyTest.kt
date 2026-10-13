package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SnoozedTabPolicyTest {
    @Test fun noTwoAlarmsShareTheSameMillisecondId() {
        assertEquals(101L, SnoozedTabPolicy.nextId(listOf(100L), 100L))
        assertEquals(105L, SnoozedTabPolicy.nextId(listOf(100L, 104L), 102L))
        assertEquals(200L, SnoozedTabPolicy.nextId(listOf(100L), 200L))
    }

    @Test fun rejectsIdOverflowRatherThanOverwritingAnAlarm() {
        assertThrows(IllegalArgumentException::class.java) {
            SnoozedTabPolicy.nextId(listOf(Long.MAX_VALUE), Long.MAX_VALUE)
        }
    }
}
