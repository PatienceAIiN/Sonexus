plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.sonex.mobile"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.sonex.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "2.1"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("sonex-release.keystore")
            storePassword = System.getenv("SONEX_KEYSTORE_PASS") ?: "sonex-release"
            keyAlias = "sonex"
            keyPassword = System.getenv("SONEX_KEYSTORE_PASS") ?: "sonex-release"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    // Native libs (ONNX, TFLite, Vosk) dominate size; split per ABI for release.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }
}
dependencies {
    implementation(project(":core"))
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    // Phase 2: on-device ML (models are OTA data files, these are just runtimes)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    // Phase 7: Cast output target
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    testImplementation("junit:junit:4.13.2")
}
