# Volta release rules. Room, Hilt, OkHttp, Compose and the Car App Library ship their own consumer rules.

# kotlinx.serialization: keep the generated serializers and the companion lookups.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.irshadm.volta.**$$serializer { *; }
-keepclassmembers class dev.irshadm.volta.** { *** Companion; }
-keepclasseswithmembers class dev.irshadm.volta.** { kotlinx.serialization.KSerializer serializer(...); }

# The car host reads the CarAppService by name from the manifest.
-keep class dev.irshadm.volta.car.VoltaCarAppService { *; }

# ML Kit creates its component registrars by reflection, from names in the manifest.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }

# Readable crash lines in Play's reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
