package com.tubeoffline.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryOrderStoreTest {

    @Test
    fun emptyOrderKeepsMediaStoreOrder() {
        val media = listOf("a", "b", "c")
        assertEquals(media, mergeLibraryOrder(media, emptyList()))
    }

    @Test
    fun customOrderReordersKnownIds() {
        val media = listOf("a", "b", "c") // newest-first
        val order = listOf("c", "a", "b")
        assertEquals(listOf("c", "a", "b"), mergeLibraryOrder(media, order))
    }

    @Test
    fun newDownloadsAppearAboveSavedOrder() {
        val media = listOf("new", "a", "b")
        val order = listOf("b", "a")
        assertEquals(listOf("new", "b", "a"), mergeLibraryOrder(media, order))
    }

    @Test
    fun dropsStaleOrderEntriesMissingFromLibrary() {
        val media = listOf("a")
        val order = listOf("gone", "a")
        assertEquals(listOf("a"), mergeLibraryOrder(media, order))
    }

    @Test
    fun emptyMediaReturnsEmpty() {
        assertEquals(emptyList<String>(), mergeLibraryOrder(emptyList(), listOf("a")))
    }
}
