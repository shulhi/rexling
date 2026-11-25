package dev.shulhi.rex

import org.junit.Assert.*
import org.junit.Test

class TerminalImePanTest {
    @Test fun dragRevealsClippedRowsAndReturnsUnusedMovement() {
        val pan=TerminalImePanState()
        val prompt=TerminalKeyboardFocus(680f,700f,710f)
        assertEquals(210,pan.position(prompt,500,240,40))
        assertEquals(0f,pan.consumeDrag(-80f),0f)
        assertEquals(130,pan.position(null,500,240,40))
        assertEquals(-30f,pan.consumeDrag(-200f),0f)
        assertEquals(-40,pan.position(null,500,240,40))
        assertEquals(30f,pan.consumeDrag(280f),0f)
        assertEquals(210,pan.position(null,500,240,40))

        pan.consumeDrag(-100f)
        pan.followCursor()
        assertEquals(210,pan.position(null,500,240,40))
        pan.consumeDrag(-100f)
        assertEquals(0,pan.position(null,740,0))
        assertEquals(210,pan.position(prompt,500,240,40))
    }

    @Test fun compactHeaderKeepsShortOutputBelowTheGlass() {
        val pan=TerminalImePanState()
        val prompt=TerminalKeyboardFocus(20f,40f,40f)
        assertEquals(-40,pan.position(null,500,200,40))
        assertEquals(-40,pan.position(prompt,500,200,40))
        assertEquals(120,pan.position(TerminalKeyboardFocus(600f,620f,650f),500,200,40))
        assertEquals(120,pan.position(null,500,200,40))
        assertEquals(-40,pan.position(prompt,500,200,40))
        assertEquals(0,pan.position(prompt,740,0))
    }

    @Test fun editorCursorMovesDoNotRecenterAVisibleViewport() {
        val pan=TerminalImePanState()
        assertEquals(400,pan.position(TerminalKeyboardFocus(600f,620f,1000f),500,400))
        for(y in listOf(590f,500f,499f,480f,600f,700f)) {
            assertEquals(400,pan.position(TerminalKeyboardFocus(y,y+20,1000f),500,400))
        }
        assertEquals(399,pan.position(TerminalKeyboardFocus(399f,419f,1000f),500,400))
        assertEquals(399,pan.position(null,500,400))
        assertEquals(400,pan.position(TerminalKeyboardFocus(880f,901f,1000f),500,400))
        assertEquals(0,pan.position(null,900,0))
    }
}
