package com.example.arabicphotoeditor.scenecleanup

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.arabicphotoeditor.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

enum class CleanupTool { Brush, Eraser, Hand }

/**
 * State of the Scene Cleanup screen: the working photo, the user's selection, one shared undo/redo timeline
 * (selection strokes and removals), and the removal run. The photo is handed in by the editor and the result is
 * handed back when the user confirms; the editor's own undo then covers the whole cleanup as one step.
 */
class SceneCleanupViewModel(app: Application) : AndroidViewModel(app) {

    private class State(val photo: Bitmap, val mask: SelectionMask)

    private val timeline = ArrayList<State>()
    private var cursor = -1
    private var source: Bitmap? = null

    var tool by mutableStateOf(CleanupTool.Brush)
        private set

    /** Brush diameter on screen, in dp. Converted to image pixels by the view using its current zoom. */
    private val _brushDp = mutableFloatStateOf(36f)
    var brushDp: Float
        get() = _brushDp.value
        set(value) { _brushDp.value = value.coerceIn(8f, 120f) }

    var processing by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var message by mutableStateOf<Int?>(null)
        private set

    /** Bumped whenever the photo or the selection changes, so the UI reads the new values. */
    var revision by mutableIntStateOf(0)
        private set

    private var job: Job? = null
    @Volatile
    private var cancelRequested = false

    val photo: Bitmap? get() = timeline.getOrNull(cursor)?.photo
    val mask: SelectionMask? get() = timeline.getOrNull(cursor)?.mask
    val original: Bitmap? get() = source

    val hasPhoto: Boolean get() = photo != null
    val canUndo: Boolean get() = !processing && cursor > 0
    val canRedo: Boolean get() = !processing && cursor in 0 until timeline.lastIndex
    val hasSelection: Boolean get() = mask?.isEmpty() == false

    /** True when at least one removal has been applied (so there is something to hand back or compare). */
    val changed: Boolean get() = photo != null && photo !== source

    private val engine: RemovalEngine = BasicRemovalEngine

    // ------------------------------------------------------------ setup

    /** Called by the screen with the editor's current picture; the same bitmap again changes nothing. */
    fun open(bitmap: Bitmap?) {
        if (bitmap === source) return
        if (processing) return
        source = bitmap
        timeline.clear()
        cursor = -1
        if (bitmap != null) {
            timeline.add(State(bitmap, SelectionMask.forPhoto(bitmap.width, bitmap.height)))
            cursor = 0
        }
        revision++
    }

    fun selectTool(t: CleanupTool) {
        tool = t
    }

    // ------------------------------------------------------------ selection

    private fun push(photo: Bitmap, mask: SelectionMask) {
        while (timeline.size > cursor + 1) timeline.removeAt(timeline.lastIndex)
        timeline.add(State(photo, mask))
        while (timeline.size > MAX_STATES) timeline.removeAt(0)
        cursor = timeline.lastIndex
        revision++
    }

    /**
     * Adds a stroke to the selection ([add] = true) or removes it. [points] are normalized (0..1) over the photo and
     * [radiusFraction] is the brush radius as a fraction of the photo's long side.
     */
    fun commitStroke(points: List<Pair<Float, Float>>, radiusFraction: Float, add: Boolean) {
        val current = timeline.getOrNull(cursor) ?: return
        if (processing || points.isEmpty()) return
        val next = current.mask.copy()
        val longSide = max(next.width, next.height).toFloat()
        val px = points.map { it.first.coerceIn(0f, 1f) * next.width to it.second.coerceIn(0f, 1f) * next.height }
        next.stroke(px, radiusFraction * longSide, add)
        push(current.photo, next)
    }

    fun clearSelection() {
        val current = timeline.getOrNull(cursor) ?: return
        if (processing || current.mask.isEmpty()) return
        push(current.photo, SelectionMask(current.mask.width, current.mask.height))
    }

    fun undo() {
        if (!canUndo) return
        cursor--
        revision++
    }

    fun redo() {
        if (!canRedo) return
        cursor++
        revision++
    }

    // ------------------------------------------------------------ removal

    fun remove() {
        val current = timeline.getOrNull(cursor) ?: return
        if (processing || current.mask.isEmpty()) return
        processing = true
        progress = 0f
        cancelRequested = false
        job = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val photo = current.photo
                    // Grow the mask a little: the object's soft edge, halo and contact shadow are rarely selected exactly.
                    val grow = max(2, max(photo.width, photo.height) / 400)
                    val full = current.mask.toFullResolution(photo.width, photo.height, grow)
                    engine.remove(photo, full, { progress = it }, { cancelRequested })
                }
                if (cancelRequested) {
                    // Cancelled: keep the previous state untouched.
                } else {
                    push(result, SelectionMask(current.mask.width, current.mask.height))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                message = R.string.cleanup_memory
            } catch (e: Throwable) {
                message = R.string.cleanup_failed
            }
            processing = false
        }
    }

    fun cancel() {
        cancelRequested = true
    }

    fun messageShown() {
        message = null
    }

    private companion object {
        /** Each removal keeps a full bitmap, so the timeline is short. Selection-only states share one bitmap. */
        const val MAX_STATES = 14
    }
}
