# ============================================================
#  قواعد ProGuard/R8 لحماية الكلاسات المهمة من الإزالة أو التغيير
#  لمنع أخطاء وقت التشغيل (Runtime Errors)
# ============================================================

# ----- الحفاظ على جميع كلاسات التطبيق الرئيسية -----
-keep class com.example.app.** { *; }
-keep class com.example.app.**$* { *; }

# ----- الحفاظ على كلاسات الانعكاس (Reflection) المستخدمة في الكود -----
-keep class java.lang.reflect.** { *; }

# ----- الحفاظ على كلاسات TensorFlow Lite -----
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }

# ----- الحفاظ على كلاسات OkHttp (الشبكات) -----
-keep class okhttp3.** { *; }
-keep class okhttp3.logging.** { *; }
-keep interface okhttp3.** { *; }

# ----- الحفاظ على كلاسات org.json (تحليل JSON) -----
-keep class org.json.** { *; }

# ----- الحفاظ على كلاسات الأمان -----
-keep class androidx.security.crypto.** { *; }
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
-keepattributes *Annotation*, Signature, Exceptions, InnerClasses, EnclosingMethod

# ----- منع إزالة الكلاسات المستخدمة في الإنشاء الديناميكي (Dynamic Instantiation) -----
-keepclasseswithmembers class * {
    public <init>(...);
}

# ----- الحفاظ على جميع الحقول والدوال التي قد تُستخدم عن طريق الانعكاس -----
-keepclassmembers class * {
    @androidx.annotation.Keep <methods>;
    @androidx.annotation.Keep <fields>;
}

# ----- منع إزالة كلاسات Log (إذا تم استخدامها) -----
-keep class android.util.Log { *; }

# ----- منع تحسينات R8 التي قد تعطل استخدام بعض المكتبات -----
-dontwarn org.tensorflow.lite.**
-dontwarn okhttp3.**
-dontwarn org.json.**
-dontwarn javax.**
-dontwarn kotlin.**

# ----- احتفظ بأسماء الدوال في حال استخدام الانعكاس بالاسم -----
-keepnames class * {
    public *;
}

# ============================================================
#  نهاية القواعد
# ============================================================
