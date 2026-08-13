plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

fun secret(gradleProperty: String, environmentVariable: String) =
    providers.gradleProperty(gradleProperty)
        .orElse(providers.environmentVariable(environmentVariable))
        .orElse("")
        .get()

android {
    namespace = "com.vaylith.cleanrepsmobile"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.vaylith.cleanrepsmobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-sprint"
        buildConfigField("String", "CHALLENGE_API_BASE_URL", quoted(secret("challengeApiBaseUrl", "CHALLENGE_API_BASE_URL")))
        buildConfigField("String", "MEDIAMTX_SRT_HOST", quoted(secret("mediaMtxSrtHost", "MEDIAMTX_SRT_HOST")))
        buildConfigField("String", "MEDIAMTX_SRT_PASSPHRASE", quoted(secret("mediaMtxSrtPassphrase", "MEDIAMTX_SRT_PASSPHRASE")))
        buildConfigField("String", "MEDIAMTX_PUBLISH_PASSWORD", quoted(secret("mediaMtxPublishPassword", "MEDIAMTX_PUBLISH_PASSWORD")))
        buildConfigField("String", "MEDIAMTX_STREAM_PATH", "\"million-kicks-camera\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
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
    implementation(libs.root.encoder)
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation(libs.junit)
}
