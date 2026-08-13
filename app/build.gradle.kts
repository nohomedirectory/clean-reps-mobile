plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.vaylith.cleanrepsmobile"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.vaylith.cleanrepsmobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-sprint"
        buildConfigField("String", "CHALLENGE_API_BASE_URL", quoted(providers.gradleProperty("challengeApiBaseUrl").orElse("").get()))
        buildConfigField("String", "MEDIAMTX_SRT_HOST", quoted(providers.gradleProperty("mediaMtxSrtHost").orElse("").get()))
        buildConfigField("String", "MEDIAMTX_SRT_PASSPHRASE", quoted(providers.gradleProperty("mediaMtxSrtPassphrase").orElse("").get()))
        buildConfigField("String", "MEDIAMTX_STREAM_PATH", "\"million-kicks-camera\"")
    }
    buildFeatures { compose = true; buildConfig = true }
}

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.video)
    implementation(libs.camerax.view)
    debugImplementation("androidx.compose.ui:ui-tooling")
}
