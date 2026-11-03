package com.example.arabicphotoeditor.ui.gradient

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.arabicphotoeditor.R
import com.example.arabicphotoeditor.gradient.AnimationExport
import com.example.arabicphotoeditor.gradient.AnimationSpec
import com.example.arabicphotoeditor.gradient.Animator
import com.example.arabicphotoeditor.gradient.BlendMode
import com.example.arabicphotoeditor.gradient.ColorLab
import com.example.arabicphotoeditor.gradient.Dna
import com.example.arabicphotoeditor.gradient.ExportFailed
import com.example.arabicphotoeditor.gradient.ColorStop
import com.example.arabicphotoeditor.data.ImageLoader
import com.example.arabicphotoeditor.gradient.GradientLayer
import com.example.arabicphotoeditor.gradient.GravityPoint
import com.example.arabicphotoeditor.gradient.Light
import com.example.arabicphotoeditor.gradient.MapImage
import com.example.arabicphotoeditor.gradient.PathPoint
import com.example.arabicphotoeditor.gradient.TextureSpec
import com.example.arabicphotoeditor.gradient.GradientPresets
import com.example.arabicphotoeditor.gradient.GradientProject
import com.example.arabicphotoeditor.gradient.GradientRenderer
import com.example.arabicphotoeditor.gradient.GradientSpec
import com.example.arabicphotoeditor.gradient.GradientType
import com.example.arabicphotoeditor.gradient.ProjectIO
import com.example.arabicphotoeditor.gradient.Spread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.BufferedOutputStream
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** One saved stage of the work (Time Machine). The thumbnail is rendered shortly after the entry is created. */
class HistoryEntry(var project: GradientProject, @StringRes val labelRes: Int) {
    var thumbnail by mutableStateOf<Bitmap?>(null)
}

class GradientViewModel(app: Application) : AndroidViewModel(app) {

    var project by mutableStateOf(GradientProject.new(app.getString(R.string.layer_default_name, 1)))
        private set

    /** Low-resolution render for instant feedback while editing. */
    var preview by mutableStateOf<Bitmap?>(null)
        private set

    val history = mutableStateListOf<HistoryEntry>()
    var cursor by mutableIntStateOf(0)
        private set

    var busy by mutableStateOf(false)
        private set

    /** Message to show once (R.string.*). */
    var message by mutableStateOf<Int?>(null)
        private set

    /** Progress (0..1) of a running animation export, null when none is running. */
    var exportProgress by mutableStateOf<Float?>(null)
        private set

    var playing by mutableStateOf(false)
        private set

    /** Candidates of the Evolution tool (palettes as stops), kept outside the project. */
    val generation = mutableStateListOf<List<ColorStop>>()

    @Volatile
    private var exportCancelled = false
    private var exportJob: Job? = null
    private var playJob: Job? = null
    private var commitJob: Job? = null
    @StringRes
    private var pendingLabel: Int = R.string.history_edit
    private var rendering = false
    private var dirty = false
    private val random = Random(System.nanoTime())

    val canUndo get() = cursor > 0
    val canRedo get() = cursor < history.lastIndex

    init {
        history.add(HistoryEntry(project, R.string.history_start))
        requestThumbnail(history[0])
        renderPreview()
    }

    // ------------------------------------------------------------ rendering

    private fun renderPreview() {
        if (playing) return // the playback loop renders the latest project itself
        // "Latest wins" without starving: never run two renders at once, re-run if something changed meanwhile.
        if (rendering) {
            dirty = true
            return
        }
        rendering = true
        viewModelScope.launch {
            try {
                do {
                    dirty = false
                    val snapshot = project
                    preview = withContext(Dispatchers.Default) { renderBitmap(snapshot, PREVIEW_SIDE) }
                } while (dirty)
            } finally {
                rendering = false
            }
        }
    }

    private fun requestThumbnail(entry: HistoryEntry) {
        viewModelScope.launch {
            entry.thumbnail = withContext(Dispatchers.Default) { renderBitmap(entry.project, THUMB_SIDE) }
        }
    }

    // ------------------------------------------------------------ editing

    /**
     * Applies [transform] to the project and re-renders. With a [commitLabel], the change is saved to the history
     * once editing pauses (so a drag or slider move becomes a single step).
     */
    fun edit(@StringRes commitLabel: Int? = null, transform: (GradientProject) -> GradientProject) {
        val next = transform(project)
        if (next == project) return
        project = next
        renderPreview()
        if (commitLabel != null) {
            pendingLabel = commitLabel
            commitJob?.cancel()
            commitJob = viewModelScope.launch {
                delay(COMMIT_DELAY_MS)
                commit(commitLabel)
            }
        }
    }

    /** Applies [transform] and saves it to the history immediately (for taps and buttons). */
    fun editNow(@StringRes label: Int, transform: (GradientProject) -> GradientProject) {
        edit(null, transform)
        commit(label)
    }

    private fun commit(@StringRes label: Int) {
        commitJob?.cancel()
        commitJob = null
        if (project == history[cursor].project) return
        while (history.lastIndex > cursor) history.removeAt(history.lastIndex) // drop redo branch
        val entry = HistoryEntry(project, label)
        history.add(entry)
        if (history.size > MAX_HISTORY) history.removeAt(0)
        cursor = history.lastIndex
        requestThumbnail(entry)
    }

    private fun flushPending() {
        if (commitJob?.isActive == true) commit(pendingLabel)
    }

    fun undo() {
        flushPending()
        if (cursor > 0) goTo(cursor - 1)
    }

    fun redo() {
        flushPending()
        if (cursor < history.lastIndex) goTo(cursor + 1)
    }

    /** Time Machine: jump to any saved stage. Editing from there discards the stages after it. */
    fun goTo(index: Int) {
        flushPending()
        if (index !in history.indices) return
        cursor = index
        project = history[index].project
        renderPreview()
    }

    // ------------------------------------------------------------ selected-layer helpers

    fun setType(type: GradientType) = editNow(R.string.history_type) { p ->
        p.updateSpec { s ->
            s.copy(
                type = type,
                // Spirals need a repeating ramp to show their arms.
                spread = if (type == GradientType.Spiral && s.spread == Spread.Clamp) Spread.Repeat else s.spread,
            )
        }
    }

    fun applyPalette(colors: IntArray) = editNow(R.string.history_palette) { p ->
        p.updateSpec { it.copy(stops = GradientPresets.stopsOf(colors)) }
    }

    fun randomPalette() = editNow(R.string.history_random) { p ->
        p.updateSpec { it.copy(stops = GradientPresets.random(random)) }
    }

    fun setStopColor(index: Int, color: Int) = edit(R.string.history_color) { p ->
        p.updateSpec { s ->
            if (index !in s.stops.indices) s
            else s.copy(stops = s.stops.mapIndexed { i, st -> if (i == index) st.copy(color = color) else st })
        }
    }

    fun setStopPosition(index: Int, position: Float) = edit(R.string.history_stop_move) { p ->
        p.updateSpec { s ->
            if (index !in s.stops.indices) s
            else s.copy(
                stops = s.stops.mapIndexed { i, st ->
                    if (i == index) st.copy(position = position.coerceIn(0f, 1f)) else st
                },
            )
        }
    }

    /** Adds a stop at [position] with the color the ramp already has there; returns the new stop's index. */
    fun addStop(position: Float): Int {
        val pos = position.coerceIn(0f, 1f)
        var newIndex = project.selected.spec.stops.size
        editNow(R.string.history_stop_add) { p ->
            p.updateSpec { s ->
                val lut = GradientRenderer.buildLut(s.stops)
                val color = lut[(pos * (GradientRenderer.LUT_SIZE - 1)).toInt().coerceIn(0, GradientRenderer.LUT_SIZE - 1)]
                newIndex = s.stops.size
                s.copy(stops = s.stops + ColorStop(pos, color))
            }
        }
        return newIndex
    }

    fun removeStop(index: Int) = editNow(R.string.history_stop_remove) { p ->
        p.updateSpec { s ->
            if (s.stops.size <= 2 || index !in s.stops.indices) s
            else s.copy(stops = s.stops.filterIndexed { i, _ -> i != index })
        }
    }

    fun reverseStops() = editNow(R.string.history_reverse) { p ->
        p.updateSpec { s -> s.copy(stops = s.stops.map { it.copy(position = 1f - it.position) }) }
    }

    fun setAngle(angle: Float) = edit(R.string.history_shape) { p -> p.updateSpec { it.copy(angle = angle) } }
    fun setScale(scale: Float) = edit(R.string.history_shape) { p -> p.updateSpec { it.copy(scale = scale) } }
    fun setTurns(turns: Float) = edit(R.string.history_shape) { p -> p.updateSpec { it.copy(turns = turns) } }
    fun setSpread(spread: Spread) = editNow(R.string.history_shape) { p -> p.updateSpec { it.copy(spread = spread) } }

    fun setCenter(x: Float, y: Float) = edit(R.string.history_shape) { p ->
        p.updateSpec { it.copy(centerX = x.coerceIn(0f, 1f), centerY = y.coerceIn(0f, 1f)) }
    }

    fun resetCenter() = editNow(R.string.history_shape) { p ->
        p.updateSpec { it.copy(centerX = 0.5f, centerY = 0.5f, scale = 1f) }
    }

    /** Touch gestures on the preview: one finger moves the center, two fingers scale and rotate. */
    fun transformSelected(dx: Float, dy: Float, zoom: Float, rotationDegrees: Float) =
        edit(R.string.history_gesture) { p ->
            p.updateSpec { s ->
                s.copy(
                    centerX = (s.centerX + dx).coerceIn(-0.5f, 1.5f),
                    centerY = (s.centerY + dy).coerceIn(-0.5f, 1.5f),
                    scale = (s.scale * zoom).coerceIn(0.05f, 10f),
                    angle = ((s.angle + rotationDegrees) % 360f + 360f) % 360f,
                )
            }
        }

    // ------------------------------------------------------------ layers

    /** Changing the selected layer is not an editing step: it updates the current history entry in place. */
    fun selectLayer(index: Int) {
        flushPending() // so pending edits keep their own history step
        val selected = index.coerceIn(0, project.layers.lastIndex)
        if (selected == project.selectedIndex) return
        project = project.copy(selectedIndex = selected)
        history[cursor].project = project
    }

    fun addLayer() = editNow(R.string.history_layer_add) { p ->
        if (p.layers.size >= MAX_LAYERS) p
        else {
            val spec = GradientSpec(
                type = GradientType.Radial,
                stops = GradientPresets.random(random),
                spread = Spread.Clamp,
            )
            val layer = GradientLayer(
                id = p.nextLayerId(),
                name = getApplication<Application>().getString(R.string.layer_default_name, p.layers.size + 1),
                spec = spec,
                opacity = 0.8f,
                blend = BlendMode.Normal,
            )
            p.copy(layers = p.layers + layer, selectedIndex = p.layers.size)
        }
    }

    fun duplicateLayer(index: Int) = editNow(R.string.history_layer_duplicate) { p ->
        if (index !in p.layers.indices || p.layers.size >= MAX_LAYERS) p
        else {
            val copy = p.layers[index].copy(id = p.nextLayerId())
            val list = p.layers.toMutableList().apply { add(index + 1, copy) }
            p.copy(layers = list, selectedIndex = index + 1)
        }
    }

    fun deleteLayer(index: Int) = editNow(R.string.history_layer_delete) { p ->
        if (p.layers.size <= 1 || index !in p.layers.indices) p
        else {
            val list = p.layers.filterIndexed { i, _ -> i != index }
            val selected = if (index < p.selectedIndex) p.selectedIndex - 1 else p.selectedIndex
            p.copy(layers = list, selectedIndex = selected.coerceIn(0, list.lastIndex))
        }
    }

    /** Moves a layer by [delta] positions in the stack (+1 = up, toward the top layer). */
    fun moveLayer(index: Int, delta: Int) = editNow(R.string.history_layer_move) { p ->
        val target = index + delta
        if (index !in p.layers.indices || target !in p.layers.indices) p
        else {
            val list = p.layers.toMutableList()
            val moved = list.removeAt(index)
            list.add(target, moved)
            p.copy(layers = list, selectedIndex = target)
        }
    }

    fun setLayerVisible(index: Int, visible: Boolean) = editNow(R.string.history_layer_visibility) { p ->
        p.copy(layers = p.layers.mapIndexed { i, l -> if (i == index) l.copy(visible = visible) else l })
    }

    fun setOpacity(opacity: Float) = edit(R.string.history_layer_opacity) { p ->
        p.updateSelected { it.copy(opacity = opacity.coerceIn(0f, 1f)) }
    }

    fun setBlend(mode: BlendMode) = editNow(R.string.history_layer_blend) { p ->
        p.updateSelected { it.copy(blend = mode) }
    }

    // ------------------------------------------------------------ mesh

    fun setMeshSize(cols: Int, rows: Int) = editNow(R.string.history_mesh) { p ->
        p.updateSpec { it.copy(mesh = it.mesh.resized(cols, rows)) }
    }

    fun setMeshColor(index: Int, color: Int) = edit(R.string.history_mesh) { p ->
        p.updateSpec { s ->
            if (index !in s.mesh.colors.indices) s
            else s.copy(mesh = s.mesh.copy(colors = s.mesh.colors.mapIndexed { i, c -> if (i == index) color else c }))
        }
    }

    fun randomMesh() = editNow(R.string.history_random) { p ->
        p.updateSpec { s -> s.copy(mesh = s.mesh.copy(colors = GradientPresets.randomColors(random, s.mesh.colors.size).toList())) }
    }

    // ------------------------------------------------------------ gravity

    fun addGravityPoint(): Int {
        var newIndex = 0
        editNow(R.string.history_gravity) { p ->
            p.updateSpec { s ->
                val points = s.gravity.points
                if (points.size >= MAX_GRAVITY_POINTS) {
                    newIndex = points.lastIndex
                    s
                } else {
                    newIndex = points.size
                    val color = GradientPresets.randomColors(random, 1)[0]
                    s.copy(gravity = s.gravity.copy(points = points + GravityPoint(0.2f + random.nextFloat() * 0.6f, 0.2f + random.nextFloat() * 0.6f, color)))
                }
            }
        }
        return newIndex
    }

    fun randomGravity() = editNow(R.string.history_random) { p ->
        p.updateSpec { s ->
            val colors = GradientPresets.randomColors(random, s.gravity.points.size)
            s.copy(gravity = s.gravity.copy(points = s.gravity.points.mapIndexed { i, pt -> pt.copy(color = colors[i]) }))
        }
    }

    fun removeGravityPoint(index: Int) = editNow(R.string.history_gravity) { p ->
        p.updateSpec { s ->
            if (s.gravity.points.size <= 1 || index !in s.gravity.points.indices) s
            else s.copy(gravity = s.gravity.copy(points = s.gravity.points.filterIndexed { i, _ -> i != index }))
        }
    }

    private fun updateGravityPoint(index: Int, transform: (GravityPoint) -> GravityPoint): (GradientProject) -> GradientProject = { p ->
        p.updateSpec { s ->
            if (index !in s.gravity.points.indices) s
            else s.copy(gravity = s.gravity.copy(points = s.gravity.points.mapIndexed { i, pt -> if (i == index) transform(pt) else pt }))
        }
    }

    fun setGravityColor(index: Int, color: Int) = edit(R.string.history_gravity, updateGravityPoint(index) { it.copy(color = color) })

    fun setGravityMass(index: Int, mass: Float) =
        edit(R.string.history_gravity, updateGravityPoint(index) { it.copy(mass = mass.coerceIn(0.1f, 6f)) })

    fun moveGravityPoint(index: Int, dx: Float, dy: Float) = edit(
        R.string.history_gravity,
        updateGravityPoint(index) { it.copy(x = (it.x + dx).coerceIn(0f, 1f), y = (it.y + dy).coerceIn(0f, 1f)) },
    )

    fun setGravityPower(power: Float) = edit(R.string.history_gravity) { p ->
        p.updateSpec { it.copy(gravity = it.gravity.copy(power = power.coerceIn(0.5f, 8f))) }
    }

    // ------------------------------------------------------------ path

    /** Called while the user draws. [restart] begins a new path; otherwise the point is added if it is far enough. */
    fun appendPathPoint(x: Float, y: Float, restart: Boolean) = edit(R.string.history_path) { p ->
        p.updateSpec { s ->
            val point = PathPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
            val points = s.path.points
            val next = when {
                restart -> listOf(point)
                points.size >= MAX_PATH_POINTS -> points
                points.isNotEmpty() && Math.hypot((points.last().x - point.x).toDouble(), (points.last().y - point.y).toDouble()) < MIN_PATH_STEP -> points
                else -> points + point
            }
            if (next === points) s else s.copy(path = s.path.copy(points = next))
        }
    }

    fun clearPath() = editNow(R.string.history_path) { p -> p.updateSpec { it.copy(path = it.path.copy(points = emptyList())) } }

    fun setPathWidth(width: Float) = edit(R.string.history_path) { p ->
        p.updateSpec { it.copy(path = it.path.copy(width = width.coerceIn(0.01f, 0.5f))) }
    }

    fun setPathSoftness(softness: Float) = edit(R.string.history_path) { p ->
        p.updateSpec { it.copy(path = it.path.copy(softness = softness.coerceIn(0f, 1f))) }
    }

    fun setPathFlow(flow: Boolean) = editNow(R.string.history_path) { p ->
        p.updateSpec { it.copy(path = it.path.copy(flow = flow)) }
    }

    // ------------------------------------------------------------ gradient map

    /** Loads a photo, keeps only its luminance (at most 1024 px on the long side) and uses it for the map layer. */
    fun loadMapImage(uri: Uri) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val image = withContext(Dispatchers.Default) {
                    val bitmap = ImageLoader.load(getApplication(), uri, MAP_IMAGE_SIDE)
                    try {
                        val w = bitmap.width
                        val h = bitmap.height
                        val pixels = IntArray(w * h)
                        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                        val luma = ByteArray(w * h) { i ->
                            val c = pixels[i]
                            (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF) + 0.5f)
                                .toInt().coerceIn(0, 255).toByte()
                        }
                        MapImage(w, h, luma)
                    } finally {
                        bitmap.recycle()
                    }
                }
                editNow(R.string.history_map) { p ->
                    p.updateSpec { it.copy(type = GradientType.Map, map = it.map.copy(image = image)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                message = if (e is OutOfMemoryError) R.string.export_memory else R.string.map_load_failed
            }
            busy = false
        }
    }

    fun setMapInvert(invert: Boolean) = editNow(R.string.history_map) { p ->
        p.updateSpec { it.copy(map = it.map.copy(invert = invert)) }
    }

    fun setMapGamma(gamma: Float) = edit(R.string.history_map) { p ->
        p.updateSpec { it.copy(map = it.map.copy(gamma = gamma.coerceIn(0.2f, 5f))) }
    }

    // ------------------------------------------------------------ texture and lighting

    fun editTexture(commitNow: Boolean = false, transform: (TextureSpec) -> TextureSpec) {
        val change: (GradientProject) -> GradientProject = { p -> p.copy(texture = transform(p.texture)) }
        if (commitNow) editNow(R.string.history_texture, change) else edit(R.string.history_texture, change)
    }

    fun addLight(): Int {
        var newIndex = 0
        editNow(R.string.history_light) { p ->
            val lights = p.lighting.lights
            if (lights.size >= MAX_LIGHTS) {
                newIndex = lights.lastIndex
                p
            } else {
                newIndex = lights.size
                val light = Light(x = 0.3f + 0.4f * random.nextFloat(), y = 0.2f + 0.3f * random.nextFloat())
                p.copy(lighting = p.lighting.copy(lights = lights + light))
            }
        }
        return newIndex
    }

    fun removeLight(index: Int) = editNow(R.string.history_light) { p ->
        if (index !in p.lighting.lights.indices) p
        else p.copy(lighting = p.lighting.copy(lights = p.lighting.lights.filterIndexed { i, _ -> i != index }))
    }

    fun editLight(index: Int, transform: (Light) -> Light) = edit(R.string.history_light) { p ->
        if (index !in p.lighting.lights.indices) p
        else p.copy(
            lighting = p.lighting.copy(
                lights = p.lighting.lights.mapIndexed { i, l -> if (i == index) transform(l) else l },
            ),
        )
    }

    fun moveLight(index: Int, dx: Float, dy: Float) =
        editLight(index) { it.copy(x = (it.x + dx).coerceIn(0f, 1f), y = (it.y + dy).coerceIn(0f, 1f)) }

    fun setVignette(amount: Float) = edit(R.string.history_light) { p ->
        p.copy(lighting = p.lighting.copy(vignette = amount.coerceIn(0f, 1f)))
    }

    // ------------------------------------------------------------ project files and export

    fun newProject() {
        editNow(R.string.history_new) {
            GradientProject.new(getApplication<Application>().getString(R.string.layer_default_name, 1))
        }
    }

    fun saveProject(uri: Uri) {
        if (busy) return
        val text = ProjectIO.toJson(project)
        busy = true
        viewModelScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                        true
                    } ?: false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                false
            }
            message = if (ok) R.string.project_saved else R.string.project_save_failed
            busy = false
        }
    }

    fun openProject(uri: Uri) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val opened = withContext(Dispatchers.IO) {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        it.readBytes()
                    } ?: throw IllegalArgumentException("Unreadable")
                    ProjectIO.fromJson(String(bytes, Charsets.UTF_8))
                }
                editNow(R.string.history_open) { opened }
                message = R.string.project_opened
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                message = R.string.project_open_failed
            }
            busy = false
        }
    }

    /** Renders at [longSide] pixels on the long edge and writes a lossless PNG (alpha preserved). */
    fun exportPng(uri: Uri, longSide: Int) {
        if (busy) return
        val snapshot = project
        busy = true
        viewModelScope.launch {
            val result: Int = try {
                withContext(Dispatchers.Default) {
                    val bitmap = renderBitmap(snapshot, longSide)
                    try {
                        val written = getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        } ?: false
                        if (written) R.string.export_done else R.string.export_failed
                    } finally {
                        bitmap.recycle()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                R.string.export_memory
            } catch (e: Throwable) {
                R.string.export_failed
            }
            message = result
            busy = false
        }
    }

    // ------------------------------------------------------------ palette tools (Phase 3)

    /** Stops that represent the selected layer's colors whatever its type. */
    private fun paletteStops(p: GradientProject): List<ColorStop> {
        val spec = p.selected.spec
        return if (spec.usesStops) spec.stops else ColorLab.stopsOf(ColorLab.colorsOf(spec))
    }

    private fun applyStops(spec: GradientSpec, stops: List<ColorStop>): GradientSpec =
        if (spec.usesStops) spec.copy(stops = stops) else ColorLab.withColors(spec, IntArray(stops.size) { stops[it].color })

    /** Replaces the selected layer's colors (used by suggestions, roulette and the math lab). */
    fun applyColors(colors: IntArray, @StringRes label: Int) = editNow(label) { p ->
        p.updateSpec { s ->
            if (s.usesStops && colors.size != s.stops.size) s.copy(stops = ColorLab.stopsOf(colors)) else ColorLab.withColors(s, colors)
        }
    }

    // Evolution: children of the current palette; picking one makes it the new parent.
    fun evolve(strength: Float, parents: List<List<ColorStop>> = emptyList()) {
        val base = paletteStops(project)
        val list = if (parents.isEmpty()) listOf(base) else parents
        generation.clear()
        generation.addAll(ColorLab.nextGeneration(list, EVOLUTION_SIZE, strength, random))
    }

    fun adoptCandidate(stops: List<ColorStop>) = editNow(R.string.history_evolve) { p ->
        p.updateSpec { applyStops(it, stops) }
    }

    fun clearGeneration() = generation.clear()

    /** One roulette result is applied right away so the preview shows it (history is committed by [applyColors]). */
    fun previewColors(colors: IntArray) = edit(null) { p ->
        p.updateSpec { s ->
            if (s.usesStops && colors.size != s.stops.size) s.copy(stops = ColorLab.stopsOf(colors)) else ColorLab.withColors(s, colors)
        }
    }

    fun spinRoulette(current: IntArray, locked: BooleanArray) = ColorLab.spin(current, locked, random)

    fun collideWith(partner: List<ColorStop>, impact: Float, zone: Float, energy: Float) = editNow(R.string.history_collision) { p ->
        p.updateSpec { s -> if (s.usesStops) s.copy(stops = ColorLab.collide(s.stops, partner, impact, zone, energy)) else s }
    }

    // DNA
    fun currentDna(): String = Dna.encode(project.selected.spec)

    fun applyDna(code: String): Boolean {
        val spec = Dna.decode(code) ?: return false
        editNow(R.string.history_dna) { p -> p.updateSpec { old -> spec.copy(mesh = old.mesh, gravity = old.gravity, path = old.path, map = old.map) } }
        return true
    }

    fun breedWith(code: String): Boolean {
        val other = Dna.decode(code) ?: return false
        editNow(R.string.history_dna) { p -> p.updateSpec { old -> Dna.breed(old, other, random).copy(mesh = old.mesh, gravity = old.gravity, path = old.path, map = old.map) } }
        return true
    }

    // Surgery: precise stop control
    fun distributeStops() = editNow(R.string.history_stops) { p ->
        p.updateSpec { s ->
            val sorted = s.stops.sortedBy { it.position }
            s.copy(stops = sorted.mapIndexed { i, st -> st.copy(position = if (sorted.size == 1) 0f else i / (sorted.size - 1f)) })
        }
    }

    fun nudgeStop(index: Int, delta: Float) = editNow(R.string.history_stops) { p ->
        p.updateSpec { s ->
            if (index !in s.stops.indices) s
            else s.copy(stops = s.stops.mapIndexed { i, st -> if (i == index) st.copy(position = (st.position + delta).coerceIn(0f, 1f)) else st })
        }
    }

    // ------------------------------------------------------------ animation (Phase 3)

    fun editAnimation(transform: (AnimationSpec) -> AnimationSpec) = edit(R.string.history_animation) { p ->
        val next = transform(p.animation).let {
            it.copy(
                durationMs = it.durationMs.coerceIn(500, 10_000),
                fps = it.fps.coerceIn(5, 60),
                orbit = it.orbit.coerceIn(0f, 0.5f),
                pulse = it.pulse.coerceIn(0f, 0.5f),
            )
        }
        p.copy(animation = next)
    }

    /** Remembers the current look as the end point of the morph. */
    fun saveMorphTarget() = editNow(R.string.history_morph) { p ->
        p.copy(morphTarget = p.layers, animation = p.animation.copy(morph = true))
    }

    fun clearMorphTarget() = editNow(R.string.history_morph) { p ->
        p.copy(morphTarget = null, animation = p.animation.copy(morph = false))
    }

    fun togglePlay() {
        if (playing) {
            playing = false
            playJob?.cancel()
            playJob = null
            renderPreview()
            return
        }
        playing = true
        playJob = viewModelScope.launch {
            val start = System.nanoTime()
            while (isActive) {
                val snapshot = project
                val duration = snapshot.animation.durationMs.coerceAtLeast(500)
                val t = (((System.nanoTime() - start) / 1_000_000L) % duration).toFloat() / duration
                val frame = withContext(Dispatchers.Default) { renderBitmap(snapshot, PLAY_SIDE, t) }
                preview = frame
                delay(1)
            }
        }
    }

    fun cancelExport() {
        exportCancelled = true
    }

    fun exportGif(uri: Uri, longSide: Int) = runAnimationExport(uri) { snapshot, stream ->
        AnimationExport.gif(snapshot, longSide, BufferedOutputStream(stream), { exportProgress = it }, { exportCancelled })
    }

    fun exportMp4(uri: Uri, longSide: Int, loops: Int) {
        if (busy) return
        val snapshot = project
        busy = true
        exportCancelled = false
        exportProgress = 0f
        exportJob = viewModelScope.launch {
            val temp = File(getApplication<Application>().cacheDir, "gradient_export.mp4")
            val result: Int = try {
                withContext(Dispatchers.Default) {
                    AnimationExport.mp4(snapshot, longSide, loops, temp, { exportProgress = it }, { exportCancelled })
                    val copied = getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                        temp.inputStream().use { it.copyTo(out) }
                        true
                    } ?: false
                    if (copied) R.string.export_done else R.string.export_failed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExportFailed) {
                if (e.message == "cancelled") R.string.export_cancelled else R.string.export_video_failed
            } catch (e: OutOfMemoryError) {
                R.string.export_memory
            } catch (e: Throwable) {
                R.string.export_failed
            } finally {
                temp.delete()
            }
            message = result
            exportProgress = null
            busy = false
        }
    }

    private fun runAnimationExport(uri: Uri, work: (GradientProject, java.io.OutputStream) -> Unit) {
        if (busy) return
        val snapshot = project
        busy = true
        exportCancelled = false
        exportProgress = 0f
        exportJob = viewModelScope.launch {
            val result: Int = try {
                withContext(Dispatchers.Default) {
                    val stream = getApplication<Application>().contentResolver.openOutputStream(uri)
                    if (stream == null) {
                        R.string.export_failed
                    } else {
                        stream.use { work(snapshot, it) }
                        R.string.export_done
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExportFailed) {
                if (e.message == "cancelled") R.string.export_cancelled else R.string.export_failed
            } catch (e: OutOfMemoryError) {
                R.string.export_memory
            } catch (e: Throwable) {
                R.string.export_failed
            }
            message = result
            exportProgress = null
            busy = false
        }
    }

    fun messageShown() {
        message = null
    }

    private companion object {
        const val PREVIEW_SIDE = 512
        const val PLAY_SIDE = 320
        const val EVOLUTION_SIZE = 8
        const val THUMB_SIDE = 96
        const val MAX_HISTORY = 40
        const val MAX_LAYERS = 12
        const val MAX_GRAVITY_POINTS = 8
        const val MAX_LIGHTS = 4
        const val MAX_PATH_POINTS = 160
        const val MIN_PATH_STEP = 0.008
        const val MAP_IMAGE_SIDE = 1024
        const val COMMIT_DELAY_MS = 450L

        /** [t] (0..1) renders that moment of the animation loop; null renders the project as edited. */
        fun renderBitmap(project: GradientProject, longSide: Int, t: Float? = null): Bitmap {
            val scale = longSide.toFloat() / maxOf(project.width, project.height)
            val w = (project.width * scale).toInt().coerceAtLeast(1)
            val h = (project.height * scale).toInt().coerceAtLeast(1)
            val pixels = if (t == null) GradientRenderer.render(project, w, h) else Animator.render(project, t, w, h)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            return bitmap
        }
    }
}
