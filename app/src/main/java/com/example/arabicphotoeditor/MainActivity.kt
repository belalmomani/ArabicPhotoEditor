package com.example.arabicphotoeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.arabicphotoeditor.ui.editor.EditorScreen
import com.example.arabicphotoeditor.ui.editor.EditorViewModel
import com.example.arabicphotoeditor.ui.gradient.GradientStudioScreen
import com.example.arabicphotoeditor.ui.theme.ArabicPhotoEditorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArabicPhotoEditorTheme {
                val editorVm: EditorViewModel = viewModel()
                var showGradients by rememberSaveable { mutableStateOf(false) }
                if (showGradients) {
                    BackHandler { showGradients = false }
                    GradientStudioScreen(
                        onBack = { showGradients = false },
                        photo = editorVm.result ?: editorVm.original,
                        hasCutout = editorVm.result != null,
                        onApplyToPhoto = { bitmap ->
                            editorVm.applyGradientResult(bitmap)
                            showGradients = false
                        },
                    )
                } else {
                    EditorScreen(vm = editorVm, onOpenGradients = { showGradients = true })
                }
            }
        }
    }
}
