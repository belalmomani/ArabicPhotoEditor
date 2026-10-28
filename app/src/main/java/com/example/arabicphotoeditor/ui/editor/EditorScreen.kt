package com.example.arabicphotoeditor.ui.editor

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arabicphotoeditor.R
import com.example.arabicphotoeditor.ui.theme.ArabicPhotoEditorTheme
import com.example.arabicphotoeditor.ui.theme.EditorColors

/** Tools available in this release. Display-only: selecting one only changes which panel is shown. */
enum class EditorTool(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
) {
    RemoveBackground(R.string.tool_remove_background, R.drawable.ic_remove_background),
    RemoveBlemishes(R.string.tool_remove_blemishes, R.drawable.ic_remove_blemishes),
}

@Composable
fun EditorScreen() {
    // View-only UI state. Nothing here processes or loads an image.
    var hasImagePreview by rememberSaveable { mutableStateOf(false) }
    var selectedTool by rememberSaveable { mutableStateOf<EditorTool?>(null) }
    var panelExpanded by rememberSaveable { mutableStateOf(true) }
    var showComparePlaceholder by rememberSaveable { mutableStateOf(false) }
    var brushSize by rememberSaveable { mutableFloatStateOf(0.4f) }

    EditorContent(
        hasImagePreview = hasImagePreview,
        selectedTool = selectedTool,
        panelExpanded = panelExpanded,
        showComparePlaceholder = showComparePlaceholder,
        brushSize = brushSize,
        onPickImage = { hasImagePreview = true },
        onSelectTool = { selectedTool = if (selectedTool == it) null else it },
        onTogglePanel = { panelExpanded = !panelExpanded },
        onToggleCompare = { showComparePlaceholder = !showComparePlaceholder },
        onBrushSizeChange = { brushSize = it },
    )
}

@Composable
private fun EditorContent(
    hasImagePreview: Boolean,
    selectedTool: EditorTool?,
    panelExpanded: Boolean,
    showComparePlaceholder: Boolean,
    brushSize: Float,
    onPickImage: () -> Unit,
    onSelectTool: (EditorTool) -> Unit,
    onTogglePanel: () -> Unit,
    onToggleCompare: () -> Unit,
    onBrushSizeChange: (Float) -> Unit,
) {
    Surface(color = EditorColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            TopBar()
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth >= 600.dp && maxWidth > maxHeight
                if (wide) {
                    Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Workspace(
                            hasImagePreview, showComparePlaceholder, onPickImage,
                            Modifier.weight(1f).fillMaxHeight()
                        )
                        Column(
                            Modifier.width(300.dp).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                ToolPanel(selectedTool, true, null, brushSize, onBrushSizeChange)
                            }
                            ToolRow(selectedTool, onSelectTool)
                            CompareButton(showComparePlaceholder, onToggleCompare)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Workspace(
                            hasImagePreview, showComparePlaceholder, onPickImage,
                            Modifier.weight(1f).fillMaxWidth()
                        )
                        ToolPanel(selectedTool, panelExpanded, onTogglePanel, brushSize, onBrushSizeChange)
                        ToolRow(selectedTool, onSelectTool)
                        CompareButton(showComparePlaceholder, onToggleCompare)
                        Spacer(Modifier.size(2.dp))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Top bar

@Composable
private fun TopBar() {
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
        // Display-only: undo / redo / save are not implemented yet.
        TopBarIcon(R.drawable.ic_undo, null, stringResource(R.string.undo), enabled = false)
        TopBarIcon(R.drawable.ic_redo, null, stringResource(R.string.redo), enabled = false)
        TopBarIcon(R.drawable.ic_save, null, stringResource(R.string.save), enabled = false)
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
    hasImagePreview: Boolean,
    showComparePlaceholder: Boolean,
    onPickImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(EditorColors.Workspace),
        contentAlignment = Alignment.Center,
    ) {
        if (!hasImagePreview) {
            EmptyWorkspace(onPickImage)
        } else {
            // Centered, never cropped: the frame keeps its aspect ratio inside the available space.
            Box(
                Modifier
                    .padding(12.dp)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(EditorColors.SurfaceRaised)
                        .border(1.dp, EditorColors.Border, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(painterResource(R.drawable.ic_image), null, tint = EditorColors.TextSecondary, modifier = Modifier.size(40.dp))
                        Text(
                            stringResource(R.string.image_placeholder_label),
                            color = EditorColors.TextSecondary,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
                if (showComparePlaceholder) {
                    ComparePlaceholder(Modifier.align(Alignment.BottomCenter))
                }
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

@Composable
private fun ComparePlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(EditorColors.Surface.copy(alpha = 0.92f))
            .border(1.dp, EditorColors.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.compare_placeholder), color = EditorColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.compare_placeholder_hint), color = EditorColors.TextSecondary, fontSize = 13.sp)
    }
}

// ---------------------------------------------------------------- Tools

@Composable
private fun ToolRow(selected: EditorTool?, onSelect: (EditorTool) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        EditorTool.entries.forEach { tool ->
            ToolButton(tool, tool == selected, { onSelect(tool) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ToolButton(tool: EditorTool, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(tool.label)
    val selectedText = stringResource(R.string.tool_selected)
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
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
    tool: EditorTool?,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    brushSize: Float,
    onBrushSizeChange: (Float) -> Unit,
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
                    EditorTool.RemoveBackground -> RemoveBackgroundPanel()
                    EditorTool.RemoveBlemishes -> RemoveBlemishesPanel(brushSize, onBrushSizeChange)
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun RemoveBackgroundPanel() {
    Text(stringResource(R.string.remove_background_description), color = EditorColors.TextSecondary, fontSize = 14.sp)
    ReservedArea(R.string.options_placeholder)
}

@Composable
private fun RemoveBlemishesPanel(brushSize: Float, onChange: (Float) -> Unit) {
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
        // Visual preview of the brush size (6dp..44dp inside a fixed 48dp slot).
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

@Composable
private fun CompareButton(active: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (active) 2.dp else 1.dp, if (active) EditorColors.Accent else EditorColors.Border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) EditorColors.AccentContainer else Color.Transparent,
            contentColor = EditorColors.TextPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(painterResource(R.drawable.ic_compare), null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.compare), fontSize = 16.sp)
    }
}

// ---------------------------------------------------------------- Previews

@Preview(name = "1 - no image", showBackground = true, widthDp = 360, heightDp = 740, locale = "ar")
@Composable
private fun PreviewEmpty() = ArabicPhotoEditorTheme {
    EditorContent(false, null, true, false, 0.4f, {}, {}, {}, {}, {})
}

@Preview(name = "2 - image", showBackground = true, widthDp = 360, heightDp = 740, locale = "ar")
@Composable
private fun PreviewImage() = ArabicPhotoEditorTheme {
    EditorContent(true, null, true, false, 0.4f, {}, {}, {}, {}, {})
}

@Preview(name = "3 - blemishes tool", showBackground = true, widthDp = 360, heightDp = 740, locale = "ar")
@Composable
private fun PreviewBlemishes() = ArabicPhotoEditorTheme {
    EditorContent(true, EditorTool.RemoveBlemishes, true, true, 0.6f, {}, {}, {}, {}, {})
}

@Preview(name = "4 - remove background", showBackground = true, widthDp = 360, heightDp = 740, locale = "ar")
@Composable
private fun PreviewRemoveBackground() = ArabicPhotoEditorTheme {
    EditorContent(true, EditorTool.RemoveBackground, true, false, 0.4f, {}, {}, {}, {}, {})
}

@Preview(name = "5 - tablet/landscape", showBackground = true, widthDp = 800, heightDp = 400, locale = "ar")
@Composable
private fun PreviewWide() = ArabicPhotoEditorTheme {
    EditorContent(true, EditorTool.RemoveBlemishes, true, false, 0.4f, {}, {}, {}, {}, {})
}
