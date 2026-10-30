package com.example.arabicphotoeditor.ui.editor

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.arabicphotoeditor.R
import com.example.arabicphotoeditor.ui.theme.ArabicPhotoEditorTheme
import com.example.arabicphotoeditor.ui.theme.EditorColors

private val ErrorColor = Color(0xFFFF8A80)

/** Tools available in this release. Blemish removal is still design-only. */
enum class EditorTool(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
) {
    RemoveBackground(R.string.tool_remove_background, R.drawable.ic_remove_background),
    RemoveBlemishes(R.string.tool_remove_blemishes, R.drawable.ic_remove_blemishes),
}

/** Immutable snapshot of what the screen shows. */
data class EditorState(
    val image: Bitmap? = null,
    val result: Bitmap? = null,
    val status: BgStatus = BgStatus.Idle,
    @StringRes val errorRes: Int? = null,
    val showOriginal: Boolean = false,
) {
    val processing get() = status == BgStatus.Processing
}

@Composable
fun EditorScreen(vm: EditorViewModel = viewModel()) {
    var selectedTool by rememberSaveable { mutableStateOf<EditorTool?>(null) }
    var panelExpanded by rememberSaveable { mutableStateOf(true) }
    var brushSize by rememberSaveable { mutableFloatStateOf(0.4f) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.loadImage(uri)
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) vm.saveResult(uri)
    }

    val context = LocalContext.current
    val message = vm.message
    LaunchedEffect(message) {
        if (message != null) {
            Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
            vm.messageShown()
        }
    }

    val state = EditorState(vm.original, vm.result, vm.status, vm.errorRes, vm.showOriginal)

    EditorContent(
        onSave = { saver.launch("photo_no_background.png") },
        state = state,
        selectedTool = selectedTool,
        panelExpanded = panelExpanded,
        brushSize = brushSize,
        onPickImage = {
            if (!state.processing) {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        },
        onSelectTool = { tool ->
            val next = if (selectedTool == tool) null else tool
            selectedTool = next
            if (next == EditorTool.RemoveBackground) {
                panelExpanded = true
                vm.removeBackground() // guarded: needs an image, no result yet, nothing running
            }
        },
        onTogglePanel = { panelExpanded = !panelExpanded },
        onToggleCompare = vm::toggleCompare,
        onBrushSizeChange = { brushSize = it },
        onRunRemoveBackground = vm::removeBackground,
        onRevert = vm::revertToOriginal,
    )
}

@Composable
private fun EditorContent(
    state: EditorState,
    selectedTool: EditorTool?,
    panelExpanded: Boolean,
    brushSize: Float,
    onPickImage: () -> Unit,
    onSelectTool: (EditorTool) -> Unit,
    onTogglePanel: () -> Unit,
    onToggleCompare: () -> Unit,
    onBrushSizeChange: (Float) -> Unit,
    onRunRemoveBackground: () -> Unit,
    onRevert: () -> Unit,
    onSave: () -> Unit = {},
) {
    Surface(color = EditorColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            TopBar(saveEnabled = state.result != null && !state.processing, onSave = onSave)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth >= 600.dp && maxWidth > maxHeight
                if (wide) {
                    Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Workspace(state, onPickImage, Modifier.weight(1f).fillMaxHeight())
                        Column(
                            Modifier.width(300.dp).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                ToolPanel(
                                    state, selectedTool, true, null, brushSize,
                                    onBrushSizeChange, onRunRemoveBackground, onRevert,
                                )
                            }
                            ToolRow(selectedTool, state.processing, onSelectTool)
                            CompareButton(state, onToggleCompare)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Workspace(state, onPickImage, Modifier.weight(1f).fillMaxWidth())
                        ToolPanel(
                            state, selectedTool, panelExpanded, onTogglePanel, brushSize,
                            onBrushSizeChange, onRunRemoveBackground, onRevert,
                        )
                        ToolRow(selectedTool, state.processing, onSelectTool)
                        CompareButton(state, onToggleCompare)
                        Spacer(Modifier.size(2.dp))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Top bar

@Composable
private fun TopBar(saveEnabled: Boolean, onSave: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // First item sits on the right in RTL; AutoMirrored back arrow points right.
        TopBarIcon(
            painter = null,
            vector = Icons.AutoMirrored.Filled.ArrowBack,
            description = stringResource(R.string.back),
            enabled = true,
        )
        Text(
            text = stringResource(R.string.app_name),
            color = EditorColors.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        // Undo / redo are display-only for now. Save exports the background-removed result as PNG.
        TopBarIcon(R.drawable.ic_undo, null, stringResource(R.string.undo), enabled = false)
        TopBarIcon(R.drawable.ic_redo, null, stringResource(R.string.redo), enabled = false)
        TopBarIcon(R.drawable.ic_save, null, stringResource(R.string.save), enabled = saveEnabled, onClick = onSave)
    }
}

@Composable
private fun TopBarIcon(
    @DrawableRes painter: Int?,
    vector: androidx.compose.ui.graphics.vector.ImageVector? = null,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit = {},
) {
    val unavailable = stringResource(R.string.not_available_yet)
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .semantics { if (!enabled) stateDescription = unavailable },
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = EditorColors.TextPrimary,
            disabledContentColor = EditorColors.TextSecondary.copy(alpha = 0.55f),
        ),
    ) {
        if (vector != null) {
            Icon(vector, contentDescription = description, modifier = Modifier.size(24.dp))
        } else if (painter != null) {
            Icon(painterResource(painter), contentDescription = description, modifier = Modifier.size(24.dp))
        }
    }
}

// ---------------------------------------------------------------- Workspace

@Composable
private fun Workspace(
    state: EditorState,
    onPickImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(EditorColors.Workspace),
        contentAlignment = Alignment.Center,
    ) {
        val image = state.image
        if (image == null) {
            EmptyWorkspace(onPickImage)
        } else {
            val showResult = state.result != null && !state.showOriginal
            val shown = if (showResult) state.result!! else image
            val imageBitmap = remember(shown) { shown.asImageBitmap() }
            val description = stringResource(
                if (showResult) R.string.showing_result else R.string.showing_original
            )

            // Centered and never cropped: the frame keeps the image's aspect ratio inside the free space.
            Box(Modifier.padding(12.dp).fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .aspectRatio(shown.width.toFloat() / shown.height.toFloat())
                        .clip(RoundedCornerShape(6.dp))
                        .then(if (showResult) Modifier.checkerboard() else Modifier.background(EditorColors.SurfaceRaised))
                        .border(1.dp, EditorColors.Border, RoundedCornerShape(6.dp)),
                ) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = description,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (state.result != null) {
                Text(
                    description,
                    color = EditorColors.TextPrimary,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(EditorColors.Surface.copy(alpha = 0.9f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (!state.processing) {
                TextButton(
                    onClick = onPickImage,
                    modifier = Modifier.align(Alignment.TopStart).heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.change_image), color = EditorColors.Accent, fontSize = 14.sp)
                }
            }
            if (state.processing) {
                Box(
                    Modifier.fillMaxSize().background(EditorColors.Workspace.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = EditorColors.Accent)
                }
            }
        }
    }
}

/** Light checkerboard so real transparency is visible in the result. */
private fun Modifier.checkerboard(): Modifier = drawBehind {
    val cell = 12.dp.toPx()
    drawRect(Color(0xFF2A2D32))
    val cols = (size.width / cell).toInt() + 1
    val rows = (size.height / cell).toInt() + 1
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            if ((r + c) % 2 == 0) {
                drawRect(Color(0xFF3A3D44), Offset(c * cell, r * cell), Size(cell, cell))
            }
        }
    }
}

@Composable
private fun EmptyWorkspace(onPickImage: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(24.dp),
    ) {
        Icon(painterResource(R.drawable.ic_image), null, tint = EditorColors.TextSecondary, modifier = Modifier.size(48.dp))
        Text(stringResource(R.string.workspace_title), color = EditorColors.TextPrimary, fontSize = 20.sp)
        Text(
            stringResource(R.string.workspace_empty_hint),
            color = EditorColors.TextSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(6.dp))
        Button(
            onClick = onPickImage,
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = EditorColors.Accent,
                contentColor = EditorColors.OnAccent,
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(stringResource(R.string.pick_image), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---------------------------------------------------------------- Tools

@Composable
private fun ToolRow(selected: EditorTool?, busy: Boolean, onSelect: (EditorTool) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        EditorTool.entries.forEach { tool ->
            ToolButton(tool, tool == selected, busy, { onSelect(tool) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ToolButton(
    tool: EditorTool,
    isSelected: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(tool.label)
    val selectedText = stringResource(R.string.tool_selected)
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        enabled = !busy,
        shape = shape,
        color = if (isSelected) EditorColors.AccentContainer else EditorColors.SurfaceRaised,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) EditorColors.Accent else EditorColors.Border),
        modifier = modifier
            .heightIn(min = 64.dp)
            .semantics {
                role = Role.Button
                this.selected = isSelected
                if (isSelected) stateDescription = selectedText
            },
    ) {
        Column(
            Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box {
                Icon(
                    painterResource(tool.icon), null,
                    tint = if (isSelected) EditorColors.Accent else EditorColors.TextPrimary,
                    modifier = Modifier.size(24.dp),
                )
                if (isSelected) {
                    // Non-color cue: a check badge next to the icon.
                    Icon(
                        Icons.Default.Check, null,
                        tint = EditorColors.OnAccent,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(12.dp)
                            .background(EditorColors.Accent, CircleShape),
                    )
                }
            }
            Spacer(Modifier.size(4.dp))
            Text(
                label,
                color = EditorColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------- Adaptive panel

@Composable
private fun ToolPanel(
    state: EditorState,
    tool: EditorTool?,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    brushSize: Float,
    onBrushSizeChange: (Float) -> Unit,
    onRunRemoveBackground: () -> Unit,
    onRevert: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(EditorColors.Surface)
            .border(1.dp, EditorColors.Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tool?.let { stringResource(it.label) } ?: stringResource(R.string.panel_hint_no_tool),
                color = if (tool != null) EditorColors.TextPrimary else EditorColors.TextSecondary,
                fontSize = 16.sp,
                fontWeight = if (tool != null) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            if (tool != null && onToggle != null) {
                IconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(if (expanded) R.string.panel_collapse else R.string.panel_expand),
                        tint = EditorColors.TextPrimary,
                    )
                }
            }
        }
        AnimatedVisibility(visible = tool != null && expanded) {
            Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (tool) {
                    EditorTool.RemoveBackground -> RemoveBackgroundPanel(state, onRunRemoveBackground, onRevert)
                    EditorTool.RemoveBlemishes -> RemoveBlemishesPanel(brushSize, onBrushSizeChange)
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun RemoveBackgroundPanel(state: EditorState, onRun: () -> Unit, onRevert: () -> Unit) {
    Text(stringResource(R.string.remove_background_description), color = EditorColors.TextSecondary, fontSize = 14.sp)
    Column(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            state.image == null -> StatusText(R.string.bg_pick_first, EditorColors.TextSecondary)

            state.processing -> {
                StatusText(R.string.bg_processing, EditorColors.TextPrimary)
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = EditorColors.Accent,
                    trackColor = EditorColors.Border,
                )
            }

            state.result != null -> {
                StatusText(R.string.bg_done, EditorColors.TextPrimary)
                OutlinedButton(
                    onClick = onRevert,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, EditorColors.Border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EditorColors.TextPrimary),
                ) { Text(stringResource(R.string.bg_revert), fontSize = 15.sp) }
            }

            state.status == BgStatus.Failed -> {
                StatusText(state.errorRes ?: R.string.bg_failed_generic, ErrorColor)
                RunButton(R.string.bg_retry, onRun)
            }

            else -> RunButton(R.string.bg_run, onRun)
        }
    }
}

@Composable
private fun StatusText(@StringRes text: Int, color: Color) {
    Text(stringResource(text), color = color, fontSize = 14.sp)
}

@Composable
private fun RunButton(@StringRes label: Int, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = EditorColors.Accent,
            contentColor = EditorColors.OnAccent,
        ),
    ) { Text(stringResource(label), fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun RemoveBlemishesPanel(brushSize: Float, onChange: (Float) -> Unit) {
    // Design only: the slider and preview reflect brush size, nothing is painted or removed yet.
    Text(stringResource(R.string.remove_blemishes_description), color = EditorColors.TextSecondary, fontSize = 14.sp)
    Text(stringResource(R.string.brush_size), color = EditorColors.TextPrimary, fontSize = 14.sp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Slider(
            value = brushSize,
            onValueChange = onChange,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            colors = SliderDefaults.colors(
                thumbColor = EditorColors.Accent,
                activeTrackColor = EditorColors.Accent,
                inactiveTrackColor = EditorColors.Border,
            ),
        )
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size((6 + 38 * brushSize).dp)
                    .border(2.dp, EditorColors.TextPrimary, CircleShape)
                    .background(EditorColors.TextPrimary.copy(alpha = 0.15f), CircleShape)
            )
        }
    }
    ReservedArea(R.string.brush_controls_placeholder)
}

@Composable
private fun ReservedArea(@StringRes text: Int) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .border(1.dp, EditorColors.Border, RoundedCornerShape(10.dp))
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(text), color = EditorColors.TextSecondary.copy(alpha = 0.8f), fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------- Compare

/** Enabled only when a real result exists; toggles between the result and the untouched original. */
@Composable
private fun CompareButton(state: EditorState, onClick: () -> Unit) {
    val enabled = state.result != null
    val active = enabled && state.showOriginal
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (active) 2.dp else 1.dp, if (active) EditorColors.Accent else EditorColors.Border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) EditorColors.AccentContainer else Color.Transparent,
            contentColor = EditorColors.TextPrimary,
            disabledContentColor = EditorColors.TextSecondary.copy(alpha = 0.55f),
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(painterResource(R.drawable.ic_compare), null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.compare), fontSize = 16.sp)
    }
}

// ---------------------------------------------------------------- Previews

private fun sampleBitmap(): Bitmap =
    Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF6C8EBF.toInt()) }

private val previewImage = sampleBitmap()

@Preview(name = "1 - no image", showBackground = true, widthDp = 360, heightDp = 740, locale = "ar")
@Composable
private fun PreviewEmpty() = ArabicPhotoEditorTheme {
    EditorContent(EditorState(), null, true, 0.4f, {}, {}, {}, {}, {}, {}, {})
}

@Preview(name = "2 - image", showBackground = true, widthDp = 360, heightDp = 740, locale = "ar")
@Composable
private fun PreviewImage() = ArabicPhotoEditorTheme {
    EditorContent(EditorState(image = previewImage), null, true, 0.4f, {}, {}, {}, {}, {}, {}, {})
}

@Preview(name = "3 - processing", showBackground = true, widthDp = 360, heightDp = 740, locale = "ar")
@Composable
private fun PreviewProcessing() = ArabicPhotoEditorTheme {
    EditorContent(
        EditorState(image = previewImage, status = BgStatus.Processing),
        EditorTool.RemoveBackground, true, 0.4f, {}, {}, {}, {}, {}, {}, {},
    )
}

@Preview(name = "4 - failed", showBackground = true, widthDp = 360, heightDp = 740, locale = "ar")
@Composable
private fun PreviewFailed() = ArabicPhotoEditorTheme {
    EditorContent(
        EditorState(image = previewImage, status = BgStatus.Failed, errorRes = R.string.bg_failed_generic),
        EditorTool.RemoveBackground, true, 0.4f, {}, {}, {}, {}, {}, {}, {},
    )
}

@Preview(name = "5 - blemishes (design only)", showBackground = true, widthDp = 360, heightDp = 740, locale = "ar")
@Composable
private fun PreviewBlemishes() = ArabicPhotoEditorTheme {
    EditorContent(EditorState(image = previewImage), EditorTool.RemoveBlemishes, true, 0.6f, {}, {}, {}, {}, {}, {}, {})
}

@Preview(name = "6 - tablet/landscape", showBackground = true, widthDp = 800, heightDp = 400, locale = "ar")
@Composable
private fun PreviewWide() = ArabicPhotoEditorTheme {
    EditorContent(EditorState(image = previewImage), EditorTool.RemoveBackground, true, 0.4f, {}, {}, {}, {}, {}, {}, {})
}
