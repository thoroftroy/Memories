package dev.wonderdesk.shared

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoOrderTest {
    @Test fun namesSortCaseInsensitivelyAndOriginalOrderIsStable() {
        val names = listOf("zebra.jpg", "Apple.jpg", "cat.jpg")
        fun sorted(order: PhotoOrder) = names.sortedWith { a, b -> comparePhotoNames(a, b, order) }
        assertEquals(names, sorted(PhotoOrder.ORIGINAL))
        assertEquals(listOf("Apple.jpg", "cat.jpg", "zebra.jpg"), sorted(PhotoOrder.NAME_ASC))
        assertEquals(listOf("zebra.jpg", "cat.jpg", "Apple.jpg"), sorted(PhotoOrder.NAME_DESC))
    }
}
