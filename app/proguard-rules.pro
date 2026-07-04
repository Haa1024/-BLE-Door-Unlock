# ─── Compose ───
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ─── Kotlin 协程 ───
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ─── BLE / 蓝牙 ───
-keep class android.bluetooth.** { *; }
-keep class com.unlock.door.BleUnlockManager { *; }
-keep class com.unlock.door.Crypto { *; }

# ─── SharedPreferences ───
-keep class com.unlock.door.SettingsManager { *; }

# ─── 小组件 ───
-keep class com.unlock.door.DoorLockWidgetProvider { *; }

# ─── 服务器 API ───
-keep class com.unlock.door.ServerApi { *; }
-keep class okhttp3.** { *; }
-keep class org.json.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# ─── 序列化相关 ───
-keepattributes Signature
-keepattributes *Annotation*

# ─── R8 优化 ───
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
