plugins { id("com.android.application") }
android {
    namespace = "com.kunukuntla.engine"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kunukuntla.engine"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        // No minify: the loaded classes must keep their names so the shell can find EngineImpl.
        getByName("debug") { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    // Shared only at compile time: at runtime the interface comes from the shell (the parent loader).
    compileOnly(project(":engineapi"))
}
