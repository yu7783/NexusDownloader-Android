# --- yt-dlp / youtubedl-android ---
-keep class com.yausername.** { *; }
-keep class org.python.** { *; }

# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.nexus.downloader.**$$serializer { *; }
-keepclassmembers class com.nexus.downloader.** {
    *** Companion;
}

# --- Jsoup ---
-dontwarn org.jsoup.**
