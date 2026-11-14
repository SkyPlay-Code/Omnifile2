# Keep Room database entities, DAOs, and generated implementations
-keep class com.example.data.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep Data Models
-keep class com.example.model.** { *; }

# Keep Engine entry points & Media3 annotations
-keep class com.example.engine.** { *; }
-dontwarn androidx.media3.**

# Silence harmless warnings from unused template dependencies (if kept)
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn com.squareup.moshi.**
-dontwarn com.google.firebase.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
