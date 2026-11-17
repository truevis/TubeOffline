package com.tubeoffline.app

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchedSelectionTest {
    @Test
    fun keepsOnlyCompletedInLibraryOrder() {
        val items = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "d"), watchedOnly(items, { it }, setOf("d", "b", "zzz")))
    }

    @Test
    fun emptyWhenNothingFinished() {
        assertEquals(emptyList<String>(), watchedOnly(listOf("a"), { it }, emptySet()))
    }
}
