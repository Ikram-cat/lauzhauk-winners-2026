import groovy.json.JsonOutput
import java.net.URI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val apiBaseUrl = providers.gradleProperty("apiBaseUrl")
    .orElse(providers.environmentVariable("API_BASE_URL"))
    .orElse("")
    .map { it.trim().trimEnd('/') }
    .get()

if (apiBaseUrl.isNotEmpty()) {
    val origin = runCatching { URI(apiBaseUrl) }.getOrNull()
    require(
        origin?.scheme in listOf("http", "https") && origin?.host != null &&
            origin.rawUserInfo == null && origin.rawPath.isNullOrEmpty() &&
            origin.rawQuery == null && origin.rawFragment == null
    ) { "API_BASE_URL must be the public HTTP or HTTPS origin, without credentials, a path, or a query." }
}

android {
    namespace = "planb.androidapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "ch.lauzhack.planb"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "API_BASE_URL", JsonOutput.toJson(apiBaseUrl))
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.foundation:foundation:1.9.4")
}
