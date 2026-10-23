package ru.movereon.tennis.application

import org.junit.jupiter.api.Test
import kotlin.test.*

class PaginationTest {
    @Test fun `all supported page sizes preserve every item exactly once`() {
        for(size in listOf(3,5,8,10,15)) for(count in 0..46) {
            val items=(0 until count).toList()
            val pages=(0 until items.page(0,size).pages).map { items.page(it,size) }
            assertEquals(items,pages.flatMap { it.items })
            assertTrue(pages.all { it.items.size<=size && it.total==count && it.size==size })
        }
    }
    @Test fun `stale page after removals clamps to the remaining last page`() {
        val page=(0 until 16).toList().page(Int.MAX_VALUE,15)
        assertEquals(1,page.index);assertEquals(listOf(15),page.items)
        val remaining=(0 until 15).toList().page(page.index,15)
        assertEquals(0,remaining.index);assertEquals(15,remaining.items.size)
        val empty=emptyList<Int>().page(page.index,15)
        assertEquals(0,empty.index);assertEquals(1,empty.pages);assertTrue(empty.items.isEmpty())
        assertEquals(0,(0 until 16).toList().page(Int.MIN_VALUE,15).index)
    }
    @Test fun `visible page is a stable copy when the source changes`() {
        val items=(0 until 40).toMutableList()
        val page=items.page(1,15)
        items.clear()
        assertEquals((15 until 30).toList(),page.items)
        assertEquals(40,page.total)
    }
}
