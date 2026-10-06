plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.deepseeklover.landrop"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.deepseeklover.landrop"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "0.1.2"
    }
    buildTypes {
        release { optimization { enable = false } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
