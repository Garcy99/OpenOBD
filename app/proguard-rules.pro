# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.openobd.**$$serializer { *; }
-keepclassmembers class com.openobd.** { *** Companion; }
-keepclasseswithmembers class com.openobd.** { kotlinx.serialization.KSerializer serializer(...); }
