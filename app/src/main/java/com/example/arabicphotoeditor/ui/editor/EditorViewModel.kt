package com.example.arabicphotoeditor.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.arabicphotoeditor.R
import com.example.arabicphotoeditor.data.ImageLoader
import com.example.arabicphotoeditor.retouch.BlemishInpainter
import com.example.arabicphotoeditor.segmentation.BackgroundRemover
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BgStatus { Idle, Processing, Done, Failed }

/**
 * Holds the picked image, the background-removal result, and the undo/redo history.
 * Decoding and image processing run off the main thread; only one operation can be active at a time.
 */
class EditorViewModel(app: Application) : AndroidViewModel(app) {

    /** The picked photo, including any blemish edits made while no background-removed result existed. */
    var original by mutableStateOf<Bitmap?>(null)
        private set

    /** The background-removed image (transparent), or null. Blemish edits apply to it while it exists. */
    var result by mutableStateOf<Bitmap?>(null)
        private set
    var status by mutableStateOf(BgStatus.Idle)
        private set

    /** A string resource id (R.string.*) describing the last failure, or null. */
    var errorRes by mutableStateOf<Int?>(null)
        private set
    var showOriginal by mutableStateOf(false)
        private set
    var retouching by mutableStateOf(false)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** Message to show once (R.string.*), set after a save or retouch attempt. */
    var message by mutableStateOf<Int?>(null)
        private set

    private class Snapshot(val original: Bitmap?, val result: Bitmap?)

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    private var job: Job? = null
    private var saving = false
    private val remover by lazy { BackgroundRemover(app) }

    private val busy get() = status == BgStatus.Processing || retouching

    // ------------------------------------------------------------ history

    private fun refreshFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun snapshot() = Snapshot(original, result)

    /** Records the current state for undo, then applies the new one. */
    private fun commit(newOriginal: Bitmap?, newResult: Bitmap?) {
        undoStack.addLast(snapshot())
        while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        restore(newOriginal, newResult)
    }

    private fun restore(newOriginal: Bitmap?, newResult: Bitmap?) {
        original = newOriginal
        result = newResult
        showOriginal = false
        status = if (newResult != null) BgStatus.Done else BgStatus.Idle
        errorRes = null
        refreshFlags()
    }

    fun undo() {
        if (busy) return
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(snapshot())
        restore(previous.original, previous.result)
    }

    fun redo() {
        if (busy) return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(snapshot())
        restore(next.original, next.result)
    }

    // ------------------------------------------------------------ image

    fun loadImage(uri: Uri) {
        if (busy) return
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { ImageLoader.load(getApplication(), uri) }
                undoStack.clear()
                redoStack.clear()
                restore(bitmap, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Keep the previous image (if any); just report the failure.
                status = BgStatus.Failed
                errorRes = if (e is OutOfMemoryError) R.string.bg_failed_memory else R.string.image_load_failed
            }
        }
    }

    // ------------------------------------------------------------ background removal

    /** No-op unless an image is loaded, no result exists yet, and nothing is already running. */
    fun removeBackground() {
        val source = original ?: return
        if (busy || result != null) return
        status = BgStatus.Processing
        errorRes = null
        job = viewModelScope.launch {
            try {
                val out = withContext(Dispatchers.Default) { remover.remove(source) }
                commit(source, out)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                status = BgStatus.Failed
                errorRes = if (e is OutOfMemoryError) R.string.bg_failed_memory else R.string.bg_failed_generic
            }
        }
    }

    fun toggleCompare() {
        if (result != null) showOriginal = !showOriginal
    }

    /** Drops the background-removed result (undoable), keeping the original. */
    fun revertToOriginal() {
        if (busy || result == null) return
        commit(original, null)
    }

    // ------------------------------------------------------------ blemish removal

    /**
     * Fills the stroke area from its surroundings. [points] are normalized (0..1) to the displayed image and
     * [radiusFraction] is the brush radius as a fraction of the image width. Edits the background-removed
     * result when there is one, otherwise the original.
     */
    fun removeBlemish(points: List<Pair<Float, Float>>, radiusFraction: Float) {
        if (busy || points.isEmpty()) return
        val target = result ?: original ?: return
        val editingResult = result != null
        retouching = true
        job = viewModelScope.launch {
            try {
                val edited = withContext(Dispatchers.Default) {
                    val mask = BlemishInpainter.buildMask(
                        target.width, target.height, points, radiusFraction * target.width,
                    )
                    BlemishInpainter.inpaint(target, mask)
                }
                if (editingResult) commit(original, edited) else commit(edited, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                message = if (e is OutOfMemoryError) R.string.bg_failed_memory else R.string.blemish_failed
            } finally {
                retouching = false
            }
        }
    }

    // ------------------------------------------------------------ save

    fun messageShown() {
        message = null
    }

    /** Writes the current image (background-removed result if present) as a lossless PNG to the chosen document. */
    fun saveImage(uri: Uri) {
        val bitmap = result ?: original ?: return
        if (saving) return
        saving = true
        viewModelScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    } ?: false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                false
            }
            message = if (ok) R.string.save_done else R.string.save_failed
            saving = false
        }
    }

    override fun onCleared() {
        remover.close()
    }

    private companion object {
        /** Each step can hold a full bitmap, so keep the history short on low-memory phones. */
        const val MAX_HISTORY = 4
    }
}
