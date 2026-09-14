plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

fun secret(environmentVariable: String) =
    providers.environmentVariable(environmentVariable)
        .orElse("")
        .get()

android {
    namespace = "com.vaylith.cleanrepsmobile"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.vaylith.cleanrepsmobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-rehearsal"
        buildConfigField("String", "CHALLENGE_API_BASE_URL", quoted(secret("CHALLENGE_API_BASE_URL")))
        buildConfigField("String", "MEDIAMTX_SRT_HOST", quoted(secret("MEDIAMTX_SRT_HOST")))
        buildConfigField("String", "MEDIAMTX_SRT_PASSPHRASE", quoted(secret("MEDIAMTX_SRT_PASSPHRASE")))
        buildConfigField("String", "MEDIAMTX_PUBLISH_PASSWORD", quoted(secret("MEDIAMTX_PUBLISH_PASSWORD")))
        buildConfigField("String", "MEDIAMTX_STREAM_PATH", "\"million-kicks-camera\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        // Keep Kotlin and Java bytecode targets aligned. Leaving Kotlin's
        // target implicit can fail Gradle's JVM target validation.
        jvmTarget = JavaVersion.VERSION_17.toString()
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
    implementation(libs.root.encoder) {
        // SrtStream is implemented by RootEncoder's library module. Do not
        // package transports that the single canonical SRT publisher cannot
        // use; MediaMTX handles all server-side fan-out.
        exclude(group = "com.github.pedroSG94.RootEncoder", module = "rtmp")
        exclude(group = "com.github.pedroSG94.RootEncoder", module = "rtsp")
        exclude(group = "com.github.pedroSG94.RootEncoder", module = "udp")
    }
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation(libs.junit)
}
