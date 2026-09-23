# Keep kotlinx.serialization generated serializers for our own classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.khatwa.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.khatwa.**$$serializer { *; }
-keepclassmembers class com.khatwa.** {
    *** Companion;
}
# Room entities are accessed via generated code; keep their fields.
-keep class com.khatwa.app.data.** { *; }
