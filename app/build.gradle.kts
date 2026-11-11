plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ounan.knowledge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ounan.secondbrain"
        minSdk = 26
        targetSdk = 35
        versionCode = 31
        versionName = "0.5.3.1"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // Bundled model: OCR runs on the phone; no Google Play Services download required.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
