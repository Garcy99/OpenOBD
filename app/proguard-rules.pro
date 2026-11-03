# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.openobd.**$$serializer { *; }
-keepclassmembers class com.openobd.** { *** Companion; }
-keepclasseswithmembers class com.openobd.** { kotlinx.serialization.KSerializer serializer(...); }

# Anthropic Java SDK (optional AI assistant). Jackson (de)serializes the SDK's model classes by
# reflection, so they must survive shrinking; OpenTelemetry references AutoValue only at compile time.
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.google.auto.value.AutoValue$*
-keepattributes Signature, EnclosingMethod, InnerClasses, *Annotation*
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.databind.**
-keep class kotlin.Metadata { *; }
# JSON Schema generation for structured outputs is not used by the app (and needs JDK-only reflection).
-dontwarn com.github.victools.jsonschema.**
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType
