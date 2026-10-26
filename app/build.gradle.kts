plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.visualagent.hyperos"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.visualagent.hyperos"
        minSdk = 31
        // 适配 Android 16 (API 36) / HyperOS 3
        targetSdk = 36
        versionCode = 100
        versionName = "1.0.0-hyperos3"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // 适配 Android 16 16KB 页面对齐与 Xiaomi HyperOS 3 安装校验
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Kotlin Coroutines & Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // OkHttp (Gemini Computer Use REST & Gemini Live API WebSocket)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Shizuku API (官方 & yangFenTuoZi Android 16 兼容 API)
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")
}
