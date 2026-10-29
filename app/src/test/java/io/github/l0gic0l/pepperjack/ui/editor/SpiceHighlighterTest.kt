package io.github.l0gic0l.pepperjack.ui.editor

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpiceHighlighterTest {
    private val colors = SpiceColors(Color.Gray, Color.Blue, Color.Red, Color.Green, Color.Magenta)

    @Test
    fun colorsDirectivesElementsNumbersAndComments() {
        val text = "* title\nV1 in 0 SIN(0 1 1k) ; source\n.tran 10u 5m\n* comment\nC1 out 0 {C}\n"
        val highlighted = SpiceHighlighter.highlight(text, colors)
        assertEquals(text, highlighted.text)
        val styled = highlighted.spanStyles.map { text.substring(it.start, it.end) to it.item.color }
        assertTrue(styled.toString(), ".tran" to Color.Blue in styled)
        assertTrue(styled.toString(), "V1" to Color.Red in styled)
        assertTrue(styled.toString(), "1k" to Color.Green in styled)
        assertTrue(styled.toString(), "; source" to Color.Gray in styled)
    }
}
