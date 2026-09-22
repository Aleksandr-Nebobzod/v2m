import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Android-приложение v2m (план docs/260921_android_plan.md, этап 1).
// На данном этапе — каркас: проверка сборки и запуска без нативного ядра.
// Общий код подключается из :shared (этап 1б), ядро libv2m.so — этап 2.
plugins {
    id("com.android.application")
    kotlin("android")
}

// Номер билда — единое место определения в app/gradle.properties (оттуда же
// его берёт Build.kt в :shared, см. generateBuild).
val v2mBuild = (project.findProperty("v2m.build") as String).toInt()

android {
    namespace = "com.v2m.app.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.v2m.app"
        minSdk = 26
        targetSdk = 36
        versionCode = v2mBuild
        versionName = "1.0.0"
        // Целевое устройство — arm64 (план, §6 п.2): отсекает .so прочих ABI,
        // которые тянет AAR ONNX Runtime.
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    compileSdkMinor = 1
    buildToolsVersion = "36.1.0"
    ndkVersion = "27.0.12077973"

    packaging {
        jniLibs {
            // libonnxruntime4j_jni.so — JNI Java-API ONNX Runtime: не используется
            // (ядро работает через C++ API) и собран с выравниванием 4 КБ,
            // из-за чего Android Studio ругалась на политику 16 КБ страниц.
            excludes += "**/libonnxruntime4j_jni.so"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    // ComponentActivity и контракты выбора файлов (этап 4г): SAF требует
    // ActivityResultRegistry, поэтому площадка выбора — в приложении, а
    // реализация контрактов :shared их вызывает через AndroidHost.
    implementation("androidx.activity:activity-compose:1.9.3")
}
