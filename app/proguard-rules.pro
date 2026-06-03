# Add project specific ProGuard rules here.
# Disable R8 optimization to prevent JNI/reflection issues with MediaPipe and TFLite
-dontoptimize

# ========== MediaPipe ==========
-keep class com.google.mediapipe.** { *; }
-keep class com.google.mediapipe.proto.** { *; }

# MediaPipe tasks vision (ObjectDetector, FaceLandmarker, PoseLandmarker, HandLandmarker)
-keep class com.google.mediapipe.tasks.** { *; }

# MediaPipe framework (BitmapImageBuilder, etc.)
-keep class com.google.mediapipe.framework.** { *; }

# ========== TensorFlow Lite ==========
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.task.** { *; }
-keep class org.tensorflow.lite.support.** { *; }

# TFLite audio classifier (yamnet)
-keep class org.tensorflow.lite.task.audio.** { *; }
-keep class org.tensorflow.lite.support.audio.** { *; }

# ========== Protobuf (used by MediaPipe internally) ==========
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.ByteString { *; }

# ========== NanoHTTPD ==========
-keep class org.nanohttpd.** { *; }

# ========== Room ==========
-keep class * extends androidx.room.RoomDatabase { *; }

# ========== App data models (Gson serialization) ==========
-keep class com.homecam.app.data.** { *; }
-keep class com.homecam.app.model.** { *; }
-keep class com.homecam.app.network.** { *; }

# Keep model files in assets
-keepclassmembers class com.homecam.app.detection.EventDetector {
    private <fields>;
}

# Keep ServiceLoader META-INF services
-keepclassmembers class * {
    @java.lang.SuppressWarnings <fields>;
}
-keepnames class * implements java.io.Serializable

# ========== R8 missing class warnings ==========
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate
-dontwarn javax.annotation.processing.AbstractProcessor
-dontwarn javax.annotation.processing.SupportedAnnotationTypes
-dontwarn javax.lang.model.SourceVersion
-dontwarn javax.lang.model.element.Element
-dontwarn javax.lang.model.element.ElementKind
-dontwarn javax.lang.model.element.Modifier
-dontwarn javax.lang.model.type.TypeMirror
-dontwarn javax.lang.model.type.TypeVisitor
-dontwarn javax.lang.model.util.SimpleTypeVisitor8
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn javax.annotation.Nullable