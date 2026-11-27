package com.antenna.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.antenna.data.MiniPlayerPlace
import org.junit.Assert.assertEquals
import org.junit.Test

/** The floating mini player on big screens: dragged anywhere in its room, pinched or corner-dragged to resize. */
class FloatingMiniTest {

    /** A 1000 × 600 px room, at density 1: the smallest card is 160 px wide, the biggest 1000. */
    private val room = Rect(0f, 0f, 1000f, 600f)
    private val saved = mutableListOf<MiniPlayerPlace>()

    private fun mini(place: MiniPlayerPlace = MiniPlayerPlace()) = FloatingMini(place) { saved += it }

    private fun FloatingMini.card() = place(room, usualWidth = 300f, density = 1f)

    private fun assertCard(left: Float, top: Float, width: Float, card: Rect) {
        assertEquals(left, card.left, 0.01f)
        assertEquals(top, card.top, 0.01f)
        assertEquals(width, card.width, 0.01f)
        assertEquals(width * 9 / 16, card.height, 0.01f)
    }

    @Test
    fun `it starts in the bottom right corner, at its usual width`() {
        assertCard(700f, 600f - 168.75f, 300f, mini().card())
    }

    @Test
    fun `a drag moves it, and it stays in its room`() {
        val mini = mini().also { it.card() }
        mini.transform(pan = Offset(-200f, -100f))
        assertCard(500f, 331.25f, 300f, mini.card())
        mini.transform(pan = Offset(-5000f, -5000f))
        assertCard(0f, 0f, 300f, mini.card())
    }

    @Test
    fun `a pinch resizes it around the fingers`() {
        val mini = mini(MiniPlayerPlace(0.5f, 0.5f)).also { it.card() }
        mini.transform(pan = Offset.Zero, zoom = 2f, pivot = Offset(150f, 84.375f)) // its middle
        assertCard(200f, 131.25f, 600f, mini.card()) // still centred on (500, 300)
    }

    @Test
    fun `it can't be made smaller than 160 dp, or bigger than its room`() {
        val mini = mini(MiniPlayerPlace(0.5f, 0.5f)).also { it.card() }
        mini.transform(pan = Offset.Zero, zoom = 0.1f)
        assertEquals(160f, mini.card().width)
        mini.transform(pan = Offset.Zero, zoom = 100f)
        assertEquals(1000f, mini.card().width)
        val small = mini(MiniPlayerPlace(widthDp = 100f))
        assertEquals(320f, small.place(room, usualWidth = 300f, density = 2f).width) // 160 dp at 2x
    }

    @Test
    fun `dragging a bottom corner out grows it, the opposite corner staying put`() {
        val mini = mini(MiniPlayerPlace(0.5f, 0.5f))
        val before = mini.card() // 350, 215.625, 300 wide
        mini.resize(Offset(160f, 90f), fromLeft = false) // along its diagonal: 160 px wider
        assertCard(before.left, before.top, 460f, mini.card())
        mini.resize(Offset(-160f, 90f), fromLeft = true)
        val after = mini.card()
        assertEquals(620f, after.width)
        assertEquals(before.left + 460f, after.right, 0.01f) // the top right corner stayed
        assertEquals(before.top, after.top, 0.01f)
    }

    @Test
    fun `a corner can't take it out of its room`() {
        val mini = mini().also { it.card() } // in the bottom right corner
        mini.resize(Offset(160f, 90f), fromLeft = false) // grows towards the corner: pushed back in
        val card = mini.card()
        assertEquals(460f, card.width)
        assertEquals(room.right, card.right, 0.01f)
        assertEquals(room.bottom, card.bottom, 0.01f)
    }

    @Test
    fun `where it was left is saved, and its size once it's resized`() {
        val mini = mini().also { it.card() }
        mini.transform(pan = Offset(-700f, -431.25f))
        mini.save()
        assertEquals(MiniPlayerPlace(0f, 0f, 0f), saved.last()) // moved, not resized: still its usual size
        mini.transform(pan = Offset.Zero, zoom = 2f)
        mini.save()
        assertEquals(600f, saved.last().widthDp)
    }

    @Test
    fun `in a bigger window it stays by the same edges`() {
        val mini = mini().also { it.card() } // bottom right
        val card = mini.place(Rect(0f, 0f, 2000f, 1200f), usualWidth = 300f, density = 1f)
        assertEquals(2000f, card.right)
        assertEquals(1200f, card.bottom)
    }
}
