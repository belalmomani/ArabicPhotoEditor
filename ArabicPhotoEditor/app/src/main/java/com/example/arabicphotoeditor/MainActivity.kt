package com.example.arabicphotoeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.arabicphotoeditor.ui.editor.EditorScreen
import com.example.arabicphotoeditor.ui.theme.ArabicPhotoEditorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArabicPhotoEditorTheme {
                EditorScreen()
            }
        }
    }
}
