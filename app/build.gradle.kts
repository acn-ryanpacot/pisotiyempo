plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Crash reporting turns on only when Firebase's config file is present (Firebase console > Project settings),
// so builds without it, including CI, still work.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

android {
    namespace = "com.pisotiyempo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pisotiyempo"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        val apiBaseUrl = providers.gradleProperty("PISO_API_BASE_URL").get()
        val appKey = providers.gradleProperty("PISO_APP_KEY").get()
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "APP_KEY", "\"$appKey\"")
        // Google Cloud project number linked to the app in Play Console. 0 turns Play Integrity off.
        val cloudProject = providers.gradleProperty("PISO_CLOUD_PROJECT_NUMBER").getOrElse("0")
        buildConfigField("long", "CLOUD_PROJECT_NUMBER", "${cloudProject}L")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(libs.play.integrity)
    implementation(libs.billing)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
