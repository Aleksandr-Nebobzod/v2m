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
