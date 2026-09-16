plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.anjungan.kiosk"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anjungan.kiosk"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("anjungan") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "anjungan123"
            keyAlias = "anjungan"
            keyPassword = "anjungan123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("anjungan")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("anjungan")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
