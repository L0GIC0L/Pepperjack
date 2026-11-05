package io.github.l0gic0l.pepperjack.ui.symbols

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import io.github.l0gic0l.pepperjack.AppContainer
import io.github.l0gic0l.pepperjack.data.SymbolRepository
import io.github.l0gic0l.pepperjack.model.CustomSymbol
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.PinSide
import io.github.l0gic0l.pepperjack.model.Schematic
import io.github.l0gic0l.pepperjack.model.SymbolBody
import io.github.l0gic0l.pepperjack.model.SymbolPin
import io.github.l0gic0l.pepperjack.model.pinPositions
import io.github.l0gic0l.pepperjack.spice.SubcktHeader
import io.github.l0gic0l.pepperjack.ui.schematic.GridTransform
import io.github.l0gic0l.pepperjack.ui.schematic.drawPart
import io.github.l0gic0l.pepperjack.ui.theme.LocalCircuitColors
import kotlinx.coroutines.launch

/** Edits [symbolId], or creates a symbol for [subckt] in library file [file]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SymbolEditorScreen(
    container: AppContainer,
    symbolId: String?,
    file: String?,
    subckt: String?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var draft by remember { mutableStateOf<CustomSymbol?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(symbolId, file, subckt) {
        draft = when {
            symbolId != null -> container.symbols.get(symbolId)
            file != null && subckt != null -> runCatching {
                val header = SubcktHeader.parseAll(container.library.read(file)).firstOrNull { it.name.equals(subckt, true) }
                header?.let { CustomSymbol.create(SymbolRepository.newId(), it.name, file, it.pins) }
            }.getOrNull()
            else -> null
        }
        if (draft == null) error = "The subcircuit could not be found."
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
                title = { Text(if (symbolId == null) "New symbol" else "Edit symbol") },
                actions = {
                    TextButton(enabled = draft != null, onClick = {
                        val symbol = draft ?: return@TextButton
                        scope.launch {
                            container.symbols.save(symbol.copy(title = symbol.title.trim().ifEmpty { symbol.subckt }))
                            onBack()
                        }
                    }) { Text("Save") }
                },
            )
        },
    ) { padding ->
        val symbol = draft
        when {
            symbol != null -> SymbolEditorContent(symbol, { draft = it }, Modifier.padding(padding))
            error != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(error!!) }
            else -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

@Composable
private fun SymbolEditorContent(symbol: CustomSymbol, onChange: (CustomSymbol) -> Unit, modifier: Modifier) {
    val wide = currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val preview: @Composable (Modifier) -> Unit = { m ->
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = m) {
            SymbolPreview(symbol, Modifier.fillMaxSize().padding(16.dp), showPinNumbers = true)
        }
    }
    if (wide) {
        Row(modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            preview(Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).imePadding()) {
                SymbolControls(symbol, onChange)
            }
        }
    } else {
        Column(modifier.fillMaxSize()) {
            preview(Modifier.fillMaxWidth().height(240.dp).padding(16.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).imePadding()) {
                SymbolControls(symbol, onChange)
            }
        }
    }
}

@Composable
private fun SymbolControls(symbol: CustomSymbol, onChange: (CustomSymbol) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.navigationBarsPadding().padding(bottom = 24.dp)) {
        Text(
            "Subcircuit ${symbol.subckt}" + if (symbol.library.isNotEmpty()) " from ${symbol.library}" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = symbol.title,
            onValueChange = { onChange(symbol.copy(title = it)) },
            label = { Text("Name in the part picker") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = symbol.refPrefix,
                onValueChange = { text -> onChange(symbol.copy(refPrefix = text.filter { it.isLetter() }.take(4))) },
                label = { Text("Reference prefix") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = symbol.params,
                onValueChange = { onChange(symbol.copy(params = it)) },
                label = { Text("Default parameters") },
                placeholder = { Text("R=10k") },
                singleLine = true,
                modifier = Modifier.weight(1.4f),
            )
        }
        Text("Body", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SymbolBody.entries.forEachIndexed { index, body ->
                SegmentedButton(
                    selected = symbol.body == body,
                    onClick = { onChange(symbol.copy(body = body)) },
                    shape = SegmentedButtonDefaults.itemShape(index, SymbolBody.entries.size),
                    label = { Text(body.label) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pins", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { onChange(symbol.copy(pins = CustomSymbol.autoArrange(symbol.pins.map { it.name }))) }) {
                Icon(Icons.Outlined.AutoFixHigh, null)
                Spacer(Modifier.width(8.dp))
                Text("Auto-arrange")
            }
        }
        Text(
            "Pins are in the subcircuit's port order. Choose the side each one leaves from and its place along that side.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        symbol.pins.forEachIndexed { index, pin ->
            PinRow(
                index = index,
                pin = pin,
                canMoveUp = pin.order > 0,
                canMoveDown = symbol.pins.count { it.side == pin.side } - 1 > pin.order,
                onSide = { side -> onChange(symbol.copy(pins = movePinToSide(symbol.pins, index, side))) },
                onMove = { delta -> onChange(symbol.copy(pins = shiftPin(symbol.pins, index, delta))) },
            )
        }
        Text(
            "Parts already placed keep the symbol they were placed with; place the part again to pick up changes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PinRow(
    index: Int,
    pin: SymbolPin,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSide: (PinSide) -> Unit,
    onMove: (Int) -> Unit,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.widthIn(min = 64.dp).weight(1f)) {
                Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(pin.name, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
            SingleChoiceSegmentedButtonRow {
                PinSide.entries.forEachIndexed { i, side ->
                    SegmentedButton(
                        selected = pin.side == side,
                        onClick = { onSide(side) },
                        shape = SegmentedButtonDefaults.itemShape(i, PinSide.entries.size),
                        label = { Text(side.name.first().toString()) },
                        icon = {},
                        modifier = Modifier.semantics { contentDescription = "${pin.name} on the ${side.name.lowercase()}" },
                    )
                }
            }
            IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Move ${pin.name} earlier on its side")
            }
            IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Move ${pin.name} later on its side")
            }
        }
    }
}

/** Moves pin [index] to the end of [side] and closes the gap it left. */
internal fun movePinToSide(pins: List<SymbolPin>, index: Int, side: PinSide): List<SymbolPin> {
    val pin = pins[index]
    if (pin.side == side) return pins
    val end = pins.count { it.side == side }
    return pins.mapIndexed { i, p ->
        when {
            i == index -> p.copy(side = side, order = end)
            p.side == pin.side && p.order > pin.order -> p.copy(order = p.order - 1)
            else -> p
        }
    }
}

/** Swaps pin [index] with its neighbour on the same side. */
internal fun shiftPin(pins: List<SymbolPin>, index: Int, delta: Int): List<SymbolPin> {
    val pin = pins[index]
    val target = pin.order + delta
    val other = pins.indexOfFirst { it.side == pin.side && it.order == target }
    if (other < 0) return pins
    return pins.mapIndexed { i, p ->
        when (i) {
            index -> p.copy(order = target)
            other -> p.copy(order = pin.order)
            else -> p
        }
    }
}

/** Draws a symbol fitted to the available space, optionally with its port numbers. */
@Composable
fun SymbolPreview(symbol: CustomSymbol, modifier: Modifier = Modifier, showPinNumbers: Boolean = false) {
    val colors = LocalCircuitColors.current
    val textMeasurer = rememberTextMeasurer()
    val numberColor = MaterialTheme.colorScheme.tertiary
    val part = remember(symbol) { Part(0, PartKind.CUSTOM, "", 0, 0, value = symbol.params, symbol = symbol) }
    Canvas(modifier.semantics { contentDescription = "Preview of the ${symbol.title} symbol" }) {
        drawSymbol(part, textMeasurer, colors.part, if (showPinNumbers) numberColor else null)
    }
}

private fun DrawScope.drawSymbol(part: Part, textMeasurer: TextMeasurer, color: androidx.compose.ui.graphics.Color, numbers: androidx.compose.ui.graphics.Color?) {
    val transform = GridTransform.fit(Schematic(parts = listOf(part)), size, maxScale = 40.dp.toPx(), minScale = 1f)
    val stroke = (transform.scale * 0.1f).coerceIn(1.5f, 2.5.dp.toPx())
    drawPart(part, transform, color, stroke, textMeasurer)
    if (numbers == null) return
    val style = TextStyle(color = numbers, fontSize = (transform.scale * 0.5f).coerceIn(9.dp.toPx(), 14.dp.toPx()).toSp())
    part.pinPositions().forEachIndexed { i, pin ->
        val c = transform.toScreen(pin)
        drawCircle(numbers, radius = 3.dp.toPx(), center = c)
        val layout = textMeasurer.measure("${i + 1}", style)
        drawText(layout, topLeft = c + Offset(4.dp.toPx(), -layout.size.height - 2.dp.toPx()))
    }
}

@Composable
fun SymbolThumbnail(symbol: CustomSymbol) {
    SymbolPreview(symbol, Modifier.size(48.dp))
}
