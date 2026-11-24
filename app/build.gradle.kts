plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "io.executionbridge.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.executionbridge.android"
        if (providers.gradleProperty("bridgeSideBySide").orNull == "true") {
            applicationId = "io.executionbridge.android.challenge"
        }
        manifestPlaceholders["bridgeLabel"] = if (providers.gradleProperty("bridgeSideBySide").orNull == "true") "Execution Bridge Test" else "Execution Bridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.2.3-dev"
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
