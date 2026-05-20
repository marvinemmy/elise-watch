plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lnsgroup.elise.watch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lnsgroup.elise.watch"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "API_BASE_URL", "\"https://lnsgroup.dev\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "API_BASE_URL", "\"https://lnsgroup.dev\"")
        }
        debug {
            buildConfigField("String", "API_BASE_URL", "\"https://lnsgroup.dev\"")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Wear OS
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // WebSocket (communication avec Ã‰lise)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // TFLite (wake word "Ok Ã‰lise")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
}
