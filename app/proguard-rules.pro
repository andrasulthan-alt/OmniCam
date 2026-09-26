# OmniCam uses no reflection of its own; AndroidX/CameraX/Compose ship their own consumer rules.
# Silence warnings for compile-only annotation packages referenced by libraries.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
