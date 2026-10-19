plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.reelect.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.scrollsense.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "1.4"
    }
    // Fixed signing key so every build can be installed over the previous one
    val fixedKey = file("scrollsense-debug.keystore")
    if (fixedKey.exists()) {
        signingConfigs.getByName("debug") {
            storeFile = fixedKey
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
