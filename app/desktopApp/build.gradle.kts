import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    sourceSets {
        val desktopMain by getting
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(project(":shared"))
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.v2m.app.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb)
            packageName = "v2m"
            packageVersion = "1.0.0"
        }
    }
}

// Point JNI at the built libv2m.so (basicpitch/src/libv2m/build)
val v2mNativeDir = rootProject.file("../basicpitch/src/libv2m/build").absolutePath
tasks.withType<JavaExec>().configureEach {
    systemProperty("java.library.path", v2mNativeDir)
}
