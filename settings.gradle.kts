pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PisoTiyempo"
// core: plain-Kotlin engine, backend client and sync, shared by the Android app and the desktop app.
include(":core", ":app", ":desktop")
