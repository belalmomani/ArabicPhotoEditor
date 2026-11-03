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
import com.example.arabicphotoeditor.ui.editor.EditorScreen
import com.example.arabicphotoeditor.ui.gradient.GradientStudioScreen
import com.example.arabicphotoeditor.ui.theme.ArabicPhotoEditorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArabicPhotoEditorTheme {
                var showGradients by rememberSaveable { mutableStateOf(false) }
                if (showGradients) {
                    BackHandler { showGradients = false }
                    GradientStudioScreen(onBack = { showGradients = false })
                } else {
                    EditorScreen(onOpenGradients = { showGradients = true })
                }
            }
        }
    }
}
