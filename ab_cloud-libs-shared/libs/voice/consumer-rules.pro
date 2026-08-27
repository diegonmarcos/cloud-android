# Vosk + JNA use reflection / native bindings — keep them intact under R8.
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn org.vosk.**
