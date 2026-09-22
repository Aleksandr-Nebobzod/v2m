import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Общий код v2m (план docs/260921_android_plan.md, этапы 1б и 2; Compose —
// этап 4а). Таргеты: desktop-JVM (модуль :desktopApp) и Android (:androidApp).
plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ONNX Runtime для Android берётся готовым AAR (план, §3, вариант A):
// заголовки — вендоренные той же версии 1.21.0, libonnxruntime.so — из AAR.
// AAR линкуется в libv2m.so и попадает в APK как зависимость.
val ortVersion = "1.21.0"
val ortAar by configurations.creating
val unpackOrt by tasks.registering(Copy::class) {
    from(ortAar.map { zipTree(it) }) {
        include("jni/arm64-v8a/libonnxruntime.so")
    }
    into(layout.buildDirectory.dir("ort"))
}
tasks.matching { it.name.contains("CMake") }.configureEach { dependsOn(unpackOrt) }

// Номер билда (единое место — app/gradle.properties): кодогенерация Build.kt
// в commonMain. Потребители — «О программе» (App.kt), заголовок окна (Main.kt),
// журнал (Log.kt); versionCode :androidApp читает то же свойство.
val v2mBuild = (project.findProperty("v2m.build") as String).toInt()
val generateBuild by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/build/kotlin")
    inputs.property("build", v2mBuild)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("com/v2m/app/Build.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.v2m.app
            |
            |/** Номер билда = номер записи в docs/history.md, описывающей этот билд
            | *  (записи идут подзаголовками с датой/временем, см. «Ход работ»).
            | *  Файл сгенерирован задачей generateBuild (:shared) из значения
            | *  v2m.build в app/gradle.properties — там единое место определения
            | *  (его же читает versionCode :androidApp). Показывается в «О программе»
            | *  (App.kt), попадает в заголовок окна (Main.kt) и в журнал (Log.kt). */
            |const val BUILD = $v2mBuild
            |
            """.trimMargin()
        )
    }
}

kotlin {
    jvm()
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        val commonMain by getting
        // Build.kt приходит из generateBuild (srcDir от задачи добавляет и
        // зависимость сборки от неё — gradle task dependency inference).
        commonMain.kotlin.srcDir(generateBuild)
        commonMain.dependencies {
            // api — Compose-типы входят в публичные сигнатуры общего UI
            // (App(), NoteChart, SpectrogramView) и нужны потребителям.
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material)
            api(compose.ui)
            api(compose.components.resources)
        }
        // Промежуточный набор: код, общий для JVM и Android, но не для future-
        // таргетов (java.nio, System.loadLibrary). commonMain ← jvmShared ← jvmMain/androidMain.
        val jvmShared by creating { dependsOn(commonMain) }
        jvmMain { dependsOn(jvmShared) }
        androidMain {
            dependsOn(jvmShared)
            dependencies {
                implementation("com.microsoft.onnxruntime:onnxruntime-android:$ortVersion")
            }
        }
    }
}

dependencies {
    ortAar("com.microsoft.onnxruntime:onnxruntime-android:$ortVersion@aar")
}

// Ресурсы (иконки, mid-сэмплы) — в общем модуле (этап 4а): Res генерируется
// здесь, потребители (:desktopApp, :androidApp) видят его через api-зависимость.
compose.resources {
    packageOfResClass = "com.v2m.app.resources"
    generateResClass = always
    // Библиотечный модуль по умолчанию генерирует internal Res; потребители
    // (:desktopApp, :androidApp) обращаются к ресурсам — класс публичный.
    publicResClass = true
}

android {
    namespace = "com.v2m.app"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26
        // Целевое устройство — arm64 (план, §6 п.2).
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                val ortLib = layout.buildDirectory.file("ort/jni/arm64-v8a/libonnxruntime.so")
                arguments += "-DV2M_ORT_LIB=${ortLib.get().asFile.absolutePath}"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../../basicpitch/src/libv2m/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Compose в android-таргете общего модуля (этап 4а): общий UI компилируется
    // и для Android.
    buildFeatures {
        compose = true
    }
}
