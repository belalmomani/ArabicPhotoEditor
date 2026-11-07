package com.example.arabicphotoeditor.scenecleanup

import android.graphics.Bitmap
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.arabicphotoeditor.R
import com.example.arabicphotoeditor.ui.theme.EditorColors
import kotlin.math.max
import kotlin.math.min

private val SelectionColor = 0x99FF3B30.toInt()

/**
 * Scene Cleanup: the user paints over what should disappear, the engine rebuilds the area. One finger paints (or
 * pans with the hand tool), two fingers zoom and pan. [onApply] hands the cleaned photo back to the editor.
 */
@Composable
fun SceneCleanupScreen(
    onBack: () -> Unit,
    photo: Bitmap?,
    onApply: (Bitmap) -> Unit,
    vm: SceneCleanupViewModel = viewModel(),
) {
    LaunchedEffect(photo) { vm.open(photo) }

    val context = LocalContext.current
    val message = vm.message
    LaunchedEffect(message) {
        if (message != null) {
            Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
            vm.messageShown()
        }
    }

    var showBefore by remember { mutableStateOf(false) }
    val revision = vm.revision // read so the screen follows the timeline
    val shown = if (showBefore && vm.changed) vm.original else vm.photo
    val overlay = remember(revision) { vm.mask?.let { buildOverlay(it) } }

    Surface(color = EditorColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            CleanupTopBar(
                canUndo = vm.canUndo,
                canRedo = vm.canRedo,
                canApply = vm.changed && !vm.processing,
                onUndo = vm::undo,
                onRedo = vm::redo,
                onBack = onBack,
                onApply = { vm.photo?.let(onApply) },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (shown == null) {
                    Text(
                        stringResource(R.string.cleanup_no_photo),
                        color = EditorColors.TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                } else {
                    CleanupCanvas(vm, shown, overlay, showOverlay = !showBefore, modifier = Modifier.fillMaxSize())
                    val hint = when {
                        vm.processing -> null
                        vm.hasSelection -> R.string.cleanup_hint_ready
                        else -> R.string.cleanup_hint_empty
                    }
                    if (hint != null) {
                        Text(
                            stringResource(hint),
                            color = EditorColors.TextPrimary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp, start = 12.dp, end = 12.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(EditorColors.Surface.copy(alpha = 0.88f))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            CleanupControls(vm, onCompare = { showBefore = it })
        }
    }
}

private fun buildOverlay(mask: SelectionMask): Bitmap {
    val pixels = IntArray(mask.width * mask.height) { if (mask.data[it].toInt() != 0) SelectionColor else 0 }
    val bitmap = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
    return bitmap
}

// ---------------------------------------------------------------- top bar

@Composable
private fun CleanupTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    canApply: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onBack: () -> Unit,
    onApply: () -> Unit,
) {
    val colors = IconButtonDefaults.iconButtonColors(
        contentColor = EditorColors.TextPrimary,
        disabledContentColor = EditorColors.TextSecondary.copy(alpha = 0.55f),
    )
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp), colors = colors) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), Modifier.size(24.dp))
        }
        Text(
            stringResource(R.string.cleanup_title),
            color = EditorColors.TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(44.dp), colors = colors) {
            Icon(painterResource(R.drawable.ic_undo), stringResource(R.string.undo), Modifier.size(24.dp))
        }
        IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.size(44.dp), colors = colors) {
            Icon(painterResource(R.drawable.ic_redo), stringResource(R.string.redo), Modifier.size(24.dp))
        }
        IconButton(onClick = onApply, enabled = canApply, modifier = Modifier.size(44.dp), colors = colors) {
            Icon(painterResource(R.drawable.ic_check), stringResource(R.string.cleanup_apply), Modifier.size(24.dp))
        }
    }
}

// ---------------------------------------------------------------- canvas with zoom, pan and painting

@Composable
private fun CleanupCanvas(
    vm: SceneCleanupViewModel,
    bitmap: Bitmap,
    overlay: Bitmap?,
    showOverlay: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.clipToBounds().background(EditorColors.Workspace)) {
        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        val fit = min(cw / bitmap.width, ch / bitmap.height)
        val fw = bitmap.width * fit
        val fh = bitmap.height * fit

        var zoom by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        // The stroke being drawn right now, normalized over the photo; committed to the mask when the finger lifts.
        val live = remember { mutableStateListOf<Offset>() }
        var liveRadius by remember { mutableFloatStateOf(0f) }
        var liveAdd by remember { mutableStateOf(true) }

        // A different photo (another bitmap) starts unzoomed.
        LaunchedEffect(vm.original) {
            zoom = 1f
            offset = Offset.Zero
        }

        fun clamp(o: Offset): Offset =
            if (zoom <= 1.001f) Offset.Zero
            else Offset(o.x.coerceIn(-fw * zoom / 2f, fw * zoom / 2f), o.y.coerceIn(-fh * zoom / 2f, fh * zoom / 2f))

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(vm.tool, fw, fh) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val drawing = vm.tool != CleanupTool.Hand && !vm.processing
                        val add = vm.tool == CleanupTool.Brush
                        val pts = ArrayList<Pair<Float, Float>>()
                        var multi = false

                        // Screen position -> position over the photo (0..1), undoing the current zoom and pan.
                        fun toNorm(p: Offset): Pair<Float, Float> {
                            val u = fw / 2f + (p.x - size.width / 2f - offset.x) / zoom
                            val v = fh / 2f + (p.y - size.height / 2f - offset.y) / zoom
                            return (u / fw) to (v / fh)
                        }
                        // The brush is a fixed size on screen, so its size over the photo shrinks as you zoom in.
                        val radiusFraction = with(density) { vm.brushDp.dp.toPx() } / 2f / (zoom * max(fw, fh))

                        if (drawing) {
                            val n = toNorm(down.position)
                            pts.add(n)
                            live.clear()
                            live.add(Offset(n.first, n.second))
                            liveRadius = radiusFraction
                            liveAdd = add
                        }
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                if (!multi) {
                                    multi = true // a second finger turns the gesture into zoom/pan and drops the stroke
                                    live.clear()
                                    pts.clear()
                                }
                                val z = event.calculateZoom()
                                val pan = event.calculatePan()
                                val c = event.calculateCentroid()
                                val newZoom = (zoom * z).coerceIn(1f, 12f)
                                val rel = c - Offset(size.width / 2f, size.height / 2f)
                                val moved = rel - (rel - offset) * (newZoom / zoom) + pan
                                zoom = newZoom
                                offset = clamp(moved)
                                event.changes.forEach { if (it.pressed) it.consume() }
                            } else if (!multi && pressed == 1) {
                                val change = event.changes.first { it.pressed }
                                if (drawing) {
                                    val n = toNorm(change.position)
                                    pts.add(n)
                                    live.add(Offset(n.first, n.second))
                                } else {
                                    offset = clamp(offset + event.calculatePan())
                                }
                                change.consume()
                            }
                        } while (event.changes.any { it.pressed })

                        if (drawing && !multi && pts.isNotEmpty()) vm.commitStroke(pts, radiusFraction, add)
                        live.clear()
                    }
                },
        ) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(with(density) { fw.toDp() }, with(density) { fh.toDp() })
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = offset.x
                        translationY = offset.y
                    },
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                if (showOverlay && overlay != null) {
                    Image(
                        bitmap = overlay.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                }
                Canvas(Modifier.fillMaxSize()) {
                    if (live.isNotEmpty()) {
                        val color = if (liveAdd) Color(0x99FF3B30) else Color(0x99FFFFFF)
                        val width = liveRadius * 2f * max(size.width, size.height)
                        if (live.size == 1) {
                            drawCircle(color, width / 2f, Offset(live[0].x * size.width, live[0].y * size.height))
                        } else {
                            val path = Path().apply {
                                moveTo(live[0].x * size.width, live[0].y * size.height)
                                for (i in 1 until live.size) lineTo(live[i].x * size.width, live[i].y * size.height)
                            }
                            drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- bottom controls

@Composable
private fun CleanupControls(vm: SceneCleanupViewModel, onCompare: (Boolean) -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(EditorColors.Surface).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (vm.tool != CleanupTool.Hand) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.cleanup_size), color = EditorColors.TextSecondary, fontSize = 13.sp)
                Slider(
                    value = vm.brushDp,
                    onValueChange = vm::setBrushDp,
                    valueRange = 8f..120f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = EditorColors.Accent, activeTrackColor = EditorColors.Accent),
                )
                Text("${vm.brushDp.toInt()}", color = EditorColors.TextPrimary, fontSize = 13.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolButton(R.drawable.ic_brush, R.string.tool_brush, vm.tool == CleanupTool.Brush, true, Modifier.weight(1f)) { vm.selectTool(CleanupTool.Brush) }
            ToolButton(R.drawable.ic_eraser, R.string.tool_eraser, vm.tool == CleanupTool.Eraser, true, Modifier.weight(1f)) { vm.selectTool(CleanupTool.Eraser) }
            ToolButton(R.drawable.ic_move, R.string.tool_hand, vm.tool == CleanupTool.Hand, true, Modifier.weight(1f)) { vm.selectTool(CleanupTool.Hand) }
            ToolButton(R.drawable.ic_tab_history, R.string.tool_clear, false, vm.hasSelection && !vm.processing, Modifier.weight(1f)) { vm.clearSelection() }
            CompareButton(vm.changed, Modifier.weight(1f), onCompare)
        }
        if (vm.processing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LinearProgressIndicator(
                    progress = { vm.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f),
                    color = EditorColors.Accent,
                )
                Text(
                    stringResource(R.string.cleanup_cancel),
                    color = EditorColors.Accent,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable(role = Role.Button) { vm.cancel() }.padding(8.dp),
                )
            }
            Text(stringResource(R.string.cleanup_removing), color = EditorColors.TextSecondary, fontSize = 13.sp)
        } else {
            val enabled = vm.hasSelection
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (enabled) EditorColors.Accent else EditorColors.SurfaceRaised)
                    .clickable(enabled = enabled, role = Role.Button) { vm.remove() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.cleanup_remove),
                    color = if (enabled) Color.White else EditorColors.TextSecondary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(stringResource(R.string.cleanup_basic_note), color = EditorColors.TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ToolButton(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> EditorColors.TextSecondary.copy(alpha = 0.4f)
        selected -> EditorColors.Accent
        else -> EditorColors.TextSecondary
    }
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) EditorColors.AccentContainer else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(24.dp))
        Text(stringResource(label), color = tint, fontSize = 11.sp, maxLines = 1)
    }
}

/** Shows the original while the finger is held down. */
@Composable
private fun CompareButton(enabled: Boolean, modifier: Modifier, onCompare: (Boolean) -> Unit) {
    val tint = if (enabled) EditorColors.TextSecondary else EditorColors.TextSecondary.copy(alpha = 0.4f)
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures(onPress = {
                        onCompare(true)
                        tryAwaitRelease()
                        onCompare(false)
                    })
                }
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(R.drawable.ic_compare), null, tint = tint, modifier = Modifier.size(24.dp))
        Text(stringResource(R.string.tool_compare), color = tint, fontSize = 11.sp, maxLines = 1)
    }
}
