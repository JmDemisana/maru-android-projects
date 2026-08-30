# Nami Space ProGuard Rules

# Firebase AI
-keep class com.google.firebase.ai.** { *; }
-dontwarn com.google.firebase.ai.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.maru.namispace.model.** { <fields>; }
-keep class com.google.gson.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep BuildConfig API key
-keep class com.maru.namispace.BuildConfig { *; }
