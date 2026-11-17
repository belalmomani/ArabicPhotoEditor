# ============================================================
# ArabicPhotoEditor — ProGuard / R8 rules
# ============================================================

# ── ONNX Runtime ─────────────────────────────────────────────
# Uses JNI and loads its Java classes by reflected name at runtime.
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ── Kotlin ───────────────────────────────────────────────────
# Coroutines use reflection to read the DEBUG_ALWAYS_JUST_RESUME_ELSE_RESET flag.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# Kotlin serialization (if used transitively)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# ── Jetpack Compose ───────────────────────────────────────────
# Compose compiler generates classes whose names the runtime resolves.
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ── AndroidX ViewModel ───────────────────────────────────────
# ViewModelFactory uses reflection to find the no-arg or Application constructor.
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}

# ── Enums ────────────────────────────────────────────────────
# R8 can inline enums; keep the values() / valueOf() methods used by Compose.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Data classes used as Compose state ───────────────────────
# SceneCleanup data classes — their field names can be accessed reflectively
# by Compose's snapshot system through property delegates.
-keep class com.example.arabicphotoeditor.scenecleanup.DetectedRegion { *; }
-keep class com.example.arabicphotoeditor.scenecleanup.QualityChecker$QualityReport { *; }

# ── General Android ──────────────────────────────────────────
-keepattributes SourceFile, LineNumberTable   # readable crash stack traces
-renamesourcefileattribute SourceFile

# Keep custom Application / Activity subclasses (found by manifest name).
-keep class com.example.arabicphotoeditor.MainActivity { *; }
