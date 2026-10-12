import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Shared versioning (version.properties) + release signing, see gradle/android-common.gradle
apply(from = rootProject.file("gradle/android-common.gradle"))

android {
    namespace = "x.vladgba.sendanyway"
    compileSdk = 35

    defaultConfig {
        applicationId = "x.vladgba.sendanyway"
        minSdk = 24
        targetSdk = 35
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // The only runtime library: QR encode/decode.
    implementation("com.google.zxing:core:3.5.3")

    // Unit tests only (not shipped in the APK).
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests (android.jar only has stubs); P2P integration test.
    testImplementation("org.json:json:20240303")
}
