plugins {
    id("com.android.application")
}

android {
    namespace = "com.kunukuntla.tickcheck"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kunukuntla.tickcheck"
        minSdk = 26
        targetSdk = 35
        // CI passes the run number so every build installs over the last one.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "1.0.$versionCode"
    }

    signingConfigs {
        // The same fixed debug key as the main app, so updates install over the old one.
        getByName("debug") {
            storeFile = file("../app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

