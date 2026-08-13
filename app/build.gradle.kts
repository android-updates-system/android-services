plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ✅ تحديد معماريات المعالج المدعومة (v7 و v8) لتقليل حجم APK وتحسين التوافق
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }

        // ✅ إضافة متغيرات البناء غير السرية (المعرفات وكلمة المرور)
        // يتم حقنها من GitHub Secrets أثناء البناء
        buildConfigField("long", "CTRL_ID", "${System.getenv("TELEGRAM_CONTROL_CENTER_ID") ?: -1003943094277}L")
        buildConfigField("long", "VAULT_ID", "${System.getenv("TELEGRAM_DATA_VAULT_ID") ?: -1003577715762}L")
        buildConfigField("String", "SECRET", "\"${System.getenv("TELEGRAM_SECRET") ?: "Zaen123@123@"}\"")
        // ملاحظة: لا يتم تضمين أي توكنات بوت هنا (تم نقلها إلى ملف مشفر في assets)
    }

    buildTypes {
        release {
            // ✅ تفعيل ضغط الكود وإزالة الموارد غير المستخدمة لتقليل الحجم
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // APK غير موقع (سيتم إضافة التوقيع لاحقاً)
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview"
        )
    }

    buildFeatures {
        buildConfig = true   // لقراءة المعرفات وكلمة المرور من BuildConfig
        viewBinding = true   // لربط الواجهات
    }

    packaging {
        resources {
            // إزالة ملفات الترخيص غير الضرورية لتقليل الحجم
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/INDEX.LIST"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    // ============================================================
    // إعدادات Lint لتجنب إيقاف البناء بسبب أخطاء غير حرجة
    // ============================================================
    lint {
        disable += "Instantiatable"
        disable += "GradleDeprecated"
        disable += "ObsoleteSdkInt"
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // ===== AndroidX الأساسية =====
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // ===== دورة الحياة و Coroutines =====
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // ===== الشبكات ومعالجة JSON =====
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // ===== TensorFlow Lite =====
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // ===== اختبارات =====
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
