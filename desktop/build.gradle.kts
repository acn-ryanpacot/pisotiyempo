import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

version = "1.0.0"

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// The backend address and app key are baked in at build time, like the Android app's BuildConfig.
// Set PISO_API_BASE_URL / PISO_APP_KEY in ~/.gradle/gradle.properties; they can also be changed later in
// the app's config file.
val generateBuildInfo by tasks.registering {
    val baseUrl = providers.gradleProperty("PISO_API_BASE_URL")
    val appKey = providers.gradleProperty("PISO_APP_KEY")
    val version = project.version.toString()
    val out = layout.buildDirectory.dir("generated/buildinfo")
    inputs.property("baseUrl", baseUrl)
    inputs.property("appKey", appKey)
    outputs.dir(out)
    doLast {
        val file = out.get().file("com/pisotiyempo/desktop/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.pisotiyempo.desktop
            |
            |internal object BuildInfo {
            |    const val API_BASE_URL = "${baseUrl.get()}"
            |    const val APP_KEY = "${appKey.get()}"
            |    const val VERSION = "$version"
            |}
            |""".trimMargin(),
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateBuildInfo)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.coroutines.swing)
    implementation(libs.sqlite.jdbc)
    implementation(libs.zxing.core)
    implementation(libs.jna.platform)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "com.pisotiyempo.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "PisoTiyempo"
            packageVersion = "1.0.0"
            description = "PSE charts, patterns, AI analysis and portfolio, synced with your phone"
            vendor = "Piso Tiyempo"
            // Trimmed Java runtime: jdk.httpserver backs the dashboard feed, java.sql the local database,
            // jdk.crypto.ec modern TLS, jdk.unsupported libraries that use sun.misc.Unsafe.
            modules("java.sql", "jdk.httpserver", "jdk.crypto.ec", "java.naming", "jdk.unsupported")
            windows {
                menuGroup = "Piso Tiyempo"
                shortcut = true
                perUserInstall = true
                // Keep this constant across versions so new installers upgrade the old install.
                upgradeUuid = "4f9a2d1e-7c3b-4e8a-9b6d-2a1f5c8e7d30"
            }
        }
    }
}
