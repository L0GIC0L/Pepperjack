package io.github.l0gic0l.pepperjack.ui.editor

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import io.github.l0gic0l.pepperjack.model.AnalysisConfig
import io.github.l0gic0l.pepperjack.model.AnalysisType
import io.github.l0gic0l.pepperjack.model.Part
import io.github.l0gic0l.pepperjack.model.PartCategory
import io.github.l0gic0l.pepperjack.model.PartDef
import io.github.l0gic0l.pepperjack.model.PartKind
import io.github.l0gic0l.pepperjack.model.PartLibrary
import io.github.l0gic0l.pepperjack.model.Schematic
import io.github.l0gic0l.pepperjack.model.SpiceDialect
import io.github.l0gic0l.pepperjack.ui.schematic.drawSchematicPreview
import io.github.l0gic0l.pepperjack.ui.theme.LocalCircuitColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartPickerSheet(onDismiss: () -> Unit, onPick: (PartKind) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PartCategory.entries.forEach { category ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        category.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(PartLibrary.all.filter { it.category == category }, key = { it.kind }) { def ->
                    PartTile(def) { onPick(def.kind) }
                }
            }
        }
    }
}

@Composable
private fun PartTile(def: PartDef, onClick: () -> Unit) {
    val colors = LocalCircuitColors.current
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val preview = remember(def.kind) { Schematic(parts = listOf(Part(0, def.kind, "", 0, 0))) }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
            Canvas(Modifier.size(56.dp)) { drawSchematicPreview(preview, colors, textMeasurer) }
            Text(
                def.title,
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PartEditorSheet(part: Part, onDismiss: () -> Unit, onSave: (ref: String, value: String) -> Unit) {
    val def = PartLibrary[part.kind]
    var ref by remember(part.id) { mutableStateOf(part.ref) }
    var value by remember(part.id) { mutableStateOf(part.value) }
    var source by remember(part.id) { mutableStateOf(SourceSpec.parse(part.value)) }
    val isSource = part.kind == PartKind.VOLTAGE || part.kind == PartKind.CURRENT
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
            when {
                part.kind == PartKind.GROUND -> Text("Ground is SPICE node 0, the reference for every voltage.")
                part.kind == PartKind.OPAMP -> Text("Ideal op-amp: open-loop gain of 10⁶, no bandwidth or supply limits.")
                part.kind == PartKind.OPAMP5 -> Text(
                    "Single-pole op-amp: 100 dB open-loop gain, 10 MHz gain-bandwidth, output limited to the V+ and V− rails.",
                )
                isSource -> SourceEditor(source, unit) { source = it }
                def.models.isNotEmpty() -> {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text(def.valueLabel) },
                        supportingText = { Text("${def.valueHint}. Built-in models below; vendor models from the Model library.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        def.models.forEach { model ->
                            FilterChip(selected = value.equals(model, true), onClick = { value = model }, label = { Text(model) })
                        }
                    }
                }
                else -> {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text(def.valueLabel) },
                        supportingText = { Text(def.valueHint) },
                        singleLine = part.kind != PartKind.BEHAVIORAL,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = if (part.kind == PartKind.BEHAVIORAL) FontFamily.Monospace else FontFamily.Default,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (part.kind in setOf(PartKind.RESISTOR, PartKind.CAPACITOR, PartKind.INDUCTOR)) {
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
                    val newValue = when {
                        isSource -> source.toSpice()
                        part.kind == PartKind.LABEL -> value.trim().ifEmpty { "net" }
                        else -> value
                    }
                    onSave(if (part.kind == PartKind.LABEL) newValue else ref, newValue)
                }) { Text("Save") }
            }
        }
    }
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
