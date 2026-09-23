# JNA and the UniFFI-generated bindings are accessed reflectively.
-dontwarn java.awt.**
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class com.orangexp.core.engine.ffi.** { *; }
