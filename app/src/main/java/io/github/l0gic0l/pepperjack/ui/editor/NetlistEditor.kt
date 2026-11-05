package io.github.l0gic0l.pepperjack.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class SpiceColors(val comment: Color, val directive: Color, val element: Color, val number: Color, val keyword: Color)

/** SPICE syntax coloring: comments, dot-commands, instance names, values and functions. */
class SpiceHighlighter(private val colors: SpiceColors) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlight(text.text, colors), OffsetMapping.Identity)

    companion object {
        private val number = Regex("""(?<![\w.])[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?(?:meg|[tgkmunpfa]|mil)?[a-zA-Z]*""", RegexOption.IGNORE_CASE)
        private val function = Regex("""\b(sin|pulse|pwl|exp|sffm|am|ac|dc|v|i|if|limit|table|uramp|min|max|abs|sqrt|log|ln|pow|time)\s*(?=\()|\b(ac|dc)\b""", RegexOption.IGNORE_CASE)

        fun highlight(source: String, colors: SpiceColors): AnnotatedString = buildAnnotatedString {
            append(source)
            var start = 0
            source.split('\n').forEachIndexed { index, line ->
                val trimmed = line.trimStart()
                val lead = line.length - trimmed.length
                val lineStart = start
                when {
                    index == 0 && trimmed.isNotEmpty() ->
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.comment), lineStart, lineStart + line.length)
                    trimmed.startsWith("*") ->
                        addStyle(SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic), lineStart, lineStart + line.length)
                    else -> {
                        val commentAt = Regex("""(;|\s\$\s)""").find(line)?.range?.first ?: line.length
                        val code = line.substring(0, commentAt)
                        if (trimmed.startsWith(".")) {
                            val end = lead + trimmed.takeWhile { !it.isWhitespace() }.length
                            addStyle(SpanStyle(color = colors.directive, fontWeight = FontWeight.SemiBold), lineStart + lead, lineStart + end)
                        } else if (trimmed.isNotEmpty() && trimmed[0].isLetter()) {
                            val end = lead + trimmed.takeWhile { !it.isWhitespace() }.length
                            addStyle(SpanStyle(color = colors.element, fontWeight = FontWeight.SemiBold), lineStart + lead, lineStart + end)
                        }
                        number.findAll(code).forEach {
                            addStyle(SpanStyle(color = colors.number), lineStart + it.range.first, lineStart + it.range.last + 1)
                        }
                        function.findAll(code).forEach {
                            addStyle(SpanStyle(color = colors.keyword), lineStart + it.range.first, lineStart + it.range.last + 1)
                        }
                        if (commentAt < line.length) {
                            addStyle(SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic), lineStart + commentAt, lineStart + line.length)
                        }
                    }
                }
                start += line.length + 1
            }
        }
    }
}

@Composable
fun spiceColors(): SpiceColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        SpiceColors(
            comment = scheme.outline,
            directive = scheme.primary,
            element = scheme.tertiary,
            number = scheme.secondary,
            keyword = scheme.primary,
        )
    }
}

@Composable
fun NetlistEditor(text: String, onTextChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var value by remember { mutableStateOf(TextFieldValue(text, TextRange(0))) }
    LaunchedEffect(text) {
        if (text != value.text) value = value.copy(text = text, selection = TextRange(text.length.coerceAtMost(value.selection.end)))
    }
    val colors = spiceColors()
    val transformation = remember(colors) { SpiceHighlighter(colors) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = modifier) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
            BasicTextField(
                value = value,
                onValueChange = {
                    value = it
                    if (it.text != text) onTextChange(it.text)
                },
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = transformation,
                modifier = Modifier.fillMaxWidth().padding(16.dp).semantics { contentDescription = "SPICE netlist editor" },
            )
        }
    }
}

/** Read-only, highlighted, selectable netlist (the generated netlist of a schematic). */
@Composable
fun NetlistViewer(text: String, modifier: Modifier = Modifier) {
    val colors = spiceColors()
    val highlighted = remember(text, colors) { SpiceHighlighter.highlight(text, colors) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = modifier) {
        SelectionContainer(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                highlighted,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
