package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NavigationTrailIdPolicyTest {
    @Test fun preservesTimestampWhenNotUsed() {
        assertEquals(123L, NavigationTrailIdPolicy.nextId(123L, emptyList()))
    }

    @Test fun avoidsCollisionsWithinOneMillisecond() {
        assertEquals(125L, NavigationTrailIdPolicy.nextId(123L, listOf(123L, 124L)))
    }

    @Test fun handlesClockRollbackAndLongOverflow() {
        assertEquals(2L, NavigationTrailIdPolicy.nextId(-1, listOf(1L)))
        assertEquals(2L, NavigationTrailIdPolicy.nextId(Long.MAX_VALUE, listOf(1L, Long.MAX_VALUE)))
    }
}
