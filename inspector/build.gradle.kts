plugins {
    id("com.android.application")
}

android {
    namespace = "com.kunukuntla.a11yinspector"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kunukuntla.a11yinspector"
        minSdk = 26
        targetSdk = 35
        // CI passes the run number so every build installs over the last one.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "1.0.$versionCode"
        // Supabase sign-in: the project's URL and anon (public) key, from CI's secrets.
        // (The anon key is public by design - Supabase's row-level security protects the data.)
        fun setting(prop: String, env: String, default: String) =
            ((project.findProperty(prop) as String?) ?: System.getenv(env))?.takeIf { it.isNotBlank() } ?: default
        val supabaseUrl = setting("supabaseUrl", "SUPABASE_URL", "https://qzzcpuvcrhbasnnsfvgt.supabase.co")
        val supabaseKey = setting("supabaseKey", "SUPABASE_ANON_KEY",
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InF6emNwdXZjcmhiYXNubnNmdmd0Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3Njk1NDc2MTgsImV4cCI6MjA4NTEyMzYxOH0._MAMf4jVmNBarBLM1nSKdbjeq7lxRUjfQCnf5cxK4X8")
        buildConfigField("String", "SUPABASE_URL", "\"${supabaseUrl.trim()}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${supabaseKey.trim()}\"")
    }

    buildFeatures {
        buildConfig = true
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


dependencies {
    testImplementation("junit:junit:4.13.2")
}
