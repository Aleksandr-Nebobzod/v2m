import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
            implementation(compose.components.resources)
            implementation(project(":shared"))
        }
    }
}

// Ресурсы и класс Res (package com.v2m.app.resources) — в :shared (этап 4а
// плана docs/260921_android_plan.md); здесь остаётся только доступ к ним.

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

// --- Portable-комплект: uber-jar + нативные библиотеки ------------------------
// Одна точка определения упаковки — работает локально и в автосборке
// (.github/workflows/desktop.yml):
//   ./gradlew :desktopApp:packagePortable -Pv2m.ort.dir=<каталог ONNX Runtime>
// Собирает build/compose/portable/v2m-<билд>-<os>-<arch>/ (jar, libv2m,
// ONNX Runtime, скрипт запуска, README) и архив .zip рядом. Для Windows
// -Pv2m.ort.dir обязателен (lib/onnxruntime.dll из релиза ONNX Runtime); на
// Linux по умолчанию берётся системный пакет. Android в комплект не входит.

val v2mIsWindows = System.getProperty("os.name").startsWith("Windows")
val v2mArch = when (System.getProperty("os.arch")) {
    "amd64", "x86_64" -> "x64"
    "aarch64" -> "arm64"
    else -> System.getProperty("os.arch")
}
val v2mOsName = if (v2mIsWindows) "windows" else "linux"
val v2mBundle = "v2m-${project.property("v2m.build")}-$v2mOsName-$v2mArch"
val v2mJarName = if (v2mIsWindows) "v2m.dll" else "libv2m.so"
// Берём версионированные файлы (libonnxruntime.so.1.21.0): имя совпадает с
// SONAME, который libv2m просит у загрузчика; ссылки копируются содержимым.
val v2mOrtPattern = if (v2mIsWindows) "onnxruntime*.dll" else "libonnxruntime.so.*"
val v2mOrtDir = providers.gradleProperty("v2m.ort.dir").map { file(it) }
    .orElse(if (v2mIsWindows) file("нет-каталога-ONNX-Runtime") else file("/usr/lib/x86_64-linux-gnu"))

/** Упаковка portable-комплекта: содержимое (jar + нативные) и архив .zip. */
abstract class PackagePortableTask : DefaultTask() {
    @get:InputFiles abstract val uberJarDir: ConfigurableFileCollection
    @get:InputFile abstract val v2mNative: RegularFileProperty
    @get:InputFiles abstract val ortLibs: ConfigurableFileCollection
    @get:Input abstract val bundleName: Property<String>
    @get:Input abstract val runScriptName: Property<String>
    @get:Input abstract val runScriptText: Property<String>
    @get:Input abstract val readmeText: Property<String>
    @get:OutputDirectory abstract val portableDir: DirectoryProperty
    @get:OutputFile abstract val zipFile: RegularFileProperty

    @TaskAction
    fun pack() {
        val dir = portableDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        val jars = uberJarDir.asFileTree.matching { include("*.jar") }.files
        check(jars.size == 1) { "ожидался один uber-jar, найдено ${jars.size}: $jars" }
        val jarName = jars.single().name
        jars.single().copyTo(dir.resolve(jarName), overwrite = true)
        v2mNative.get().asFile.copyTo(dir.resolve(v2mNative.get().asFile.name), overwrite = true)
        val found = ortLibs.files
        check(found.isNotEmpty()) { "ONNX Runtime не найден: укажите -Pv2m.ort.dir=<каталог>" }
        // В Linux-релизах ONNX Runtime лежит цепочкой ссылок (libonnxruntime.so.1
        // → libonnxruntime.so.1.21.0), а загрузчику нужно ровно имя SONAME из
        // DT_NEEDED — кратчайшее версионированное (libonnxruntime.so.<major>).
        // Поэтому на библиотеку (по реальному пути) копируем один файл под этим
        // именем: иначе в комплект попадали бы две копии по 20 МБ. Проверка —
        // разрешение зависимостей libv2m (ldd) в автосборке (.github/workflows/desktop.yml).
        val libs = found.groupBy { it.toPath().toRealPath() }.map { (_, same) ->
            same.minBy { it.name.length }
        }
        libs.forEach { it.copyTo(dir.resolve(it.name), overwrite = true) }
        dir.resolve(runScriptName.get()).apply {
            writeText(runScriptText.get().replace("\${JAR}", jarName))
            setExecutable(true)
        }
        dir.resolve("README.txt").writeText(readmeText.get())
        zipFile.get().asFile.also { it.parentFile.mkdirs() }.outputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                dir.listFiles()!!.sortedBy { it.name }.forEach { f ->
                    zip.putNextEntry(ZipEntry("${bundleName.get()}/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        logger.lifecycle("portable: ${dir.absolutePath}")
        logger.lifecycle("portable: ${zipFile.get().asFile.absolutePath}")
    }
}

val packagePortable = tasks.register<PackagePortableTask>("packagePortable") {
    group = "distribution"
    description = "Portable-комплект (uber-jar + libv2m + ONNX Runtime) и архив .zip"
    dependsOn("packageUberJarForCurrentOS")
    // Каталог uber-jar задаёт плагин Compose (build/compose/jars, имя
    // <packageName>-<os>-<arch>-<version>.jar) — имя не фиксируем.
    uberJarDir.from(layout.buildDirectory.dir("compose/jars"))
    v2mNative.set(file("$v2mNativeDir/$v2mJarName"))
    ortLibs.from(fileTree(v2mOrtDir) { include(v2mOrtPattern) })
    bundleName.set(v2mBundle)
    runScriptName.set(if (v2mIsWindows) "v2m.bat" else "v2m.sh")
    runScriptText.set(
        if (v2mIsWindows) """
            |@echo off
            |rem Запуск portable-комплекта v2m ($v2mBundle). Нужна Java 17+ в PATH.
            |rem v2m.dll и onnxruntime.dll лежат рядом: каталог комплекта задаётся
            |rem java.library.path (поиск v2m.dll) и добавляется в PATH (зависимости
            |rem v2m.dll — Windows ищет их в каталоге приложения и в PATH).
            |setlocal
            |set "DIR=%~dp0"
            |if "%DIR:~-1%"=="\" set "DIR=%DIR:~0,-1%"
            |set "PATH=%DIR%;%PATH%"
            |java -Djava.library.path="%DIR%" -jar "%DIR%\${'$'}{JAR}" %*
        """.trimMargin() + "\n"
        else """
            |#!/bin/sh
            |# Запуск portable-комплекта v2m ($v2mBundle). Нужна Java 17+ в PATH.
            |# libv2m.so и libonnxruntime.so лежат рядом: путь к каталогу задаётся
            |# java.library.path, а libonnxruntime.so libv2m.so находит сам (RUNPATH ${'$'}ORIGIN).
            |set -e
            |DIR=${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}0")" && pwd)
            |exec java -Djava.library.path="${'$'}DIR" -jar "${'$'}DIR/${'$'}{JAR}" "${'$'}@"
        """.trimMargin() + "\n"
    )
    readmeText.set(
        """
        |v2m — транскрипция аудио в MIDI (portable-комплект $v2mBundle)
        |
        |Требуется Java 17 или новее в PATH (проверено на OpenJDK 21).
        |
        |Запуск:
        |  Linux:   ./v2m.sh        (если нет права запуска: bash v2m.sh)
        |  Windows: v2m.bat
        |
        |Состав: uber-jar приложения, нативная библиотека транскрипции,
        |ONNX Runtime, скрипт запуска. Ничего доустанавливать не нужно.
        |
        |Данные пользователя (настройки, пресеты, журнал, записи):
        |  Linux:   ${'$'}HOME/.v2m
        |  Windows: %USERPROFILE%\.v2m
        |
        |Микрофон: Linux — захват через ALSA; в Windows-сборке захвата нет
        |(WASAPI не реализован), запись сообщит о недоступности.
        |
        |Версия сборки: ${project.property("v2m.build")}
        """.trimMargin() + "\n"
    )
    portableDir.set(layout.buildDirectory.dir("compose/portable/$v2mBundle"))
    zipFile.set(layout.buildDirectory.file("compose/portable/$v2mBundle.zip"))
}
