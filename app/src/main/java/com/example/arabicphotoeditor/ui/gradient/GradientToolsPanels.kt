package com.example.arabicphotoeditor.ui.gradient

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arabicphotoeditor.R
import com.example.arabicphotoeditor.gradient.ColorLab
import com.example.arabicphotoeditor.gradient.ColorStop
import com.example.arabicphotoeditor.gradient.GradientPresets
import com.example.arabicphotoeditor.gradient.GradientSpec
import com.example.arabicphotoeditor.ui.theme.EditorColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun Modifier.selectedBorder(selected: Boolean, shape: androidx.compose.ui.graphics.Shape) =
    border(if (selected) 3.dp else 1.dp, if (selected) EditorColors.Accent else EditorColors.Border, shape)

private fun stopsBrush(stops: List<ColorStop>): Brush {
    val sorted = ColorLab.resample(stops, 12)
    return Brush.horizontalGradient(*sorted.map { it.position to Color(it.color) }.toTypedArray())
}

@Composable
private fun rememberStopsBrush(stops: List<ColorStop>): Brush = remember(stops) { stopsBrush(stops) }

@Composable
private fun Hint(@StringRes text: Int) =
    Text(stringResource(text), color = EditorColors.TextSecondary, fontSize = 13.sp)

@Composable
private fun Heading(@StringRes text: Int) =
    Text(stringResource(text), color = EditorColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

@StringRes
private fun harmonyLabel(h: ColorLab.Harmony?) = when (h) {
    ColorLab.Harmony.Monochrome -> R.string.harmony_monochrome
    ColorLab.Harmony.Analogous -> R.string.harmony_analogous
    ColorLab.Harmony.Complementary -> R.string.harmony_complementary
    ColorLab.Harmony.SplitComplementary -> R.string.harmony_split
    ColorLab.Harmony.Triadic -> R.string.harmony_triadic
    ColorLab.Harmony.Tetradic -> R.string.harmony_tetradic
    null -> R.string.harmony_none
}

@StringRes
private fun fixLabel(f: ColorLab.Fix) = when (f) {
    ColorLab.Fix.MoreContrast -> R.string.fix_contrast
    ColorLab.Fix.EvenLightness -> R.string.fix_lightness
    ColorLab.Fix.EvenSteps -> R.string.fix_steps
    ColorLab.Fix.Harmonize -> R.string.fix_harmonize
    ColorLab.Fix.Vibrance -> R.string.fix_vibrance
    ColorLab.Fix.Soften -> R.string.fix_soften
}

// ================================================================== Evolve (evolution, roulette, collision, DNA)

@Composable
fun EvolvePanel(vm: GradientViewModel, spec: GradientSpec, level: StudioLevel) {
    // Beginners get the two playful tools; Creative and Pro also get collision and DNA.
    val tools = buildList {
        add(R.string.tool_roulette)
        add(R.string.tool_evolution)
        if (level != StudioLevel.Easy) {
            if (spec.usesStops) add(R.string.tool_collision)
            add(R.string.tool_dna)
        }
    }
    var tool by rememberSaveable { mutableIntStateOf(R.string.tool_roulette) }
    val active = if (tool in tools) tool else tools.first()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(tools) { _, t -> StudioChip(stringResource(t), t == active, { tool = t }) }
    }
    when (active) {
        R.string.tool_roulette -> RoulettePanel(vm, spec)
        R.string.tool_evolution -> EvolutionPanel(vm)
        R.string.tool_collision -> CollisionPanel(vm, spec)
        R.string.tool_dna -> DnaPanel(vm)
    }
}

@Composable
private fun RoulettePanel(vm: GradientViewModel, spec: GradientSpec) {
    val colors = ColorLab.colorsOf(spec)
    val locked = remember(colors.size) { mutableStateListOf<Boolean>().apply { repeat(colors.size) { add(false) } } }
    var spinning by remember { mutableStateOf(false) }
    var lastRule by remember { mutableStateOf<ColorLab.Harmony?>(null) }
    val scope = rememberCoroutineScope()

    Hint(R.string.roulette_hint)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(colors.toList()) { i, c ->
            val isLocked = locked.getOrElse(i) { false }
            val desc = stringResource(R.string.roulette_color_n, i + 1)
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(c or (0xFF shl 24)))
                    .selectedBorder(isLocked, RoundedCornerShape(12.dp))
                    .semantics { contentDescription = desc }
                    .clickable(role = Role.Button) { if (i < locked.size) locked[i] = !locked[i] },
                contentAlignment = Alignment.Center,
            ) {
                if (isLocked) Text("🔒", fontSize = 16.sp)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        StudioChip(stringResource(if (spinning) R.string.roulette_spinning else R.string.roulette_spin), false, {
            if (!spinning) {
                spinning = true
                scope.launch {
                    val lockedNow = BooleanArray(colors.size) { locked.getOrElse(it) { false } }
                    var current = colors
                    // The wheel visibly slows down: quick results first, the last one is the keeper.
                    for (step in 0 until 9) {
                        val (rule, spun) = vm.spinRoulette(current, lockedNow)
                        vm.previewColors(spun)
                        lastRule = rule
                        current = spun
                        delay(60L + step * 35L)
                    }
                    vm.applyColors(current, R.string.history_roulette)
                    spinning = false
                }
            }
        }, enabled = !spinning && locked.any { !it })
        lastRule?.let {
            Text(stringResource(harmonyLabel(it)), color = EditorColors.TextSecondary, fontSize = 13.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EvolutionPanel(vm: GradientViewModel) {
    var strength by rememberSaveable { mutableStateOf(0.5f) }
    val parents = remember { mutableStateListOf<List<ColorStop>>() }
    val generation = vm.generation
    Hint(R.string.evolution_hint)
    LabeledSlider(R.string.evolution_strength, strength, 0.1f..1f, "${(strength * 100).toInt()}%", { strength = it })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioChip(stringResource(if (generation.isEmpty()) R.string.evolution_start else R.string.evolution_next), false, {
            vm.evolve(strength, parents.toList())
            parents.clear()
        })
        if (generation.isNotEmpty()) StudioChip(stringResource(R.string.evolution_clear), false, {
            parents.clear()
            vm.clearGeneration()
        })
    }
    if (generation.isNotEmpty()) {
        Hint(R.string.evolution_pick)
        for (rowStart in generation.indices step 4) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in rowStart until minOf(rowStart + 4, generation.size)) {
                    val stops = generation[i]
                    val isParent = parents.contains(stops)
                    val desc = stringResource(R.string.evolution_candidate_n, i + 1)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(rememberStopsBrush(stops))
                            .selectedBorder(isParent, RoundedCornerShape(10.dp))
                            .semantics { contentDescription = desc }
                            .combinedClickable(
                                role = Role.Button,
                                onClick = { vm.adoptCandidate(stops) },
                                onLongClick = { if (isParent) parents.remove(stops) else if (parents.size < 3) parents.add(stops) },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun CollisionPanel(vm: GradientViewModel, spec: GradientSpec) {
    val project = vm.project
    // Partners: the preset palettes and the other layers that are made of color stops.
    val partners = remember(project.layers, project.selectedIndex) {
        buildList {
            project.layers.forEachIndexed { i, layer ->
                if (i != project.selectedIndex && layer.spec.usesStops) add(layer.spec.stops)
            }
            GradientPresets.palettes.forEach { add(GradientPresets.stopsOf(it)) }
        }
    }
    var partner by rememberSaveable { mutableIntStateOf(0) }
    var impact by rememberSaveable { mutableStateOf(0.5f) }
    var zone by rememberSaveable { mutableStateOf(0.2f) }
    var energy by rememberSaveable { mutableStateOf(0.5f) }
    val chosen = partner.coerceIn(0, partners.lastIndex)

    Hint(R.string.collision_hint)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(partners) { i, stops ->
            val desc = stringResource(R.string.collision_partner_n, i + 1)
            Box(
                Modifier
                    .size(width = 68.dp, height = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(rememberStopsBrush(stops))
                    .selectedBorder(i == chosen, RoundedCornerShape(10.dp))
                    .semantics { contentDescription = desc }
                    .clickable(role = Role.Button) { partner = i },
            )
        }
    }
    LabeledSlider(R.string.collision_impact, impact, 0.15f..0.85f, "${(impact * 100).toInt()}%", { impact = it })
    LabeledSlider(R.string.collision_zone, zone, 0.04f..0.4f, "${(zone * 100).toInt()}%", { zone = it })
    LabeledSlider(R.string.collision_energy, energy, 0f..1f, "${(energy * 100).toInt()}%", { energy = it })
    StudioChip(stringResource(R.string.collision_run), false, { vm.collideWith(partners[chosen], impact, zone, energy) })
}

@Composable
private fun DnaPanel(vm: GradientViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val code = vm.currentDna()
    var input by rememberSaveable { mutableStateOf("") }
    Hint(R.string.dna_hint)
    Text(
        code,
        color = EditorColors.TextPrimary,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(EditorColors.SurfaceRaised)
            .padding(10.dp),
    )
    StudioChip(stringResource(R.string.dna_copy), false, {
        clipboard.setText(AnnotatedString(code))
        Toast.makeText(context, R.string.dna_copied, Toast.LENGTH_SHORT).show()
    })
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        label = { Text(stringResource(R.string.dna_paste_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioChip(stringResource(R.string.dna_paste), false, { clipboard.getText()?.text?.let { input = it.trim() } })
        StudioChip(stringResource(R.string.dna_apply), false, {
            if (!vm.applyDna(input)) Toast.makeText(context, R.string.dna_invalid, Toast.LENGTH_SHORT).show()
        }, enabled = input.isNotBlank())
        StudioChip(stringResource(R.string.dna_breed), false, {
            if (!vm.breedWith(input)) Toast.makeText(context, R.string.dna_invalid, Toast.LENGTH_SHORT).show()
        }, enabled = input.isNotBlank())
    }
}

// ================================================================== Lab (math analysis and suggestions)

@Composable
fun LabPanel(vm: GradientViewModel, spec: GradientSpec) {
    val colors = ColorLab.colorsOf(spec)
    val analysis = remember(colors.toList()) { ColorLab.analyze(colors) }
    val suggestions = remember(colors.toList()) { ColorLab.suggest(colors) }

    Hint(R.string.lab_hint)
    LightnessChart(colors)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabRow(R.string.lab_harmony, stringResource(harmonyLabel(analysis.harmony)))
        LabRow(R.string.lab_contrast, String.format("%.1f : 1", analysis.contrastRatio))
        LabRow(R.string.lab_lightness, "${(analysis.minLightness * 100).toInt()}% – ${(analysis.maxLightness * 100).toInt()}%")
        LabRow(R.string.lab_chroma, "${(analysis.averageChroma / 0.3f * 100).toInt().coerceAtMost(100)}%")
        if (analysis.lowContrast) Hint(R.string.lab_warn_contrast)
        if (analysis.bandingRisk) Hint(R.string.lab_warn_banding)
        if (!analysis.monotonic && colors.size > 2) Hint(R.string.lab_note_bumpy)
    }
    if (suggestions.isEmpty()) {
        Hint(R.string.lab_nothing_to_fix)
    } else {
        Heading(R.string.lab_suggestions)
        for (s in suggestions) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(96.dp)
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(rememberStopsBrush(remember(s) { ColorLab.stopsOf(s.colors) }))
                        .border(1.dp, EditorColors.Border, RoundedCornerShape(8.dp)),
                )
                Text(
                    stringResource(fixLabel(s.fix)) + (s.rule?.let { " (" + stringResource(harmonyLabel(it)) + ")" } ?: ""),
                    color = EditorColors.TextPrimary,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                StudioChip(stringResource(R.string.lab_apply), false, { vm.applyColors(s.colors, R.string.history_lab) })
            }
        }
    }
}

@Composable
private fun LabRow(@StringRes label: Int, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), color = EditorColors.TextSecondary, fontSize = 14.sp)
        Text(value, color = EditorColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Lightness along the palette: a rising or falling line means a clean ramp, a zigzag means a bumpy one. */
@Composable
private fun LightnessChart(colors: IntArray) {
    val lightness = colors.map { ColorLab.toLch(it)[0] }
    val line = EditorColors.Accent
    val grid = EditorColors.Border
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(EditorColors.SurfaceRaised),
    ) {
        val pad = 8.dp.toPx()
        drawLine(grid, Offset(pad, size.height / 2), Offset(size.width - pad, size.height / 2), 1.dp.toPx())
        val path = Path()
        lightness.forEachIndexed { i, l ->
            val x = pad + (size.width - 2 * pad) * (if (lightness.size == 1) 0f else i / (lightness.size - 1f))
            val y = pad + (size.height - 2 * pad) * (1f - l)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            drawCircle(Color(colors[i] or (0xFF shl 24)), 6.dp.toPx(), Offset(x, y))
        }
        drawPath(path, line, style = Stroke(2.dp.toPx()))
        colors.forEachIndexed { i, c ->
            val x = pad + (size.width - 2 * pad) * (if (lightness.size == 1) 0f else i / (lightness.size - 1f))
            val y = pad + (size.height - 2 * pad) * (1f - lightness[i])
            drawCircle(Color(c or (0xFF shl 24)), 5.dp.toPx(), Offset(x, y))
        }
    }
}

// ================================================================== Animate

@Composable
fun AnimatePanel(vm: GradientViewModel, level: StudioLevel) {
    val project = vm.project
    val anim = project.animation
    val pro = level == StudioLevel.Pro

    Hint(R.string.anim_hint)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        StudioChip(stringResource(if (vm.playing) R.string.anim_stop else R.string.anim_play), vm.playing, vm::togglePlay, enabled = anim.isActive)
        if (!anim.isActive) Text(stringResource(R.string.anim_pick_motion), color = EditorColors.TextSecondary, fontSize = 13.sp)
    }

    Heading(R.string.anim_motion)
    ChipOptions(R.string.anim_rotate, listOf(-2, -1, 0, 1, 2), anim.rotate, { if (it == 0) stringResource(R.string.anim_off) else "${it}×" }) { v ->
        vm.editAnimation { it.copy(rotate = v) }
    }
    ChipOptions(R.string.anim_flow, listOf(0, 1, 2), anim.flow, { if (it == 0) stringResource(R.string.anim_off) else "${it}×" }) { v ->
        vm.editAnimation { it.copy(flow = v) }
    }
    ChipOptions(R.string.anim_hue, listOf(0, 1, 2), anim.hueCycles, { if (it == 0) stringResource(R.string.anim_off) else "${it}×" }) { v ->
        vm.editAnimation { it.copy(hueCycles = v) }
    }
    LabeledSlider(R.string.anim_orbit, anim.orbit, 0f..0.4f, "${(anim.orbit * 100).toInt()}%", { v -> vm.editAnimation { it.copy(orbit = v) } })
    LabeledSlider(R.string.anim_pulse, anim.pulse, 0f..0.5f, "${(anim.pulse * 100).toInt()}%", { v -> vm.editAnimation { it.copy(pulse = v) } })

    Heading(R.string.anim_morph)
    Hint(R.string.anim_morph_hint)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioChip(stringResource(R.string.anim_morph_save), project.morphTarget != null, vm::saveMorphTarget)
        StudioChip(stringResource(R.string.anim_morph_clear), false, vm::clearMorphTarget, enabled = project.morphTarget != null)
    }

    Heading(R.string.anim_timing)
    ChipOptions(R.string.anim_duration, listOf(1000, 2000, 3000, 5000, 8000), anim.durationMs, { "${it / 1000}s" }) { v ->
        vm.editAnimation { it.copy(durationMs = v) }
    }
    if (pro) {
        ChipOptions(R.string.anim_fps, listOf(12, 15, 24, 30), anim.fps, { "$it" }) { v -> vm.editAnimation { it.copy(fps = v) } }
    }
}

@Composable
private fun ChipOptions(
    @StringRes label: Int,
    options: List<Int>,
    selected: Int,
    text: @Composable (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    Text(stringResource(label), color = EditorColors.TextPrimary, fontSize = 14.sp)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(options) { _, v -> StudioChip(text(v), v == selected, { onSelect(v) }) }
    }
}

/** Shared by the Project tab: export of the animation. */
@Composable
fun AnimationExportSection(
    vm: GradientViewModel,
    animSize: Int,
    onAnimSize: (Int) -> Unit,
    loops: Int,
    onLoops: (Int) -> Unit,
    onExportGif: () -> Unit,
    onExportMp4: () -> Unit,
) {
    val anim = vm.project.animation
    Heading(R.string.export_animation)
    if (!anim.isActive) {
        Hint(R.string.export_animation_none)
        return
    }
    val progress = vm.exportProgress
    ChipOptions(R.string.export_anim_size, listOf(360, 480, 720, 1080), animSize, { "$it" }, onAnimSize)
    ChipOptions(R.string.export_mp4_loops, listOf(1, 2, 3), loops, { "${it}×" }, onLoops)
    Hint(R.string.export_gif_note)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioChip(stringResource(R.string.export_gif), false, onExportGif, enabled = !vm.busy)
        StudioChip(stringResource(R.string.export_mp4), false, onExportMp4, enabled = !vm.busy)
    }
    if (progress != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.weight(1f),
                color = EditorColors.Accent,
            )
            StudioChip(stringResource(R.string.export_cancel), false, vm::cancelExport)
        }
    }
}
