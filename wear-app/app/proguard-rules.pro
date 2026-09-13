# ProGuard/R8 rules for the release build.
#
# NOTE: the release buildType currently ships with isMinifyEnabled = false, so
# these rules are dormant. They exist so that enabling minification later is a
# one-line change with the known-necessary keeps already in place. Do not enable
# minification without validating a signed release build on a real Pixel Watch
# against a real board (Health Services + reflection-heavy libraries can be
# stripped in ways that only surface at runtime).

# --- Health Services (reflection over data types / builders) ---
-keep class androidx.health.services.** { *; }
-dontwarn androidx.health.services.**

# --- Jetpack Compose / Wear Compose ---
-keep class androidx.compose.** { *; }
-keep class androidx.wear.compose.** { *; }
-dontwarn androidx.compose.**

# --- Kotlin coroutines ---
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# --- App domain models are plain data classes; keep names for readable logs/crashes ---
-keep class app.floatface.core.** { *; }

# Keep annotations and Kotlin metadata.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class kotlin.Metadata { *; }
