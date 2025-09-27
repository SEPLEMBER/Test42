plugins {
    alias(libs.plugins.android-application)
    alias(libs.plugins.kotlin-android)
}

android {
    namespace = "org.syndes.rust"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.syndes.rust"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true // Включен R8 для оптимизации
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // debug настройки при необходимости
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    // Core
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.documentfile)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // EditorKit — основной выбор (легковесный, модульный, Apache-2.0)
    implementation(libs.editorkit)
    // Подключаем языковые модули по потребности — сейчас Rust и Kotlin как пример
    implementation(libs.editorkit.language.rust)
    implementation(libs.editorkit.language.kotlin)

    // Опционально: prism4j если будешь делать частично свой лексер/парсер
    implementation(libs.prism4j.bundler)

    // Опционально: лёгкий viewer
    implementation(libs.codeview)

    // Tests
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
