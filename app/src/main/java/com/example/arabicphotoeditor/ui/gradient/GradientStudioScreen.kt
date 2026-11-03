package com.example.arabicphotoeditor.ui.gradient

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.arabicphotoeditor.R
import com.example.arabicphotoeditor.gradient.BlendMode
import com.example.arabicphotoeditor.gradient.GradientPresets
import com.example.arabicphotoeditor.gradient.GradientSpec
import com.example.arabicphotoeditor.gradient.GradientType
import com.example.arabicphotoeditor.gradient.Spread
import com.example.arabicphotoeditor.ui.theme.EditorColors

/** Three ways to use the same tool: each level reveals more controls, never a different tool. */
enum class StudioLevel(@StringRes val label: Int) {
    Easy(R.string.level_easy),
    Creative(R.string.level_creative),
    Pro(R.string.level_pro),
}

enum class StudioTab(@StringRes val label: Int) {
    Style(R.string.tab_style),
    /** Its label depends on the layer type, see [sourceTabLabel]. */
    Source(R.string.tab_source),
    Colors(R.string.tab_colors),
    Shape(R.string.tab_shape),
    Effects(R.string.tab_effects),
    Evolve(R.string.tab_evolve),
    Lab(R.string.tab_lab),
    Animate(R.string.tab_animate),
    Layers(R.string.tab_layers),
    History(R.string.tab_history),
    Project(R.string.tab_project),
}

/** What a finger does on the preview, decided by the selected layer type and the open tab. */
private enum class PreviewMode { MoveCenter, MoveGravityPoint, MoveLight, DrawPath, None }

private val GradientType.isBasic: Boolean get() = ordinal <= GradientType.Diamond.ordinal

/** Only the tabs that matter for the selected layer and level are offered. */
private fun tabsFor(level: StudioLevel, spec: GradientSpec): List<StudioTab> = buildList {
    add(StudioTab.Style)
    if (!spec.type.isBasic) add(StudioTab.Source)
    if (spec.usesStops) add(StudioTab.Colors)
    if (level != StudioLevel.Easy && spec.type.isBasic) add(StudioTab.Shape)
    if (level != StudioLevel.Easy) add(StudioTab.Effects)
    add(StudioTab.Evolve)
    if (level != StudioLevel.Easy) {
        add(StudioTab.Lab)
        add(StudioTab.Animate)
    }
    if (level == StudioLevel.Pro) {
        add(StudioTab.Layers)
        add(StudioTab.History)
    }
    add(StudioTab.Project)
}

@StringRes
private fun sourceTabLabel(type: GradientType) = when (type) {
    GradientType.Mesh -> R.string.tab_source_mesh
    GradientType.Gravity -> R.string.tab_source_gravity
    GradientType.Path -> R.string.tab_source_path
    GradientType.Map -> R.string.tab_source_map
    else -> R.string.tab_source
}

@StringRes
private fun typeLabel(type: GradientType) = when (type) {
    GradientType.Linear -> R.string.type_linear
    GradientType.Radial -> R.string.type_radial
    GradientType.Conic -> R.string.type_conic
    GradientType.Spiral -> R.string.type_spiral
    GradientType.Diamond -> R.string.type_diamond
    GradientType.Mesh -> R.string.type_mesh
    GradientType.Gravity -> R.string.type_gravity
    GradientType.Path -> R.string.type_path
    GradientType.Map -> R.string.type_map
}

@StringRes
private fun blendLabel(mode: BlendMode) = when (mode) {
    BlendMode.Normal -> R.string.blend_normal
    BlendMode.Multiply -> R.string.blend_multiply
    BlendMode.Screen -> R.string.blend_screen
    BlendMode.Overlay -> R.string.blend_overlay
    BlendMode.SoftLight -> R.string.blend_soft_light
    BlendMode.Add -> R.string.blend_add
    BlendMode.Difference -> R.string.blend_difference
}

@StringRes
private fun spreadLabel(spread: Spread) = when (spread) {
    Spread.Clamp -> R.string.spread_clamp
    Spread.Repeat -> R.string.spread_repeat
    Spread.Mirror -> R.string.spread_mirror
}

@Composable
fun GradientStudioScreen(onBack: () -> Unit, vm: GradientViewModel = viewModel()) {
    var level by rememberSaveable { mutableStateOf(StudioLevel.Easy) }
    var tab by rememberSaveable { mutableStateOf(StudioTab.Style) }
    var stopIndex by rememberSaveable { mutableIntStateOf(0) }
    var exportSize by rememberSaveable { mutableIntStateOf(2048) }
    var meshIndex by rememberSaveable { mutableIntStateOf(0) }
    var gravityIndex by rememberSaveable { mutableIntStateOf(0) }
    var lightIndex by rememberSaveable { mutableIntStateOf(0) }
    var drawingPath by rememberSaveable { mutableStateOf(false) }
    var animSize by rememberSaveable { mutableIntStateOf(480) }
    var animLoops by rememberSaveable { mutableIntStateOf(1) }

    val project = vm.project
    val spec = project.selected.spec
    val tabs = tabsFor(level, spec)
    val activeTab = if (tab in tabs) tab else tabs.first()
    val selectedStop = stopIndex.coerceIn(0, spec.stops.lastIndex)
    val selectedGravity = gravityIndex.coerceIn(0, spec.gravity.points.lastIndex.coerceAtLeast(0))
    val lights = project.lighting.lights
    val selectedLight = if (lights.isEmpty()) -1 else lightIndex.coerceIn(0, lights.lastIndex)

    val mode = when {
        activeTab == StudioTab.Effects && selectedLight >= 0 -> PreviewMode.MoveLight
        activeTab == StudioTab.Source && spec.type == GradientType.Gravity -> PreviewMode.MoveGravityPoint
        activeTab == StudioTab.Source && spec.type == GradientType.Path && drawingPath -> PreviewMode.DrawPath
        spec.type.isBasic -> PreviewMode.MoveCenter
        else -> PreviewMode.None
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) vm.exportPng(uri, exportSize)
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.saveProject(uri)
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openProject(uri)
    }
    val gifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/gif")) { uri ->
        if (uri != null) vm.exportGif(uri, minOf(animSize, 720))
    }
    val mp4Launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        if (uri != null) vm.exportMp4(uri, animSize, animLoops)
    }
    val mapPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.loadMapImage(uri)
    }

    val context = LocalContext.current
    val message = vm.message
    LaunchedEffect(message) {
        if (message != null) {
            Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
            vm.messageShown()
        }
    }

    Surface(color = EditorColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            StudioTopBar(vm, onBack)
            LevelRow(level) { level = it }
            PreviewArea(
                vm = vm,
                mode = mode,
                tab = activeTab,
                gravityIndex = selectedGravity,
                lightIndex = selectedLight,
                showHint = level == StudioLevel.Easy && mode == PreviewMode.MoveCenter,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            TabRow(tabs, activeTab, spec.type) { tab = it }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 270.dp)
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(EditorColors.Surface)
                    .border(1.dp, EditorColors.Border, RoundedCornerShape(12.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (activeTab) {
                    StudioTab.Style -> StylePanel(vm, spec, level) { picked -> if (!picked.isBasic) tab = StudioTab.Source }
                    StudioTab.Source -> SourcePanel(
                        vm = vm,
                        spec = spec,
                        level = level,
                        meshIndex = meshIndex,
                        onMeshIndex = { meshIndex = it },
                        gravityIndex = selectedGravity,
                        onGravityIndex = { gravityIndex = it },
                        drawingPath = drawingPath,
                        onDrawingPath = { drawingPath = it },
                        onPickMapImage = {
                            mapPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    )
                    StudioTab.Colors -> ColorsPanel(vm, spec, level, selectedStop) { stopIndex = it }
                    StudioTab.Shape -> ShapePanel(vm, spec, level)
                    StudioTab.Effects -> EffectsPanel(vm, level, selectedLight) { lightIndex = it }
                    StudioTab.Evolve -> EvolvePanel(vm, spec, level)
                    StudioTab.Lab -> LabPanel(vm, spec)
                    StudioTab.Animate -> AnimatePanel(vm, level)
                    StudioTab.Layers -> LayersPanel(vm)
                    StudioTab.History -> HistoryPanel(vm)
                    StudioTab.Project -> ProjectPanel(
                        vm = vm,
                        exportSize = exportSize,
                        onExportSize = { exportSize = it },
                        onSave = { saveLauncher.launch("gradient_project.json") },
                        onOpen = { openLauncher.launch(arrayOf("*/*")) },
                        onExport = { exportLauncher.launch("gradient.png") },
                        animSize = animSize,
                        onAnimSize = { animSize = it },
                        animLoops = animLoops,
                        onAnimLoops = { animLoops = it },
                        onExportGif = { gifLauncher.launch("gradient.gif") },
                        onExportMp4 = { mp4Launcher.launch("gradient.mp4") },
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

// ---------------------------------------------------------------- chrome

@Composable
private fun StudioTopBar(vm: GradientViewModel, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp), colors = studioIconColors()) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), Modifier.size(24.dp))
        }
        Text(
            stringResource(R.string.studio_title),
            color = EditorColors.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        IconButton(onClick = vm::undo, enabled = vm.canUndo, modifier = Modifier.size(48.dp), colors = studioIconColors()) {
            Icon(painterResource(R.drawable.ic_undo), stringResource(R.string.undo), Modifier.size(24.dp))
        }
        IconButton(onClick = vm::redo, enabled = vm.canRedo, modifier = Modifier.size(48.dp), colors = studioIconColors()) {
            Icon(painterResource(R.drawable.ic_redo), stringResource(R.string.redo), Modifier.size(24.dp))
        }
    }
}

@Composable
private fun studioIconColors() = IconButtonDefaults.iconButtonColors(
    contentColor = EditorColors.TextPrimary,
    disabledContentColor = EditorColors.TextSecondary.copy(alpha = 0.55f),
)

@Composable
private fun LevelRow(level: StudioLevel, onSelect: (StudioLevel) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StudioLevel.entries.forEach {
            StudioChip(stringResource(it.label), it == level, { onSelect(it) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TabRow(tabs: List<StudioTab>, active: StudioTab, type: GradientType, onSelect: (StudioTab) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(tabs) { _, t ->
            val label = if (t == StudioTab.Source) sourceTabLabel(type) else t.label
            StudioChip(stringResource(label), t == active, { onSelect(t) })
        }
    }
}

// ---------------------------------------------------------------- preview

@Composable
private fun PreviewArea(
    vm: GradientViewModel,
    mode: PreviewMode,
    tab: StudioTab,
    gravityIndex: Int,
    lightIndex: Int,
    showHint: Boolean,
    modifier: Modifier = Modifier,
) {
    val project = vm.project
    val bitmap = vm.preview
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val description = stringResource(R.string.preview_desc)

    Box(modifier.clip(RoundedCornerShape(12.dp)).background(EditorColors.Workspace), contentAlignment = Alignment.Center) {
        Box(Modifier.padding(10.dp).fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .aspectRatio(project.width.toFloat() / project.height.toFloat())
                    .clip(RoundedCornerShape(6.dp))
                    .checkerboard()
                    .border(1.dp, EditorColors.Border, RoundedCornerShape(6.dp))
                    .pointerInput(mode, gravityIndex, lightIndex) {
                        when (mode) {
                            // One finger moves the gradient's center; two fingers scale and rotate it.
                            PreviewMode.MoveCenter -> detectTransformGestures { _, pan, zoom, rotation ->
                                vm.transformSelected(pan.x / size.width, pan.y / size.height, zoom, rotation)
                            }

                            PreviewMode.MoveGravityPoint -> detectDragGestures { change, drag ->
                                change.consume()
                                vm.moveGravityPoint(gravityIndex, drag.x / size.width, drag.y / size.height)
                            }

                            PreviewMode.MoveLight -> detectDragGestures { change, drag ->
                                change.consume()
                                vm.moveLight(lightIndex, drag.x / size.width, drag.y / size.height)
                            }

                            PreviewMode.DrawPath -> detectDragGestures(
                                onDragStart = { o -> vm.appendPathPoint(o.x / size.width, o.y / size.height, restart = true) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    vm.appendPathPoint(change.position.x / size.width, change.position.y / size.height, restart = false)
                                },
                            )

                            PreviewMode.None -> Unit
                        }
                    },
            ) {
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = description,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                }
                Canvas(Modifier.fillMaxSize()) {
                    val p = vm.project
                    val s = p.selected.spec
                    fun marker(x: Float, y: Float, radius: Float, fill: Color?, selected: Boolean) {
                        val c = Offset(x * size.width, y * size.height)
                        if (fill != null) drawCircle(fill, radius, c)
                        drawCircle(Color.Black.copy(alpha = 0.55f), radius + 1.5.dp.toPx(), c, style = Stroke(3.dp.toPx()))
                        drawCircle(Color.White, radius, c, style = Stroke(if (selected) 3.dp.toPx() else 1.5.dp.toPx()))
                    }
                    when {
                        mode == PreviewMode.MoveCenter -> marker(s.centerX, s.centerY, 9.dp.toPx(), null, true)

                        tab == StudioTab.Source && s.type == GradientType.Gravity ->
                            s.gravity.points.forEachIndexed { i, pt ->
                                marker(pt.x, pt.y, 10.dp.toPx(), Color(pt.color or (0xFF shl 24)), i == gravityIndex)
                            }

                        tab == StudioTab.Source && s.type == GradientType.Path && s.path.points.size >= 2 -> {
                            val path = Path().apply {
                                moveTo(s.path.points[0].x * size.width, s.path.points[0].y * size.height)
                                for (i in 1 until s.path.points.size) {
                                    lineTo(s.path.points[i].x * size.width, s.path.points[i].y * size.height)
                                }
                            }
                            drawPath(path, Color.Black.copy(alpha = 0.5f), style = Stroke(3.5f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                            drawPath(path, Color.White, style = Stroke(1.5f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }

                        tab == StudioTab.Effects ->
                            p.lighting.lights.forEachIndexed { i, l ->
                                marker(l.x, l.y, 9.dp.toPx(), Color(l.color or (0xFF shl 24)), i == lightIndex)
                            }
                    }
                }
            }
        }
        if (showHint) {
            Text(
                stringResource(R.string.preview_hint),
                color = EditorColors.TextPrimary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(EditorColors.Surface.copy(alpha = 0.85f))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/** Light checkerboard so transparency is visible. */
private fun Modifier.checkerboard(): Modifier = drawBehind {
    val cell = 12.dp.toPx()
    drawRect(Color(0xFF2A2D32))
    val cols = (size.width / cell).toInt() + 1
    val rows = (size.height / cell).toInt() + 1
    for (r in 0 until rows) for (c in 0 until cols) {
        if ((r + c) % 2 == 0) drawRect(Color(0xFF3A3D44), Offset(c * cell, r * cell), Size(cell, cell))
    }
}

// ---------------------------------------------------------------- style and colors

@Composable
private fun StylePanel(vm: GradientViewModel, spec: GradientSpec, level: StudioLevel, onPicked: (GradientType) -> Unit) {
    // Beginners get the five classic ramps; the content-driven types appear from the Creative level.
    val types = GradientType.entries.filter { level != StudioLevel.Easy || it.isBasic }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(types) { _, type ->
            StudioChip(stringResource(typeLabel(type)), type == spec.type, {
                vm.setType(type)
                onPicked(type)
            })
        }
    }
    if (spec.usesStops) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(GradientPresets.palettes) { index, colors ->
                val desc = stringResource(R.string.preset_n, index + 1)
                Box(
                    Modifier
                        .size(width = 68.dp, height = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Brush.horizontalGradient(colors.map { Color(it) }))
                        .border(1.dp, EditorColors.Border, RoundedCornerShape(10.dp))
                        .semantics { contentDescription = desc }
                        .clickable(role = Role.Button) { vm.applyPalette(colors) },
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioChip(stringResource(R.string.random_palette), false, {
            when (spec.type) {
                GradientType.Mesh -> vm.randomMesh()
                GradientType.Gravity -> vm.randomGravity()
                else -> vm.randomPalette()
            }
        })
        if (level != StudioLevel.Easy && spec.usesStops) {
            StudioChip(stringResource(R.string.reverse_colors), false, vm::reverseStops)
        }
    }
}

@Composable
private fun ColorsPanel(
    vm: GradientViewModel,
    spec: GradientSpec,
    level: StudioLevel,
    selectedStop: Int,
    onSelectStop: (Int) -> Unit,
) {
    val stops = spec.stops
    GradientBar(
        stops = stops,
        selected = selectedStop,
        canAdd = level != StudioLevel.Easy,
        onSelect = onSelectStop,
        onMove = { i, pos -> vm.setStopPosition(i, pos) },
        onAdd = { pos -> onSelectStop(vm.addStop(pos)) },
    )
    ColorEditor(
        color = stops[selectedStop].color,
        showAlpha = level != StudioLevel.Easy,
        onChange = { vm.setStopColor(selectedStop, it) },
    )
    if (level == StudioLevel.Pro) {
        LabeledSlider(
            label = R.string.stop_position,
            value = stops[selectedStop].position,
            range = 0f..1f,
            valueText = String.format("%.3f", stops[selectedStop].position),
            onChange = { vm.setStopPosition(selectedStop, it) },
        )
    }
    if (level != StudioLevel.Easy) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioChip(stringResource(R.string.stop_add), false, {
                onSelectStop(vm.addStop(widestGapMiddle(stops.map { it.position })))
            })
            StudioChip(stringResource(R.string.stop_remove), false, {
                vm.removeStop(selectedStop)
                onSelectStop(0)
            }, enabled = stops.size > 2)
        }
    }
}

/** Middle of the largest empty stretch between stops (including both ends), a sensible place for a new stop. */
private fun widestGapMiddle(positions: List<Float>): Float {
    val sorted = (listOf(0f) + positions.sorted() + listOf(1f))
    var best = 0.5f
    var bestGap = -1f
    for (i in 0 until sorted.lastIndex) {
        val gap = sorted[i + 1] - sorted[i]
        if (gap > bestGap) {
            bestGap = gap
            best = (sorted[i] + sorted[i + 1]) / 2f
        }
    }
    return best
}

@Composable
private fun ShapePanel(vm: GradientViewModel, spec: GradientSpec, level: StudioLevel) {
    if (spec.type != GradientType.Radial) {
        LabeledSlider(R.string.shape_angle, spec.angle, 0f..360f, "${spec.angle.toInt()}°", vm::setAngle)
    }
    LabeledSlider(R.string.shape_scale, spec.scale, 0.1f..4f, String.format("%.2f", spec.scale), vm::setScale)
    if (spec.type == GradientType.Spiral) {
        LabeledSlider(R.string.shape_turns, spec.turns, 1f..10f, String.format("%.1f", spec.turns), vm::setTurns)
    }
    if (level == StudioLevel.Pro) {
        LabeledSlider(
            R.string.shape_center_x, spec.centerX, 0f..1f, String.format("%.2f", spec.centerX),
            { vm.setCenter(it, spec.centerY) },
        )
        LabeledSlider(
            R.string.shape_center_y, spec.centerY, 0f..1f, String.format("%.2f", spec.centerY),
            { vm.setCenter(spec.centerX, it) },
        )
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(Spread.entries) { _, s ->
            StudioChip(stringResource(spreadLabel(s)), s == spec.spread, { vm.setSpread(s) })
        }
        item { StudioChip(stringResource(R.string.reset_center), false, vm::resetCenter) }
    }
}

// ---------------------------------------------------------------- content sources

@Composable
private fun SourcePanel(
    vm: GradientViewModel,
    spec: GradientSpec,
    level: StudioLevel,
    meshIndex: Int,
    onMeshIndex: (Int) -> Unit,
    gravityIndex: Int,
    onGravityIndex: (Int) -> Unit,
    drawingPath: Boolean,
    onDrawingPath: (Boolean) -> Unit,
    onPickMapImage: () -> Unit,
) {
    when (spec.type) {
        GradientType.Mesh -> MeshPanel(vm, spec, level, meshIndex, onMeshIndex)
        GradientType.Gravity -> GravityPanel(vm, spec, level, gravityIndex, onGravityIndex)
        GradientType.Path -> PathPanel(vm, spec, drawingPath, onDrawingPath)
        GradientType.Map -> MapPanel(vm, spec, onPickMapImage)
        else -> Unit
    }
}

@Composable
private fun MeshPanel(vm: GradientViewModel, spec: GradientSpec, level: StudioLevel, selected: Int, onSelect: (Int) -> Unit) {
    val mesh = spec.mesh
    val index = selected.coerceIn(0, mesh.colors.lastIndex)
    Text(stringResource(R.string.mesh_hint), color = EditorColors.TextSecondary, fontSize = 13.sp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.mesh_cols), color = EditorColors.TextPrimary, fontSize = 14.sp, modifier = Modifier.width(72.dp))
        (2..5).forEach { n -> StudioChip("$n", n == mesh.cols, { vm.setMeshSize(n, mesh.rows) }) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.mesh_rows), color = EditorColors.TextPrimary, fontSize = 14.sp, modifier = Modifier.width(72.dp))
        (2..5).forEach { n -> StudioChip("$n", n == mesh.rows, { vm.setMeshSize(mesh.cols, n) }) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (r in 0 until mesh.rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until mesh.cols) {
                    val i = r * mesh.cols + c
                    val desc = stringResource(R.string.mesh_node_n, i + 1)
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(mesh.colors[i] or (0xFF shl 24)))
                            .border(
                                if (i == index) 3.dp else 1.dp,
                                if (i == index) EditorColors.Accent else EditorColors.Border,
                                RoundedCornerShape(8.dp),
                            )
                            .semantics { contentDescription = desc }
                            .clickable(role = Role.Button) { onSelect(i) },
                    )
                }
            }
        }
    }
    ColorEditor(mesh.colors[index], showAlpha = level == StudioLevel.Pro, onChange = { vm.setMeshColor(index, it) })
}

@Composable
private fun GravityPanel(vm: GradientViewModel, spec: GradientSpec, level: StudioLevel, selected: Int, onSelect: (Int) -> Unit) {
    val points = spec.gravity.points
    val index = selected.coerceIn(0, points.lastIndex)
    Text(stringResource(R.string.gravity_hint), color = EditorColors.TextSecondary, fontSize = 13.sp)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(points) { i, pt ->
            val desc = stringResource(R.string.gravity_point_n, i + 1)
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(pt.color or (0xFF shl 24)))
                    .border(
                        if (i == index) 3.dp else 1.dp,
                        if (i == index) EditorColors.Accent else EditorColors.Border,
                        CircleShape,
                    )
                    .semantics { contentDescription = desc }
                    .clickable(role = Role.Button) { onSelect(i) },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioChip(stringResource(R.string.gravity_add), false, { onSelect(vm.addGravityPoint()) })
        StudioChip(stringResource(R.string.gravity_remove), false, {
            vm.removeGravityPoint(index)
            onSelect(0)
        }, enabled = points.size > 1)
    }
    ColorEditor(points[index].color, showAlpha = level == StudioLevel.Pro, onChange = { vm.setGravityColor(index, it) })
    LabeledSlider(R.string.gravity_mass, points[index].mass, 0.1f..6f, String.format("%.1f", points[index].mass), { vm.setGravityMass(index, it) })
    LabeledSlider(R.string.gravity_power, spec.gravity.power, 0.5f..8f, String.format("%.1f", spec.gravity.power), vm::setGravityPower)
}

@Composable
private fun PathPanel(vm: GradientViewModel, spec: GradientSpec, drawing: Boolean, onDrawing: (Boolean) -> Unit) {
    val path = spec.path
    Text(
        stringResource(if (drawing) R.string.path_hint_drawing else R.string.path_hint),
        color = EditorColors.TextSecondary,
        fontSize = 13.sp,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioChip(stringResource(R.string.path_draw), drawing, { onDrawing(!drawing) })
        StudioChip(stringResource(R.string.path_clear), false, vm::clearPath, enabled = path.points.isNotEmpty())
        StudioChip(stringResource(R.string.path_flow), path.flow, { vm.setPathFlow(!path.flow) })
    }
    LabeledSlider(R.string.path_width, path.width, 0.01f..0.5f, String.format("%.2f", path.width), vm::setPathWidth)
    if (!path.flow) {
        LabeledSlider(R.string.path_softness, path.softness, 0f..1f, "${(path.softness * 100).toInt()}%", vm::setPathSoftness)
    }
}

@Composable
private fun MapPanel(vm: GradientViewModel, spec: GradientSpec, onPick: () -> Unit) {
    val map = spec.map
    val image = map.image
    Text(stringResource(R.string.map_hint), color = EditorColors.TextSecondary, fontSize = 13.sp)
    Text(
        if (image == null) stringResource(R.string.map_no_image) else stringResource(R.string.map_image_info, image.width, image.height),
        color = EditorColors.TextPrimary,
        fontSize = 14.sp,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioChip(stringResource(if (image == null) R.string.map_pick else R.string.map_change), false, onPick, enabled = !vm.busy)
        StudioChip(stringResource(R.string.map_invert), map.invert, { vm.setMapInvert(!map.invert) }, enabled = image != null)
    }
    LabeledSlider(R.string.map_gamma, map.gamma, 0.3f..3f, String.format("%.2f", map.gamma), vm::setMapGamma)
}

// ---------------------------------------------------------------- effects

@Composable
private fun EffectsPanel(vm: GradientViewModel, level: StudioLevel, selectedLight: Int, onSelectLight: (Int) -> Unit) {
    val project = vm.project
    val tex = project.texture
    val lighting = project.lighting
    val pro = level == StudioLevel.Pro

    Text(stringResource(R.string.effects_texture), color = EditorColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    LabeledSlider(R.string.texture_grain, tex.grain, 0f..1f, "${(tex.grain * 100).toInt()}%", { v -> vm.editTexture { it.copy(grain = v) } })
    if (pro) {
        LabeledSlider(R.string.texture_grain_size, tex.grainSize, 1f..8f, String.format("%.1f", tex.grainSize), { v -> vm.editTexture { it.copy(grainSize = v) } })
    }
    LabeledSlider(R.string.texture_noise, tex.noise, 0f..1f, "${(tex.noise * 100).toInt()}%", { v -> vm.editTexture { it.copy(noise = v) } })
    if (pro) {
        LabeledSlider(R.string.texture_noise_scale, tex.noiseScale, 0.05f..0.8f, String.format("%.2f", tex.noiseScale), { v -> vm.editTexture { it.copy(noiseScale = v) } })
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { StudioChip(stringResource(R.string.texture_dither), tex.dither, { vm.editTexture(true) { it.copy(dither = !it.dither) } }) }
        if (pro) {
            item { StudioChip(stringResource(R.string.texture_mono), tex.monochrome, { vm.editTexture(true) { it.copy(monochrome = !it.monochrome) } }) }
            item { StudioChip(stringResource(R.string.texture_reseed), false, { vm.editTexture(true) { it.copy(seed = it.seed + 1) } }) }
        }
    }

    Text(stringResource(R.string.effects_lighting), color = EditorColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.light_hint), color = EditorColors.TextSecondary, fontSize = 13.sp)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(lighting.lights) { i, light ->
            val desc = stringResource(R.string.light_n, i + 1)
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(light.color or (0xFF shl 24)))
                    .border(
                        if (i == selectedLight) 3.dp else 1.dp,
                        if (i == selectedLight) EditorColors.Accent else EditorColors.Border,
                        CircleShape,
                    )
                    .semantics { contentDescription = desc }
                    .clickable(role = Role.Button) { onSelectLight(i) },
            )
        }
        item { StudioChip(stringResource(R.string.light_add), false, { onSelectLight(vm.addLight()) }) }
    }
    if (selectedLight in lighting.lights.indices) {
        val light = lighting.lights[selectedLight]
        LabeledSlider(R.string.light_intensity, light.intensity, 0f..2f, "${(light.intensity * 100).toInt()}%", { v -> vm.editLight(selectedLight) { it.copy(intensity = v) } })
        LabeledSlider(R.string.light_radius, light.radius, 0.05f..1.2f, String.format("%.2f", light.radius), { v -> vm.editLight(selectedLight) { it.copy(radius = v) } })
        if (pro) {
            ColorEditor(light.color, showAlpha = false, onChange = { c -> vm.editLight(selectedLight) { it.copy(color = c or (0xFF shl 24)) } })
        }
        StudioChip(stringResource(R.string.light_remove), false, {
            vm.removeLight(selectedLight)
            onSelectLight(0)
        })
    }
    LabeledSlider(R.string.light_vignette, lighting.vignette, 0f..1f, "${(lighting.vignette * 100).toInt()}%", vm::setVignette)
}

// ---------------------------------------------------------------- layers, history, project

@Composable
private fun LayersPanel(vm: GradientViewModel) {
    val project = vm.project
    StudioChip(stringResource(R.string.layer_add), false, vm::addLayer)
    // The top layer is listed first, like in most editors.
    for (index in project.layers.indices.reversed()) {
        val layer = project.layers[index]
        val isSelected = index == project.selectedIndex
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (isSelected) EditorColors.AccentContainer else EditorColors.SurfaceRaised)
                .border(
                    if (isSelected) 2.dp else 1.dp,
                    if (isSelected) EditorColors.Accent else EditorColors.Border,
                    RoundedCornerShape(10.dp),
                )
                .clickable(role = Role.Button) { vm.selectLayer(index) }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val visibleDescription = stringResource(R.string.layer_visible)
            Switch(
                checked = layer.visible,
                onCheckedChange = { vm.setLayerVisible(index, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = EditorColors.Accent),
                modifier = Modifier.semantics { contentDescription = visibleDescription },
            )
            Text(
                layer.name,
                color = EditorColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            IconButton(onClick = { vm.moveLayer(index, 1) }, enabled = index < project.layers.lastIndex, modifier = Modifier.size(44.dp), colors = studioIconColors()) {
                Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.layer_up))
            }
            IconButton(onClick = { vm.moveLayer(index, -1) }, enabled = index > 0, modifier = Modifier.size(44.dp), colors = studioIconColors()) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.layer_down))
            }
            IconButton(onClick = { vm.deleteLayer(index) }, enabled = project.layers.size > 1, modifier = Modifier.size(44.dp), colors = studioIconColors()) {
                Icon(Icons.Default.Delete, stringResource(R.string.layer_delete))
            }
        }
    }
    StudioChip(stringResource(R.string.layer_duplicate), false, { vm.duplicateLayer(project.selectedIndex) })
    val layer = project.selected
    LabeledSlider(
        R.string.layer_opacity, layer.opacity, 0f..1f, "${(layer.opacity * 100).toInt()}%", vm::setOpacity,
    )
    Text(stringResource(R.string.layer_blend), color = EditorColors.TextPrimary, fontSize = 14.sp)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(BlendMode.entries) { _, mode ->
            StudioChip(stringResource(blendLabel(mode)), mode == layer.blend, { vm.setBlend(mode) })
        }
    }
}

@Composable
private fun HistoryPanel(vm: GradientViewModel) {
    Text(stringResource(R.string.history_hint), color = EditorColors.TextSecondary, fontSize = 13.sp)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(vm.history) { index, entry ->
            val current = index == vm.cursor
            val thumb = remember(entry.thumbnail) { entry.thumbnail?.asImageBitmap() }
            Column(
                Modifier
                    .width(76.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        if (current) 2.dp else 1.dp,
                        if (current) EditorColors.Accent else EditorColors.Border,
                        RoundedCornerShape(10.dp),
                    )
                    .clickable(role = Role.Button) { vm.goTo(index) }
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)).checkerboard()) {
                    if (thumb != null) {
                        Image(bitmap = thumb, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                Text(
                    "${index + 1}. ${stringResource(entry.labelRes)}",
                    color = if (current) EditorColors.TextPrimary else EditorColors.TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun ProjectPanel(
    vm: GradientViewModel,
    exportSize: Int,
    onExportSize: (Int) -> Unit,
    onSave: () -> Unit,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    animSize: Int,
    onAnimSize: (Int) -> Unit,
    animLoops: Int,
    onAnimLoops: (Int) -> Unit,
    onExportGif: () -> Unit,
    onExportMp4: () -> Unit,
) {
    val busy = vm.busy
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioChip(stringResource(R.string.project_new), false, vm::newProject, enabled = !busy)
        StudioChip(stringResource(R.string.project_open), false, onOpen, enabled = !busy)
        StudioChip(stringResource(R.string.project_save), false, onSave, enabled = !busy)
    }
    Text(stringResource(R.string.project_hint), color = EditorColors.TextSecondary, fontSize = 13.sp)
    Text(stringResource(R.string.export_size_label), color = EditorColors.TextPrimary, fontSize = 14.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1080, 2048, 3072).forEach { size ->
            StudioChip("$size", size == exportSize, { onExportSize(size) })
        }
    }
    StudioChip(stringResource(R.string.export_png), false, onExport, enabled = !busy)
    AnimationExportSection(vm, animSize, onAnimSize, animLoops, onAnimLoops, onExportGif, onExportMp4)
    if (busy && vm.exportProgress == null) Text(stringResource(R.string.working), color = EditorColors.TextSecondary, fontSize = 13.sp)
}
