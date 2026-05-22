plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lnsgroup.elise.watch"
    compileSdk = 34

    val buildNum = (project.findProperty("versionCode") as String?)?.toInt() ?: 1

    defaultConfig {
        applicationId = "com.lnsgroup.elise.watch"
        minSdk = 30
        targetSdk = 34
        versionCode = buildNum
        versionName = "1.0.$buildNum"
        buildConfigField("String", "API_BASE_URL", "\"https://lnsgroup.dev\"")
    }

    signingConfigs {
        create("elise") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: rootProject.file("elise-release.keystore"))
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "elise2026"
            keyAlias = System.getenv("KEY_ALIAS") ?: "elise"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "elise2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("elise")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "API_BASE_URL", "\"https://lnsgroup.dev\"")
        }
        debug {
            signingConfig = signingConfigs.getByName("elise")
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

    // WebSocket (communication avec Élise)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // TFLite (wake word "Ok Élise")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // Wearable Data Layer — proxy Bluetooth phone↔watch
    implementation("com.google.android.gms:play-services-wearable:18.1.0")
}
