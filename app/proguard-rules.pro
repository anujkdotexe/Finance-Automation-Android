# Proguard configuration for Finance Automation Android

# Keep Kotlin reflect and metadata
-keepclassmembers class * {
    @org.jetbrains.annotations.* <fields>;
    @org.jetbrains.annotations.* <methods>;
}

# AndroidX Architecture & Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
-keep class * extends androidx.room.migration.Migration
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    *;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keepclassmembers class * extends okhttp3.OkHttpClient {
    *;
}

# KotlinX Coroutines
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.** { *; }
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}

# KotlinX Serialization
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * implements kotlinx.serialization.KSerializer {
    <fields>;
    <methods>;
}
-keep,allowobfuscation,allowshrinking class * {
    @kotlinx.serialization.Serializable class *;
}

# WorkManager
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Google Play Services Auth
-keep class com.google.android.gms.auth.api.** { *; }
-keep interface com.google.android.gms.auth.api.** { *; }
-dontwarn com.google.android.gms.**

# SQLCipher
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**

# Readable release stack traces (file names and line numbers are kept, class names are still shrunk)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
