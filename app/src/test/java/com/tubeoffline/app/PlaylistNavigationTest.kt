package com.tubeoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistNavigationTest {

    private data class Item(val id: String, val name: String)

    private fun next(items: List<Item>, current: Int, query: String, skipIds: Set<String> = emptySet()) =
        adjacentInFilteredList(
            items = items,
            currentIndex = current,
            direction = +1,
            matches = { query.isBlank() || it.name.contains(query, ignoreCase = true) },
            skip = { it.id in skipIds },
        )

    private fun prev(items: List<Item>, current: Int, query: String, skipIds: Set<String> = emptySet()) =
        adjacentInFilteredList(
            items = items,
            currentIndex = current,
            direction = -1,
            matches = { query.isBlank() || it.name.contains(query, ignoreCase = true) },
            skip = { it.id in skipIds },
        )

    @Test
    fun searchScopesNextToMatchingNamesOnly() {
        val items = listOf(
            Item("a", "Math lecture 1.mp4"),
            Item("b", "Cooking.mp4"),
            Item("c", "Physics lecture.mp4"),
        )
        assertEquals("c", next(items, 0, "lecture")!!.id)
        assertNull(next(items, 2, "lecture"))
    }

    @Test
    fun nonMatchingCurrentUsesNearestMatchingNeighbour() {
        val items = listOf(
            Item("a", "Math lecture 1.mp4"),
            Item("x", "Unrelated.mp4"),
            Item("c", "Physics lecture.mp4"),
        )
        assertEquals("c", next(items, 1, "lecture")!!.id)
        assertEquals("a", prev(items, 1, "lecture")!!.id)
    }

    @Test
    fun blankQueryUsesFullLibraryOrder() {
        val items = listOf(Item("a", "A.mp4"), Item("b", "B.mp4"))
        assertEquals("b", next(items, 0, "")!!.id)
        assertEquals("a", prev(items, 1, "  ")!!.id)
    }

    @Test
    fun skipOmitsFinishedForAutoplay() {
        val items = listOf(
            Item("a", "lecture A.mp4"),
            Item("b", "lecture B.mp4"),
            Item("c", "lecture C.mp4"),
        )
        assertEquals("c", next(items, 0, "lecture", skipIds = setOf("b"))!!.id)
    }
}
