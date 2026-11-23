import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing key (kept in keystore/, which is git-ignored: keep it private and backed up).
// Keep it: updates must be signed with the same key to install over the old app.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.autotube.car"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.autotube.car"
        minSdk = 29
        targetSdk = 34
        versionCode = 5
        versionName = "2.2"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Unofficial Android Auto SDK (CarActivity): the only way to draw our own full-screen UI
    // (a WebView) on the car display. Same build of the library Fermata Auto ships.
    implementation(files("libs/aauto.aar"))
    // What that SDK needs at runtime.
    implementation("androidx.fragment:fragment:1.6.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.core:core:1.12.0")
    // Phone-only setup: Shizuku (a separate app started with Wireless debugging) lets AutoTube run
    // the one adb-shell command that records the Play Store install, without a computer.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
