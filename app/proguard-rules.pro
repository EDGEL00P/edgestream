# Room entities and DAOs are generated and kept by Room's own rules.
# OkHttp ships its own consumer rules.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.jetbrains.annotations.**

# Vosk speech recognition (auto captions) is called through JNA by reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
