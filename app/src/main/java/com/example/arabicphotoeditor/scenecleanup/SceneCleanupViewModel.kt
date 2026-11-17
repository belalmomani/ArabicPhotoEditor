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

/** Detection state shown in the UI. */
sealed class DetectionState {
    object Idle    : DetectionState()
    object Running : DetectionState()
    data class Done(val regions: List<DetectedRegion>) : DetectionState()
    object NoRegions : DetectionState()  // model ran but nothing found
}

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

    /** Number of disconnected regions in the current selection (shown in the Remove button label). */
    var regionCount by mutableIntStateOf(0)
        private set

    /** Index of the region currently being processed (1-based, shown in progress label). */
    var currentRegion by mutableIntStateOf(0)
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

    private val engine: RemovalEngine = AutoRemovalEngine
    private var detector: SaliencyDetector? = null

    /** Auto-detection state. UI shows an overlay of bounding boxes when Done. */
    var detectionState by mutableStateOf<DetectionState>(DetectionState.Idle)
        private set

    /** Quality report from the last removal, null until a removal completes. */
    var qualityReport by mutableStateOf<QualityChecker.QualityReport?>(null)
        private set

    /** Mask saved before the last removal, so retry can re-run on the same selection. */
    private var lastRemovalMask: SelectionMask? = null
    private var lastRemovalPhoto: Bitmap? = null

    // ------------------------------------------------------------ setup

    /** Called by the screen with the editor's current picture; the same bitmap again changes nothing. */
    fun open(bitmap: Bitmap?) {
        if (bitmap === source) return
        if (processing) return
        source = bitmap
        timeline.clear()
        cursor = -1
        detectionState = DetectionState.Idle
        qualityReport = null
        lastRemovalMask = null
        lastRemovalPhoto = null
        if (bitmap != null) {
            timeline.add(State(bitmap, SelectionMask.forPhoto(bitmap.width, bitmap.height)))
            cursor = 0
        }
        revision++
    }

    /**
     * Runs the saliency model on the current photo and stores detected regions.
     * The user then taps a region to add it to the selection. Nothing is auto-removed.
     */
    fun detectObjects() {
        val photo = photo ?: return
        if (processing || detectionState == DetectionState.Running) return
        detectionState = DetectionState.Running
        viewModelScope.launch {
            val regions = withContext(Dispatchers.Default) {
                val det = detector ?: SaliencyDetector(getApplication()).also { detector = it }
                det.detect(photo)
            }
            detectionState = if (regions.isEmpty()) DetectionState.NoRegions
                             else DetectionState.Done(regions)
        }
    }

    /** User tapped at normalized photo coordinates (nx, ny); add the region that contains the point. */
    fun tapRegion(nx: Float, ny: Float) {
        val state = detectionState
        if (state !is DetectionState.Done) return
        val region = state.regions.firstOrNull { it.contains(nx, ny) } ?: return
        val current = timeline.getOrNull(cursor) ?: return
        if (processing) return
        // Merge this region's mask into the current selection.
        val next = current.mask.copy()
        val regionSel = region.toSelectionMask(next.width, next.height)
        for (i in next.data.indices) {
            if (regionSel.data[i] != 0.toByte()) next.data[i] = SelectionMask.SELECTED
        }
        push(current.photo, next)
    }

    /** Dismiss the detection overlay without selecting anything. */
    fun dismissDetection() {
        detectionState = DetectionState.Idle
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
        regionCount = countRegions(mask)
        revision++
    }

    private fun countRegions(mask: SelectionMask): Int {
        if (mask.isEmpty()) return 0
        // Count directly on the selection-mask data without upscaling.
        val bool = BooleanArray(mask.data.size) { mask.data[it] != 0.toByte() }
        return RegionLabeler.label(bool, mask.width, mask.height).size
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

    /**
     * Removes all selected regions one by one:
     * 1. Labels disconnected regions in the current mask.
     * 2. Sorts them largest-first (more source pixels available for later fills).
     * 3. For each region: grow mask edges → engine.remove → ColourMatcher.correct → push snapshot.
     * 4. Each region is a separate timeline step, so the user can undo any single removal.
     */
    fun remove() {
        val current = timeline.getOrNull(cursor) ?: return
        if (processing || current.mask.isEmpty()) return
        processing = true
        progress = 0f
        cancelRequested = false
        currentRegion = 0

        // Save the state before removal for retry.
        lastRemovalMask  = current.mask.copy()
        lastRemovalPhoto = current.photo
        qualityReport    = null

        job = viewModelScope.launch {
            try {
                val selW = current.mask.width
                val selH = current.mask.height

                // Label regions on Default dispatcher (CPU work).
                val (regions, maskBool) = withContext(Dispatchers.Default) {
                    val mb = BooleanArray(selW * selH) { current.mask.data[it] != 0.toByte() }
                    RegionLabeler.label(mb, selW, selH) to mb
                }

                if (regions.isEmpty()) {
                    processing = false
                    return@launch
                }

                val total = regions.size
                var workingPhoto = current.photo
                val grow = max(2, max(workingPhoto.width, workingPhoto.height) / 400)

                for ((idx, region) in regions.withIndex()) {
                    if (cancelRequested) break

                    // Update UI counters on Main.
                    currentRegion = idx + 1
                    progress = idx.toFloat() / total

                    // Heavy work on Default.
                    val (filled, fullMask) = withContext(Dispatchers.Default) {
                        // Build full-res mask for this region only.
                        val regionBytes = ByteArray(selW * selH).also { dst ->
                            for (i in region.indices) dst[i] = SelectionMask.SELECTED
                        }
                        val regionSelMask = SelectionMask(selW, selH, regionBytes)
                        val fm = regionSelMask.toFullResolution(workingPhoto.width, workingPhoto.height, grow)

                        val f = engine.remove(
                            workingPhoto, fm,
                            progress = { p -> progress = (idx + p) / total },
                            cancelled = { cancelRequested },
                        )
                        f to fm
                    }

                    if (cancelRequested) break

                    // Colour correction on Default; recycle the raw fill afterwards.
                    val corrected = withContext(Dispatchers.Default) {
                        val c = ColourMatcher.correct(filled, fullMask)
                        // If ColourMatcher returned a *new* bitmap (correction was applied),
                        // recycle the raw fill to free memory immediately.
                        if (c !== filled) filled.recycle()
                        c
                    }

                    // Recycle the previous working photo if it is no longer the source image
                    // and not still in the undo timeline.
                    val prevWorking = workingPhoto
                    workingPhoto = corrected
                    if (prevWorking !== source && !timeline.any { it.photo === prevWorking }) {
                        prevWorking.recycle()
                    }

                    // Update remaining selection (remove consumed region from maskBool).
                    for (i in region.indices) maskBool[i] = false
                    val remainingBytes = ByteArray(selW * selH).also { dst ->
                        for (i in dst.indices) if (maskBool[i]) dst[i] = SelectionMask.SELECTED
                    }
                    val remainingMask = SelectionMask(selW, selH, remainingBytes)

                    // Push snapshot on Main so compose reads it.
                    push(workingPhoto, remainingMask)
                }

                // Ensure final state has an empty selection mask.
                if (!cancelRequested) {
                    val cur = timeline.getOrNull(cursor)
                    if (cur != null && !cur.mask.isEmpty()) {
                        push(cur.photo, SelectionMask(cur.mask.width, cur.mask.height))
                    }
                    // Run quality check on the final result vs original mask.
                    val finalPhoto = timeline.getOrNull(cursor)?.photo
                    val origMask   = lastRemovalMask
                    if (finalPhoto != null && origMask != null) {
                        val report = withContext(Dispatchers.Default) {
                            val fullMask = origMask.toFullResolution(
                                finalPhoto.width, finalPhoto.height, 0)
                            QualityChecker.check(finalPhoto, fullMask)
                        }
                        qualityReport = report
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                message = R.string.cleanup_memory
            } catch (e: Throwable) {
                message = R.string.cleanup_failed
            }
            processing = false
            currentRegion = 0
            progress = 0f
        }
    }

    fun cancel() {
        cancelRequested = true
    }

    /**
     * Undoes the last removal and re-runs it so PatchMatch picks a different random seed.
     * Only available when [qualityReport] is non-null (i.e. a removal just finished).
     *
     * Strategy: walk the timeline backwards until we find the state whose photo matches the
     * pre-removal photo, then push the saved mask back on top and call [remove].  If the
     * timeline was trimmed and the state is gone, push fresh from the saved bitmaps.
     */
    fun retry() {
        val retryMask  = lastRemovalMask  ?: return
        val retryPhoto = lastRemovalPhoto ?: return
        if (processing) return

        qualityReport = null

        // Walk back to the pre-removal state.
        var found = false
        for (i in cursor downTo 0) {
            if (timeline.getOrNull(i)?.photo === retryPhoto) {
                cursor = i
                found = true
                break
            }
        }
        // Whether we found it or not, push the original photo + mask as a new state to retry.
        if (!found) {
            // Timeline was trimmed — reinsert the saved state.
            while (timeline.size > cursor + 1) timeline.removeAt(timeline.lastIndex)
            timeline.add(State(retryPhoto, retryMask))
            cursor = timeline.lastIndex
        }
        push(retryPhoto, retryMask)
        remove()
    }

    /** Hides the quality report card without retrying. */
    fun dismissQuality() { qualityReport = null }

    fun messageShown() {
        message = null
    }

    override fun onCleared() {
        super.onCleared()
        detector?.close()
        detector = null
    }

    private companion object {
        /** Each removal keeps a full bitmap, so the timeline is short. Selection-only states share one bitmap. */
        const val MAX_STATES = 14
    }
}
