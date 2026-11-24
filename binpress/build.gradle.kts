plugins {
    id("com.android.application")
}

android {
    namespace = "com.kunukuntla.binpress"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kunukuntla.binpress"
        minSdk = 26
        targetSdk = 35
        // CI passes the run number so every build installs over the last one.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "1.0.$versionCode"
    }

    signingConfigs {
        // One fixed debug key, so updates install over the old build.
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
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
