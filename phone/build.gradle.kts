plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lnsgroup.elise.companion"
    compileSdk = 34

    val buildNum = (project.findProperty("versionCode") as String?)?.toInt() ?: 1

    defaultConfig {
        applicationId = "com.lnsgroup.elise.companion"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNum
        versionName = "1.0.$buildNum"
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
    wearApp(project(":app"))

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
