package com.anilili.ui.components

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class AnilifeLogoGeometryTest {
    @Test
    fun `square logo is centred by the shorter canvas edge`() {
        val frame = silhouetteFrame(Size(200f, 100f))

        assertEquals(55f, frame.left, 0f)
        assertEquals(5f, frame.top, 0f)
        assertEquals(90f, frame.width, 0f)
        assertEquals(90f, frame.height, 0f)
    }
}
