package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileSnapshotCacheTest {
    @Test fun returnsSameParsedValueForUnchangedPreferences() {
        val cache = ProfileSnapshotCache<List<String>>()
        val initial = listOf("one", "two")
        cache.put("default", "[\"one\",\"two\"]", initial)
        assertEquals(initial, cache.get("default", "[\"one\",\"two\"]"))
        assertNull(cache.get("default", "[\"other\"]"))
        assertNull(cache.get("default", "[\"one\",\"two\"]"))
    }

    @Test fun isolatesAndBoundsProfiles() {
        val cache = ProfileSnapshotCache<String>(2)
        cache.put("a", "A", "a value")
        cache.put("b", "B", "b value")
        assertEquals("a value", cache.get("a", "A"))
        cache.put("c", "C", "c value")
        assertNull(cache.get("b", "B"))
        assertEquals("a value", cache.get("a", "A"))
        assertEquals("c value", cache.get("c", "C"))
    }

    @Test fun supportsNullRawAndExplicitInvalidation() {
        val cache = ProfileSnapshotCache<List<String>>(1)
        cache.put("private", null, emptyList())
        assertEquals(emptyList<String>(), cache.get("private", null))
        cache.clear("private")
        assertNull(cache.get("private", null))
    }
}
