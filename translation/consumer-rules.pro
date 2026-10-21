# ONNX Runtime builds Java objects from native code through JNI, so R8 must not strip or rename them
-keep class ai.onnxruntime.** { *; }

# ML Kit text recognition is loaded from Play Services and wired through static fields R8 would optimise away
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
