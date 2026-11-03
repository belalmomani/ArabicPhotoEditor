package com.example.arabicphotoeditor.ui.gradient

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arabicphotoeditor.R
import com.example.arabicphotoeditor.gradient.ColorMath
import com.example.arabicphotoeditor.gradient.ColorStop
import com.example.arabicphotoeditor.gradient.GradientRenderer
import com.example.arabicphotoeditor.ui.theme.EditorColors
import kotlin.math.abs

/** Small selectable pill used for types, tabs, levels, blend modes. Selection is shown by weight and border, not color alone. */
@Composable
fun StudioChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) EditorColors.AccentContainer else EditorColors.SurfaceRaised,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) EditorColors.Accent else EditorColors.Border,
        ),
        modifier = modifier
            .heightIn(min = 44.dp)
            .semantics {
                role = Role.Button
                this.selected = selected
            },
    ) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (enabled) EditorColors.TextPrimary else EditorColors.TextSecondary.copy(alpha = 0.6f),
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun LabeledSlider(
    @StringRes label: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), color = EditorColors.TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = EditorColors.TextSecondary, fontSize = 13.sp)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.heightIn(min = 40.dp),
            colors = SliderDefaults.colors(
                thumbColor = EditorColors.Accent,
                activeTrackColor = EditorColors.Accent,
                inactiveTrackColor = EditorColors.Border,
            ),
        )
    }
}

/**
 * The color ramp with draggable stop handles. Tap a handle to select it; drag to move it; tap an empty spot to add a
 * stop (when [canAdd]). Touch positions are physical, so position 0 is always on the left, like the rendered ramp.
 */
@Composable
fun GradientBar(
    stops: List<ColorStop>,
    selected: Int,
    canAdd: Boolean,
    onSelect: (Int) -> Unit,
    onMove: (Int, Float) -> Unit,
    onAdd: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lut = remember(stops) { GradientRenderer.buildLut(stops) }
    val latestStops by rememberUpdatedState(stops)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestOnMove by rememberUpdatedState(onMove)
    val latestOnAdd by rememberUpdatedState(onAdd)
    val latestCanAdd by rememberUpdatedState(canAdd)
    val handleRadiusDp = 14.dp
    val barHeightDp = 30.dp

    fun trackStart(density: Float, width: Float) = handleRadiusDp.value * density
    fun trackWidth(density: Float, width: Float) = (width - 2f * handleRadiusDp.value * density).coerceAtLeast(1f)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(70.dp)
            .pointerInput(Unit) {
                var dragging = -1
                fun nearest(x: Float): Int {
                    val start = trackStart(density, size.width.toFloat())
                    val tw = trackWidth(density, size.width.toFloat())
                    var best = -1
                    var bestDist = 28.dp.toPx()
                    latestStops.forEachIndexed { i, s ->
                        val d = abs(start + s.position * tw - x)
                        if (d <= bestDist) {
                            bestDist = d
                            best = i
                        }
                    }
                    return best
                }
                detectDragGestures(
                    onDragStart = { o ->
                        dragging = nearest(o.x)
                        if (dragging >= 0) latestOnSelect(dragging)
                    },
                    onDrag = { change, _ ->
                        if (dragging >= 0) {
                            change.consume()
                            val start = trackStart(density, size.width.toFloat())
                            val tw = trackWidth(density, size.width.toFloat())
                            latestOnMove(dragging, ((change.position.x - start) / tw).coerceIn(0f, 1f))
                        }
                    },
                    onDragEnd = { dragging = -1 },
                    onDragCancel = { dragging = -1 },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val start = trackStart(density, size.width.toFloat())
                    val tw = trackWidth(density, size.width.toFloat())
                    var best = -1
                    var bestDist = 28.dp.toPx()
                    latestStops.forEachIndexed { i, s ->
                        val d = abs(start + s.position * tw - o.x)
                        if (d <= bestDist) {
                            bestDist = d
                            best = i
                        }
                    }
                    if (best >= 0) latestOnSelect(best)
                    else if (latestCanAdd) latestOnAdd(((o.x - start) / tw).coerceIn(0f, 1f))
                }
            },
    ) {
        val start = trackStart(density, size.width)
        val tw = trackWidth(density, size.width)
        val barH = barHeightDp.toPx()
        val last = GradientRenderer.LUT_SIZE - 1

        // checker-ish backdrop so transparent stops are visible
        drawRect(Color(0xFF3A3D44), Offset(start, 0f), Size(tw, barH))
        var x = 0f
        val step = 3f
        while (x < tw) {
            val t = (x / tw).coerceIn(0f, 1f)
            drawRect(Color(lut[(t * last).toInt()]), Offset(start + x, 0f), Size(minOf(step, tw - x) + 0.5f, barH))
            x += step
        }
        drawRect(EditorColors.Border, Offset(start, 0f), Size(tw, barH), style = Stroke(width = 1.dp.toPx()))

        val r = handleRadiusDp.toPx()
        stops.forEachIndexed { i, s ->
            val cx = start + s.position * tw
            val cy = barH + r + 6.dp.toPx()
            val isSelected = i == selected
            drawCircle(Color(s.color or (0xFF shl 24)), radius = r - 2.dp.toPx(), center = Offset(cx, cy))
            drawCircle(
                if (isSelected) EditorColors.Accent else EditorColors.TextSecondary,
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = if (isSelected) 3.dp.toPx() else 1.5.dp.toPx()),
            )
            if (isSelected) {
                drawLine(EditorColors.Accent, Offset(cx, barH), Offset(cx, cy - r), strokeWidth = 2.dp.toPx())
            }
        }
    }
}

/** HSV + alpha sliders and a hex field. Hue is remembered so it does not jump to red when saturation hits zero. */
@Composable
fun ColorEditor(
    color: Int,
    showAlpha: Boolean,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hsv = ColorMath.argbToHsv(color)
    var savedHue by remember { mutableFloatStateOf(hsv[0]) }
    val hue = if (hsv[1] > 0.02f && hsv[2] > 0.02f) hsv[0] else savedHue
    val alpha = color ushr 24

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        LabeledSlider(R.string.color_hue, hue, 0f..360f, "${hue.toInt()}°", {
            savedHue = it
            onChange(ColorMath.hsvToArgb(it, hsv[1], hsv[2], alpha))
        })
        LabeledSlider(R.string.color_saturation, hsv[1], 0f..1f, "${(hsv[1] * 100).toInt()}%", {
            onChange(ColorMath.hsvToArgb(hue, it, hsv[2], alpha))
        })
        LabeledSlider(R.string.color_value, hsv[2], 0f..1f, "${(hsv[2] * 100).toInt()}%", {
            onChange(ColorMath.hsvToArgb(hue, hsv[1], it, alpha))
        })
        if (showAlpha) {
            LabeledSlider(R.string.color_alpha, alpha / 255f, 0f..1f, "${(alpha / 255f * 100).toInt()}%", {
                onChange((color and 0x00FFFFFF) or ((it * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24))
            })
        }
        HexField(color, onChange)
    }
}

@Composable
private fun HexField(color: Int, onChange: (Int) -> Unit) {
    var text by remember(color) { mutableStateOf(String.format("%06X", color and 0x00FFFFFF)) }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.color_hex), color = EditorColors.TextPrimary, fontSize = 14.sp)
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .border(1.dp, EditorColors.Border, RoundedCornerShape(10.dp))
                .background(EditorColors.SurfaceRaised, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { input ->
                    val cleaned = input.removePrefix("#").uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                    text = cleaned
                    if (cleaned.length == 6) {
                        onChange((color and (0xFF shl 24)) or cleaned.toInt(16))
                    }
                },
                singleLine = true,
                textStyle = TextStyle(
                    color = EditorColors.TextPrimary,
                    fontSize = 15.sp,
                    textDirection = TextDirection.Ltr,
                ),
                cursorBrush = SolidColor(EditorColors.Accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
        }
        Box(
            Modifier
                .size(44.dp)
                .border(1.dp, EditorColors.Border, RoundedCornerShape(10.dp))
                .background(Color(color or (0xFF shl 24)), RoundedCornerShape(10.dp))
        )
    }
}
