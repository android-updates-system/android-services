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

        // تحديد معماريات المعالج المدعومة (لتقليل حجم APK)
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }

        // تقييد اللغة إلى الإنجليزية فقط لتقليل حجم APK
        resConfigs("en")

        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "VERSION", "\"${defaultConfig.versionName}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlin.ExperimentalStdlibApi",
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions"
        )
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    // استبعاد الملفات غير الضرورية من APK لتقليل الحجم
    packaging {
        resources {
            excludes += setOf(
                "META-INF/**",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/ASL2.0",
                "META-INF/README.md",
                "META-INF/MANIFEST.MF"
            )
            // استبعاد مكتبات x86 و mips (غير مدعومة في معظم الأجهزة الحديثة)
            excludes += setOf(
                "**/lib/x86/*.so",
                "**/lib/x86_64/*.so",
                "**/lib/mips/*.so"
            )
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    lint {
        disable += "Instantiatable"
        disable += "GradleDeprecated"
        disable += "ObsoleteSdkInt"
        disable += "MissingTranslation"
        disable += "ExtraTranslation"
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // AndroidX الأساسية
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // دورة الحياة و Coroutines
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // الشبكات ومعالجة JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // TensorFlow Lite (للذكاء الاصطناعي)
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // التشفير والأمان (EncryptedSharedPreferences)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // دعم desugaring لميزات Java 8+ في الإصدارات الأقدم
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // اختبارات
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
