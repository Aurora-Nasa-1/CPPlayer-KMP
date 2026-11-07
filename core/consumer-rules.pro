# Keep the core module's provider/api layer from being stripped.
-keep class cp.player.core.** { *; }

# JNA rules
-keepclassmembers class * extends com.sun.jna.Library {
    public <methods>;
    public <fields>;
}
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**