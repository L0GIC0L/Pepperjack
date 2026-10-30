package io.github.l0gic0l.pepperjack.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.pepperjack.data.LibraryModel
import io.github.l0gic0l.pepperjack.model.AnalysisConfig
import io.github.l0gic0l.pepperjack.model.AnalysisType
import io.github.l0gic0l.pepperjack.model.CustomSymbol
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PartCategory
import io.github.l0gic0l.pepperjack.model.PartDef
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.PartLibrary
import io.github.l0gic0l.pepperjack.model.PotValue
import io.github.l0gic0l.pepperjack.model.Schematic
import io.github.l0gic0l.pepperjack.model.SpiceDialect
import io.github.l0gic0l.pepperjack.model.SwitchValue
import io.github.l0gic0l.pepperjack.model.TransformerValue
import io.github.l0gic0l.pepperjack.model.ValueEditor
import io.github.l0gic0l.pepperjack.model.def
import io.github.l0gic0l.pepperjack.spice.ModelFamily
import io.github.l0gic0l.pepperjack.spice.SpiceModels
import kotlin.math.roundToInt
import io.github.l0gic0l.pepperjack.ui.schematic.drawSchematicPreview
import io.github.l0gic0l.pepperjack.ui.theme.LocalCircuitColors

/** A part to place: a built-in symbol, or a user-made symbol for a library subcircuit. */
data class PartChoice(val kind: PartKind, val symbol: CustomSymbol? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartPickerSheet(symbols: List<CustomSymbol>, onDismiss: () -> Unit, onPick: (PartChoice) -> Unit) {
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    fun matches(title: String, extra: String = "") = q.isEmpty() || title.lowercase().contains(q) || extra.lowercase().contains(q)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search parts and models") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PartCategory.entries.forEach { category ->
                val defs = PartLibrary.all.filter { it.category == category }
                    .filter { def -> matches(def.title, def.models.joinToString(" ") { it.name }) }
                val custom = if (category == PartCategory.CUSTOM) symbols.filter { matches(it.title, it.subckt) } else emptyList()
                if (defs.isEmpty() && custom.isEmpty()) return@forEach
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        category.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(defs, key = { it.kind }) { def ->
                    PartTile(def.title, Part(0, def.kind, "", 0, 0, value = def.defaultValue)) { onPick(PartChoice(def.kind)) }
                }
                items(custom, key = { "symbol-" + it.id }) { symbol ->
                    PartTile(symbol.title, Part(0, PartKind.CUSTOM, "", 0, 0, symbol = symbol)) {
                        onPick(PartChoice(PartKind.CUSTOM, symbol))
                    }
                }
            }
            if (q.isEmpty() && symbols.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Make symbols for vendor subcircuits in the Model library; they appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PartTile(title: String, preview: Part, onClick: () -> Unit) {
    val colors = LocalCircuitColors.current
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val sheet = remember(preview) { Schematic(parts = listOf(preview)) }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
            Canvas(Modifier.size(56.dp)) { drawSchematicPreview(sheet, colors, textMeasurer) }
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val suffixes = listOf("p", "n", "u", "m", "k", "Meg")

/** Whether a model from the user's library fits [this] symbol. */
fun PartDef.accepts(model: LibraryModel): Boolean = accepts(model.type, model.pins.size)

/** Whether a model of [type] (a `.model` type, or SUBCKT with [pins] ports) fits [this] symbol. */
fun PartDef.accepts(type: String, pins: Int): Boolean {
    if (type == LibraryModel.SUBCKT) return pins == this.pins.size && editor == ValueEditor.MODEL
    val types = families.flatMap { family ->
        when (family) {
            ModelFamily.DIODE, ModelFamily.SCHOTTKY, ModelFamily.ZENER, ModelFamily.LED -> listOf("D")
            ModelFamily.NPN -> listOf("NPN")
            ModelFamily.PNP -> listOf("PNP")
            ModelFamily.NJF -> listOf("NJF")
            ModelFamily.PJF -> listOf("PJF")
            ModelFamily.NMOS -> listOf("NMOS", "VDMOS")
            ModelFamily.PMOS -> listOf("PMOS", "VDMOS")
            ModelFamily.SWITCH -> listOf("SW")
            else -> emptyList()
        }
    }
    return type in types
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PartEditorSheet(
    part: Part,
    libraryModels: List<LibraryModel>,
    onDismiss: () -> Unit,
    onBrowseOnline: () -> Unit,
    onSave: (ref: String, value: String) -> Unit,
) {
    val def = part.def
    var ref by remember(part.id) { mutableStateOf(part.ref) }
    var value by remember(part.id) { mutableStateOf(part.value.ifBlank { if (def.editor == ValueEditor.MODEL) def.defaultValue else part.value }) }
    var source by remember(part.id) { mutableStateOf(SourceSpec.parse(part.value)) }
    val unit = if (part.kind == PartKind.CURRENT) "A" else "V"

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(def.title, style = MaterialTheme.typography.titleLarge)
            if (part.kind != PartKind.GROUND && part.kind != PartKind.LABEL) {
                OutlinedTextField(
                    value = ref,
                    onValueChange = { ref = it },
                    label = { Text("Reference") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            when (def.editor) {
                ValueEditor.NONE -> Text(
                    when (part.kind) {
                        PartKind.GROUND -> "Ground is SPICE node 0, the reference for every voltage."
                        PartKind.OPAMP -> "Ideal op-amp: open-loop gain of 10⁶, no bandwidth or supply limits."
                        PartKind.AMMETER -> "Measures the current flowing in at the + pin, with zero resistance. " +
                            "The reading shows while live simulating; probe it to plot the current."
                        else -> def.valueHint
                    },
                )
                ValueEditor.SOURCE -> SourceEditor(source, unit) { source = it }
                ValueEditor.MODEL -> ModelPicker(def, value, libraryModels, onBrowseOnline) { value = it }
                ValueEditor.POT -> {
                    val pot = PotValue.parse(value)
                    OutlinedTextField(
                        value = pot.resistance,
                        onValueChange = { value = pot.copy(resistance = it.trim()).format() },
                        label = { Text("Total resistance") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Wiper position: ${(pot.position * 100).roundToInt()}% from pin 1", style = MaterialTheme.typography.bodyMedium)
                    Slider(value = pot.position.toFloat(), onValueChange = { value = pot.copy(position = it.toDouble()).format() })
                }
                ValueEditor.TRANSFORMER -> {
                    val t = TransformerValue.parse(value)
                    NumberField("Primary inductance", t.primary) { value = t.copy(primary = it.trim()).format() }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("Primary turns", fmtTurns(t.primaryTurns), Modifier.weight(1f)) { text ->
                            text.toDoubleOrNull()?.takeIf { it > 0 }?.let { value = t.copy(primaryTurns = it).format() }
                        }
                        NumberField("Secondary turns", fmtTurns(t.secondaryTurns), Modifier.weight(1f)) { text ->
                            text.toDoubleOrNull()?.takeIf { it > 0 }?.let { value = t.copy(secondaryTurns = it).format() }
                        }
                    }
                    NumberField("Coupling k (0–1)", t.coupling) { value = t.copy(coupling = it.trim()).format() }
                    Text(
                        "Secondary inductance ${t.secondary()}. Each winding needs a DC path to ground.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ValueEditor.SPST -> Choice(listOf("open" to "Open", "closed" to "Closed"), if (SwitchValue.isClosed(value)) "closed" else "open") { value = it }
                ValueEditor.SPDT -> Choice(listOf("A" to "COM to A", "B" to "COM to B"), if (SwitchValue.isB(value)) "B" else "A") { value = it }
                ValueEditor.LOGIC -> {
                    NumberField(def.valueLabel, value) { value = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("1.8", "3.3", "5", "12").forEach { v ->
                            FilterChip(selected = value.trim() == v, onClick = { value = v }, label = { Text("$v V") })
                        }
                    }
                    Text(def.valueHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text(def.valueLabel) },
                        supportingText = { Text(def.valueHint) },
                        singleLine = def.editor != ValueEditor.EXPRESSION,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = if (def.editor == ValueEditor.EXPRESSION) FontFamily.Monospace else FontFamily.Default,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (part.kind in setOf(PartKind.RESISTOR, PartKind.CAPACITOR, PartKind.INDUCTOR, PartKind.VOLTMETER)) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            suffixes.forEach { suffix ->
                                AssistChip(
                                    onClick = { value = value.trimEnd { it.isLetter() } + suffix },
                                    label = { Text(suffix) },
                                )
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp).navigationBarsPadding(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.size(8.dp))
                Button(onClick = {
                    val newValue = when (def.editor) {
                        ValueEditor.SOURCE -> source.toSpice()
                        ValueEditor.LABEL -> value.trim().ifEmpty { "net" }
                        else -> value
                    }
                    onSave(if (part.kind == PartKind.LABEL) newValue else ref, newValue)
                }) { Text("Save") }
            }
        }
    }
}

private fun fmtTurns(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

@Composable
private fun Choice(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (key, label) ->
            SegmentedButton(
                selected = selected == key,
                onClick = { onSelect(key) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(label) },
            )
        }
    }
}

/** Model name field plus a browsable list of built-in and library models that fit the symbol. */
@Composable
private fun ModelPicker(
    def: PartDef,
    value: String,
    libraryModels: List<LibraryModel>,
    onBrowseOnline: () -> Unit,
    onChange: (String) -> Unit,
) {
    val current = value.trim().substringBefore(' ')
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(def.valueLabel) },
        supportingText = { Text("Pick a model below, or type any model name defined in your directives or library.") },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )
    val fitting = libraryModels.filter { def.accepts(it) }
    // A downloaded vendor file can hold hundreds of models; list a page of those matching the field.
    val fromLibrary = if (fitting.size <= LIBRARY_PAGE) fitting else fitting.filter { it.name.contains(current, true) }.take(LIBRARY_PAGE)
    def.families.forEach { family ->
        val models = SpiceModels.family(family)
        if (models.isEmpty()) return@forEach
        Text(family.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column {
                models.forEach { model ->
                    ModelRow(model.name, model.description, model.name.equals(current, true)) { onChange(model.name) }
                }
            }
        }
    }
    if (fitting.isNotEmpty()) {
        Text("From your model library", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (fromLibrary.size < fitting.size) {
            Text(
                "${fitting.size} fit this part. Type part of a name above to find one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (fromLibrary.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    fromLibrary.forEach { model ->
                        val detail = if (model.isSubckt) "${model.file} · subcircuit (${model.pins.joinToString(" ")})" else "${model.file} · ${model.type}"
                        ModelRow(model.name, detail, model.name.equals(current, true)) { onChange(model.name) }
                    }
                }
            }
        }
    }
    OutlinedButton(onClick = onBrowseOnline, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.TravelExplore, null, Modifier.size(18.dp))
        Text("Find more models online", Modifier.padding(start = 8.dp))
    }
}

private const val LIBRARY_PAGE = 30

@Composable
private fun ModelRow(name: String, description: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(name, fontFamily = FontFamily.Monospace) },
        supportingContent = { Text(description, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SourceEditor(spec: SourceSpec, unit: String, onChange: (SourceSpec) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SourceType.entries.forEachIndexed { index, type ->
            SegmentedButton(
                selected = spec.type == type,
                onClick = {
                    onChange(if (type == SourceType.CUSTOM) spec.copy(type = type, custom = spec.toSpice()) else spec.copy(type = type))
                },
                shape = SegmentedButtonDefaults.itemShape(index, SourceType.entries.size),
                label = { Text(type.label, maxLines = 1) },
            )
        }
    }
    when (spec.type) {
        SourceType.DC -> NumberField("DC value ($unit)", spec.dc) { onChange(spec.copy(dc = it)) }
        SourceType.AC -> {
            NumberField("DC bias ($unit)", spec.dc) { onChange(spec.copy(dc = it)) }
            NumberField("AC magnitude ($unit)", spec.acMagnitude.ifEmpty { "1" }) { onChange(spec.copy(acMagnitude = it)) }
        }
        SourceType.SINE -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Offset", spec.sinOffset, Modifier.weight(1f)) { onChange(spec.copy(sinOffset = it)) }
                NumberField("Amplitude", spec.sinAmplitude, Modifier.weight(1f)) { onChange(spec.copy(sinAmplitude = it)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Frequency (Hz)", spec.sinFrequency, Modifier.weight(1f)) { onChange(spec.copy(sinFrequency = it)) }
                NumberField("AC magnitude", spec.acMagnitude, Modifier.weight(1f)) { onChange(spec.copy(acMagnitude = it)) }
            }
        }
        SourceType.PULSE -> {
            SourceSpec.pulseLabels.chunked(2).forEachIndexed { row, labels ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    labels.forEachIndexed { column, label ->
                        val index = row * 2 + column
                        NumberField(label, spec.pulse[index], Modifier.weight(1f)) { text ->
                            onChange(spec.copy(pulse = spec.pulse.toMutableList().also { it[index] = text }))
                        }
                    }
                    if (labels.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        SourceType.CUSTOM -> OutlinedTextField(
            value = spec.custom,
            onValueChange = { onChange(spec.copy(custom = it)) },
            label = { Text("SPICE source value") },
            supportingText = { Text("Anything ngspice accepts, e.g. PWL(0 0 1m 5), EXP(...), SFFM(...)") },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text(
        spec.toSpice(),
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisSheet(
    config: AnalysisConfig,
    dialect: SpiceDialect?,
    defaultDialect: SpiceDialect,
    isSchematic: Boolean,
    sourceNames: List<String>,
    onDismiss: () -> Unit,
    onApply: (AnalysisConfig, SpiceDialect?) -> Unit,
) {
    var draft by remember { mutableStateOf(config) }
    var chosenDialect by remember { mutableStateOf(dialect) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Simulation settings", style = MaterialTheme.typography.titleLarge)
            Text("SPICE dialect", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            DialectPicker(chosenDialect, defaultDialect) { chosenDialect = it }

            if (isSchematic) {
                Text("Analysis", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    AnalysisType.entries.forEachIndexed { index, type ->
                        SegmentedButton(
                            selected = draft.type == type,
                            onClick = { draft = draft.copy(type = type) },
                            shape = SegmentedButtonDefaults.itemShape(index, AnalysisType.entries.size),
                            label = {
                                Text(
                                    when (type) {
                                        AnalysisType.OP -> "OP"
                                        AnalysisType.TRAN -> "Transient"
                                        AnalysisType.AC -> "AC"
                                        AnalysisType.DC -> "DC"
                                    },
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
                Text(draft.type.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (draft.type) {
                    AnalysisType.OP -> Text("Solves the DC bias point: every node voltage and branch current.")
                    AnalysisType.TRAN -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField("Stop time", draft.tranStop, Modifier.weight(1f)) { draft = draft.copy(tranStop = it) }
                            NumberField("Time step", draft.tranStep, Modifier.weight(1f)) { draft = draft.copy(tranStep = it) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField("Start saving at", draft.tranStart, Modifier.weight(1f)) { draft = draft.copy(tranStart = it) }
                            NumberField("Max step (optional)", draft.tranMaxStep, Modifier.weight(1f)) { draft = draft.copy(tranMaxStep = it) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = draft.tranUic, onCheckedChange = { draft = draft.copy(tranUic = it) })
                            Text("Skip the initial operating point (UIC)")
                        }
                    }
                    AnalysisType.AC -> {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf("dec" to "Decade", "oct" to "Octave", "lin" to "Linear").forEachIndexed { index, (key, label) ->
                                SegmentedButton(
                                    selected = draft.acSweep == key,
                                    onClick = { draft = draft.copy(acSweep = key) },
                                    shape = SegmentedButtonDefaults.itemShape(index, 3),
                                    label = { Text(label) },
                                )
                            }
                        }
                        NumberField(if (draft.acSweep == "lin") "Total points" else "Points per ${if (draft.acSweep == "dec") "decade" else "octave"}", draft.acPoints.toString()) {
                            draft = draft.copy(acPoints = it.filter(Char::isDigit).toIntOrNull()?.coerceIn(1, 100_000) ?: draft.acPoints)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField("Start (Hz)", draft.acStart, Modifier.weight(1f)) { draft = draft.copy(acStart = it) }
                            NumberField("Stop (Hz)", draft.acStop, Modifier.weight(1f)) { draft = draft.copy(acStop = it) }
                        }
                    }
                    AnalysisType.DC -> {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }) {
                                Text("Sweep ${draft.dcSource}")
                                Icon(Icons.Outlined.ArrowDropDown, null)
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                (sourceNames.ifEmpty { listOf(draft.dcSource) }).forEach { name ->
                                    DropdownMenuItem(text = { Text(name) }, onClick = {
                                        draft = draft.copy(dcSource = name)
                                        expanded = false
                                    })
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField("Start", draft.dcStart, Modifier.weight(1f)) { draft = draft.copy(dcStart = it) }
                            NumberField("Stop", draft.dcStop, Modifier.weight(1f)) { draft = draft.copy(dcStop = it) }
                            NumberField("Step", draft.dcStep, Modifier.weight(1f)) { draft = draft.copy(dcStep = it) }
                        }
                    }
                }
                Text(
                    draft.toSpice(),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "An analysis line in the SPICE directives (.tran, .ac, …) takes precedence over this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Analyses come from the netlist itself (.tran, .ac, .dc, .op, .noise, .step and .control blocks).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp).navigationBarsPadding(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.size(8.dp))
                Button(onClick = { onApply(draft, chosenDialect) }) { Text("Apply") }
            }
        }
    }
}

@Composable
private fun DialectPicker(selected: SpiceDialect?, defaultDialect: SpiceDialect, onSelect: (SpiceDialect?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                selected?.label ?: "App default (${defaultDialect.label})",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Outlined.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("App default (${defaultDialect.label})") },
                onClick = { onSelect(null); expanded = false },
            )
            SpiceDialect.entries.forEach { dialect ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(dialect.label)
                            Text(
                                dialect.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = { onSelect(dialect); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectivesSheet(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("SPICE directives", style = MaterialTheme.typography.titleLarge)
            Text(
                "Lines added to the generated netlist, like LTspice's SPICE directive: .param, .model, " +
                    ".include, .options, .meas, .step, .ic, or an analysis such as .tran 1u 10m.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text(".param R=1k\n.include TL072.lib", fontFamily = FontFamily.Monospace) },
                minLines = 6,
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp).navigationBarsPadding(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.size(8.dp))
                Button(onClick = { onSave(text) }) { Text("Save") }
            }
        }
    }
}
