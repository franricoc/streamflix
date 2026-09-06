# -----------------------------------------------------------------------------
# Streamflix ProGuard / R8 Rules
# -----------------------------------------------------------------------------

# General Attributes
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable
-dontwarn javax.annotation.**

# -----------------------------------------------------------------------------
# Kotlin & Coroutines
# -----------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# -----------------------------------------------------------------------------
# Project Models & Data Classes
# -----------------------------------------------------------------------------
-keep class com.streamflixreborn.streamflix.models.** { *; }
-keepclassmembers class com.streamflixreborn.streamflix.models.** { *; }
-keep class com.streamflixreborn.streamflix.sync.RemoteMediaState { *; }
-keepclassmembers class com.streamflixreborn.streamflix.sync.RemoteMediaState { *; }

# -----------------------------------------------------------------------------
# Providers & Extractors (Reflection & Registry Support)
# -----------------------------------------------------------------------------
-keep @interface com.streamflixreborn.streamflix.providers.StreamflixProvider
-keep @com.streamflixreborn.streamflix.providers.StreamflixProvider class * {
    public static final ** INSTANCE;
    *;
}
-keep class com.streamflixreborn.streamflix.providers.** {
    public static final ** INSTANCE;
    *;
}
-keep class com.streamflixreborn.streamflix.extractors.** {
    public static final ** INSTANCE;
    *;
}

# -----------------------------------------------------------------------------
# Room Persistence Library
# -----------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
-keep class androidx.room.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>(...);
}
-keep class * implements androidx.sqlite.db.SupportSQLiteOpenHelper$Factory
-keep class * implements androidx.sqlite.db.SupportSQLiteOpenHelper

# -----------------------------------------------------------------------------
# Gson & Retrofit
# -----------------------------------------------------------------------------
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.** { *; }
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# -----------------------------------------------------------------------------
# OkHttp
# -----------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# -----------------------------------------------------------------------------
# Kotlinx Serialization
# -----------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# -----------------------------------------------------------------------------
# Media3 / ExoPlayer
# -----------------------------------------------------------------------------
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# -----------------------------------------------------------------------------
# Glide
# -----------------------------------------------------------------------------
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-dontwarn com.bumptech.glide.**

# -----------------------------------------------------------------------------
# Supabase & Ktor
# -----------------------------------------------------------------------------
-keep class io.github.jan.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# -----------------------------------------------------------------------------
# Third-party utilities (Rhino, NanoHTTPD, Jsoup, Cronet, Conscrypt, SVG, ZXing)
# -----------------------------------------------------------------------------
-keep class org.jsoup.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class fi.iki.elonen.** { *; }
-keep class com.caverock.androidsvg.** { *; }
-keep class com.google.zxing.** { *; }
-keep class org.chromium.net.** { *; }
-keep class org.conscrypt.** { *; }
-keep class org.java_websocket.** { *; }