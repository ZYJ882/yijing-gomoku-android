# JNI entry points are referenced from native code.
-keep class com.gomoku.android.ai.NativeTacticalScanner { *; }

# Keep model fields used by the JSON LAN protocol and Android runtime diagnostics.
-keepclassmembers class com.gomoku.android.network.** { *; }
