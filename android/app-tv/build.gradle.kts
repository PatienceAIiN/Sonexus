plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.sonex.tv"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.sonex.tv"
        minSdk = 26
        targetSdk = 34
        versionCode = 41
        versionName = "5.1"
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
        release { signingConfig = signingConfigs.getByName("release") }
    }
}
dependencies {
    implementation(project(":core"))
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("junit:junit:4.13.2")
}
