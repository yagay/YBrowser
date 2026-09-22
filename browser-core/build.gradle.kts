plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

group = "com.yagay.ybrowser"
version = "0.1.0"

android {
    namespace = "com.yagay.browsercore"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api("org.mozilla.geckoview:geckoview:155.0.20260903215306")
}
