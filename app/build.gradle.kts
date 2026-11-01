plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.flowshare.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.flowshare.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }
    }

    // A shared development key is committed so every build (local or GitHub Actions)
    // is signed identically and can be installed over the previous one.
    // Replace with your own private key before publishing to an app store.
    signingConfigs {
        create("flow") {
            storeFile = rootProject.file("keystore/flowshare-debug.jks")
            storePassword = "flowshare"
            keyAlias = "flowshare"
            keyPassword = "flowshare"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("flow")
        }
        release {
            // Release builds run the animations much more smoothly than debug builds.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("flow")
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
        compose = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Nearby Connections: Bluetooth / BLE discovery + Wi-Fi Direct / hotspot bandwidth upgrade
    implementation("com.google.android.gms:play-services-nearby:19.3.0")

    // QR pairing
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
