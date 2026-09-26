plugins {
    id("com.android.application")
    // The Checkbox Ticker part (package com.example.checkboxticker) is written in Kotlin.
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kunukuntla.dropdownpicker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kunukuntla.dropdownpicker"
        minSdk = 26
        targetSdk = 35
        // CI passes the run number so every build is a newer version that installs over the last one.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "1.0.$versionCode"
    }

    signingConfigs {
        // Fixed debug key committed to the repo so every build has the same signature.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
            // Strip unused library code so the APK stays small.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // On-device text reading (OCR), bundled offline: the recognition model ships inside
    // the APK, so it works on first launch with no internet and no Google Play services
    // dependency (unlike the play-services-mlkit-text-recognition variant, which downloads
    // the model on demand). Adds roughly 15-20 MB to the APK.
    implementation("com.google.mlkit:text-recognition:16.0.0")
    // The Checkbox Ticker's box-look checks run as unit tests.
    testImplementation("junit:junit:4.13.2")
}
