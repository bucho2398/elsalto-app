plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI passes the run number, so every build installs over the previous one.
val buildNumber = (System.getenv("BUILD_NUMBER") ?: "1").toInt()

// Only the official release pipeline sets ELSALTO_OFFICIAL=1. Every other build (yours, a fork's,
// the public CI's) gets its own application id and name, so it installs next to the official app
// instead of fighting it for the same id. Forks that publish APKs should pick their own id.
val official = System.getenv("ELSALTO_OFFICIAL") == "1"

android {
    namespace = "io.github.bucho2398.elsalto"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.bucho2398.elsalto"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        if (!official) {
            applicationIdSuffix = ".foss"
            versionNameSuffix = "-foss"
        }
        resValue("string", "app_name", if (official) "El Salto" else "El Salto FOSS")
        resValue("string", "widget_name", if (official) "El Salto conditions" else "El Salto FOSS conditions")
    }

    // The release key comes from repository secrets (see .github/workflows/build.yml).
    val keystore = System.getenv("SIGNING_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    // theCrag walls and terrain, refreshed by hand (see README).
    sourceSets["main"].assets.srcDirs("../data")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests; the real one for the model test.
    testImplementation("org.json:json:20240303")
}
