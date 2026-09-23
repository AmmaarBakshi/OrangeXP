# Type-safe navigation routes are @Serializable objects resolved at runtime.
-keepattributes *Annotation*, InnerClasses
-keep @kotlinx.serialization.Serializable class com.orangexp.** { *; }
-keepclassmembers class com.orangexp.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
