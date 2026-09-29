plugins {
    kotlin("multiplatform") version "2.1.0" apply false
    kotlin("android") version "2.1.0" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    // Android-сборка (план docs/260921_android_plan.md, этап 1). AGP 8.13 требует
    // Gradle не ниже 8.13 (у проекта ровно 8.13) и JDK 17+; рекомендуемый NDK — 27.0.12077973.
    id("com.android.application") version "8.13.0" apply false
    id("com.android.library") version "8.13.0" apply false
}

// Версия продукта — две формы одного значения. Единое место определения —
// v2m.version (мажор и минор) и v2m.build в app/gradle.properties; обе формы
// собираются здесь и раздаются подпроектам.
//   semver (1.0.60)  — номер билда без ведущих нулей: packageVersion
//                      (:desktopApp), где поля версии обязаны быть числами;
//   padded (1.0.060) — тот же номер тремя знаками: имена файлов (комплект,
//                      uber-jar), versionName (:androidApp), README.txt и тег
//                      релиза — ведущие нули здесь допустимы и выравнивают
//                      номера билдов (Т03 п.7).
val v2mBuildNumber = (property("v2m.build") as String).toInt()
val v2mSemverVersion = "${property("v2m.version")}.$v2mBuildNumber"
val v2mPaddedVersion = "${property("v2m.version")}.${v2mBuildNumber.toString().padStart(3, '0')}"

allprojects {
    extra["v2mVersion"] = v2mSemverVersion
    extra["v2mVersionPadded"] = v2mPaddedVersion
}
