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
import com.example.arabicphotoeditor.segmentation.BackgroundRemover
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BgStatus { Idle, Processing, Done, Failed }

/**
 * Holds the picked image and the background-removal result.
 * Inference and decoding run off the main thread; only one run can be active at a time.
 */
class EditorViewModel(app: Application) : AndroidViewModel(app) {

    var original by mutableStateOf<Bitmap?>(null)
        private set
    var result by mutableStateOf<Bitmap?>(null)
        private set
    var status by mutableStateOf(BgStatus.Idle)
        private set
    /** A string resource id (R.string.*) describing the last failure, or null. */
    var errorRes by mutableStateOf<Int?>(null)
        private set
    var showOriginal by mutableStateOf(false)
        private set

    private var job: Job? = null
    private val remover by lazy { BackgroundRemover(app) }

    fun loadImage(uri: Uri) {
        if (status == BgStatus.Processing) return
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { ImageLoader.load(getApplication(), uri) }
                original = bitmap
                result = null
                showOriginal = false
                status = BgStatus.Idle
                errorRes = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Keep the previous image (if any); just report the failure.
                status = BgStatus.Failed
                errorRes = if (e is OutOfMemoryError) R.string.bg_failed_memory else R.string.image_load_failed
            }
        }
    }

    /** No-op unless an image is loaded, no result exists yet, and nothing is already running. */
    fun removeBackground() {
        val source = original ?: return
        if (status == BgStatus.Processing || result != null) return
        status = BgStatus.Processing
        errorRes = null
        job = viewModelScope.launch {
            try {
                val out = withContext(Dispatchers.Default) { remover.remove(source) }
                result = out
                showOriginal = false
                status = BgStatus.Done
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

    /** Discards the result and goes back to the untouched original. */
    fun revertToOriginal() {
        if (status == BgStatus.Processing) return
        result = null
        showOriginal = false
        status = BgStatus.Idle
        errorRes = null
    }

    override fun onCleared() {
        remover.close()
    }
}
