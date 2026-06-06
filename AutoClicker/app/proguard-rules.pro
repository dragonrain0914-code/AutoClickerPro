# AutoClicker ProGuard Rules

# 保留無障礙服務
-keep class com.autoclicker.pro.accessibility.** { *; }
-keep class com.autoclicker.pro.overlay.** { *; }
-keep class com.autoclicker.pro.service.** { *; }
-keep class com.autoclicker.pro.model.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
