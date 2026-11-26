plugins {
    id("com.android.application")
}

android {
    namespace = "com.kunukuntla.gowthamadmin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kunukuntla.gowthamadmin"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "1.0.$versionCode"
        fun setting(prop: String, env: String, default: String) =
            ((project.findProperty(prop) as String?) ?: System.getenv(env))?.takeIf { it.isNotBlank() } ?: default
        val supabaseUrl = setting("supabaseUrl", "SUPABASE_URL", "https://qzzcpuvcrhbasnnsfvgt.supabase.co")
        val supabaseKey = setting("supabaseKey", "SUPABASE_ANON_KEY",
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InF6emNwdXZjcmhiYXNubnNmdmd0Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3Njk1NDc2MTgsImV4cCI6MjA4NTEyMzYxOH0._MAMf4jVmNBarBLM1nSKdbjeq7lxRUjfQCnf5cxK4X8")
        buildConfigField("String", "SUPABASE_URL", "\"${supabaseUrl.trim()}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${supabaseKey.trim()}\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
