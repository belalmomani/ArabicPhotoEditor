# ONNX Runtime uses JNI and looks up its Java classes by name.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
