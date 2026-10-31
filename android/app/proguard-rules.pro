# 保留 kotlinx.serialization 生成的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.studycheck.app.**$$serializer { *; }
-keepclassmembers class com.studycheck.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.studycheck.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit
-keepattributes Signature, Exceptions
-dontwarn okhttp3.**
-dontwarn okio.**
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ---------------------------------------------------------------
# Hilt / Dagger
# R8 会把生成的组件类改名，导致 Application 启动时注入失败直接崩
# ---------------------------------------------------------------
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ApplicationComponentManager { *; }
# Hilt 注解处理器生成的 Hilt_<Application> / Hilt_<Activity> 等类，按名字被反射查找
-keep class **Hilt_** { *; }
-keep class **.*_HiltComponents** { *; }
-keep,allowobfuscation @interface dagger.hilt.android.HiltAndroidApp
-keepclasseswithmembers class * {
    @dagger.hilt.android.HiltAndroidApp <methods>;
}
-keep,allowobfuscation @interface dagger.hilt.android.AndroidEntryPoint
-keepclasseswithmembers class * {
    @dagger.hilt.android.AndroidEntryPoint <methods>;
}
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
# Hilt 生成的 @EntryPoint 实现
-keep,allowobfuscation @interface dagger.hilt.EntryPoint
-keepclasseswithmembers class * {
    @dagger.hilt.EntryPoint <methods>;
}

# ---------------------------------------------------------------
# WorkManager：Worker 由系统反射实例化，构造不能被删/改名
# ---------------------------------------------------------------
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class androidx.work.impl.WorkManagerInitializer { *; }
-keep class androidx.work.Configuration { *; }
-keepclassmembers class * extends androidx.work.CoroutineWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ---------------------------------------------------------------
# Room：生成的 DAO 实现按名字反射查找
# ---------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers class * {
    @androidx.room.Query <methods>;
}

# ---------------------------------------------------------------
# AppWidgetProvider / TileService：由系统实例化
# ---------------------------------------------------------------
-keep class com.studycheck.app.widget.** { *; }
