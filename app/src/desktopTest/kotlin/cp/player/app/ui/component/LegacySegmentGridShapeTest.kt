package cp.player.app.ui.component

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals

class LegacySegmentGridShapeTest {

    private val size = Size(100f, 100f)
    private val density = Density(1f)

    @Test
    fun `only the outer corners of a two by three grid stay large`() {
        val topLeft = shape(row = 0, rowCount = 2, column = 0, rowSize = 3)
        assertEquals(20f, topLeft.topStart.toPx(size, density), 0.01f)
        assertEquals(4f, topLeft.topEnd.toPx(size, density), 0.01f)
        assertEquals(4f, topLeft.bottomStart.toPx(size, density), 0.01f)
        assertEquals(4f, topLeft.bottomEnd.toPx(size, density), 0.01f)

        val topMiddle = shape(row = 0, rowCount = 2, column = 1, rowSize = 3)
        assertEquals(4f, topMiddle.topStart.toPx(size, density), 0.01f)
        assertEquals(4f, topMiddle.topEnd.toPx(size, density), 0.01f)
        assertEquals(4f, topMiddle.bottomStart.toPx(size, density), 0.01f)
        assertEquals(4f, topMiddle.bottomEnd.toPx(size, density), 0.01f)

        val topRight = shape(row = 0, rowCount = 2, column = 2, rowSize = 3)
        assertEquals(4f, topRight.topStart.toPx(size, density), 0.01f)
        assertEquals(20f, topRight.topEnd.toPx(size, density), 0.01f)
        assertEquals(4f, topRight.bottomStart.toPx(size, density), 0.01f)
        assertEquals(4f, topRight.bottomEnd.toPx(size, density), 0.01f)

        val bottomLeft = shape(row = 1, rowCount = 2, column = 0, rowSize = 3)
        assertEquals(4f, bottomLeft.topStart.toPx(size, density), 0.01f)
        assertEquals(4f, bottomLeft.topEnd.toPx(size, density), 0.01f)
        assertEquals(20f, bottomLeft.bottomStart.toPx(size, density), 0.01f)
        assertEquals(4f, bottomLeft.bottomEnd.toPx(size, density), 0.01f)

        val bottomMiddle = shape(row = 1, rowCount = 2, column = 1, rowSize = 3)
        assertEquals(4f, bottomMiddle.topStart.toPx(size, density), 0.01f)
        assertEquals(4f, bottomMiddle.topEnd.toPx(size, density), 0.01f)
        assertEquals(4f, bottomMiddle.bottomStart.toPx(size, density), 0.01f)
        assertEquals(4f, bottomMiddle.bottomEnd.toPx(size, density), 0.01f)

        val bottomRight = shape(row = 1, rowCount = 2, column = 2, rowSize = 3)
        assertEquals(4f, bottomRight.topStart.toPx(size, density), 0.01f)
        assertEquals(4f, bottomRight.topEnd.toPx(size, density), 0.01f)
        assertEquals(4f, bottomRight.bottomStart.toPx(size, density), 0.01f)
        assertEquals(20f, bottomRight.bottomEnd.toPx(size, density), 0.01f)
    }

    @Test
    fun `an incomplete last row keeps its exposed edge rounded`() {
        val lastItem = shape(row = 1, rowCount = 2, column = 1, rowSize = 2)
        assertEquals(4f, lastItem.topStart.toPx(size, density), 0.01f)
        assertEquals(4f, lastItem.topEnd.toPx(size, density), 0.01f)
        assertEquals(4f, lastItem.bottomStart.toPx(size, density), 0.01f)
        assertEquals(20f, lastItem.bottomEnd.toPx(size, density), 0.01f)
    }

    private fun shape(row: Int, rowCount: Int, column: Int, rowSize: Int): RoundedCornerShape =
        legacySegmentGridShape(row, rowCount, column, rowSize) as RoundedCornerShape
}
