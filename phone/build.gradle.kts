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

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // CameraX
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // ML Kit — reconnaissance faciale + scan QR WiFi
    implementation("com.google.mlkit:face-detection:16.1.6")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Google Sign-In + APIs
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.apis:google-api-services-gmail:v1-rev20220404-2.0.0")
    implementation("com.google.apis:google-api-services-calendar:v3-rev20220715-2.0.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20220815-2.0.0")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.34.1")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")

    // Wearable Data Layer — proxy Bluetooth watch→phone→serveur
    implementation("com.google.android.gms:play-services-wearable:18.1.0")
}
