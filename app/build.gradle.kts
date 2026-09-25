plugins {
    id("com.android.application")
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
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
