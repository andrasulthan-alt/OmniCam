plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.omnicam"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.omnicam"
        minSdk = 30          // Android 11+: zoom ratio API, MediaStore relative path, modern Camera2
        targetSdk = 37
        versionCode = 13
        versionName = "0.3.8"
        // Real phones only: drops the x86/x86_64 emulator copies of CameraX's small native helper.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Permanent release key, supplied by CI from GitHub Secrets (never committed to the repo).
    // With the same key every release, updates install over the previous version.
    val keystorePath: String? = System.getenv("OMNICAM_KEYSTORE_PATH")
    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("OMNICAM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OMNICAM_KEY_ALIAS")
                keyPassword = System.getenv("OMNICAM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8: removes unused code from Compose/CameraX and optimizes the rest (much smaller, faster APK)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".dev"
        }
    }

    buildFeatures {
        compose = true
    }

    // Do not embed the Google-encrypted dependency metadata block (not needed; F-Droid friendly).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    implementation(libs.bundles.camerax)
    implementation(libs.zxing.core)   // Apache-2.0, tanpa Google Play Services
}
