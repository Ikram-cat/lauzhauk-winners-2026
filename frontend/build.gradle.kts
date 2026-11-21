import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.gradle.kotlin.dsl.withGroovyBuilder

buildscript {
    if (providers.gradleProperty("enableAndroid").orNull == "true") {
        repositories { google(); mavenCentral() }
        dependencies { classpath("com.android.tools.build:gradle:8.11.1") }
    }
}

plugins {
    kotlin("multiplatform") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    kotlin("plugin.compose") version "2.2.21"
    id("org.jetbrains.compose") version "1.9.3"
}

val enableAndroid = providers.gradleProperty("enableAndroid").orNull == "true"
val enableIos = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

if (enableAndroid) {
    apply(plugin = "com.android.library")
    extensions.getByName("android").withGroovyBuilder {
        "setNamespace"("planb.shared")
        "setCompileSdk"(35)
        "defaultConfig" { "setMinSdk"(24) }
    }
}

kotlin {
    jvm("desktop")
    if (enableAndroid) androidTarget()
    if (enableIos) {
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework {
                baseName = "PlanBKit"
                isStatic = true
            }
        }
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig { outputFileName = "planb.js" }
        }
        binaries.executable()
    }

    sourceSets {
        if (enableAndroid) named("androidMain") {
            dependencies { implementation("io.ktor:ktor-client-android:3.3.3") }
        }
        if (enableIos) named("iosMain") {
            dependencies { implementation("io.ktor:ktor-client-darwin:3.3.3") }
        }
        commonMain.dependencies {
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("io.ktor:ktor-client-core:3.3.3")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        wasmJsMain.dependencies {
            implementation("io.ktor:ktor-client-js:3.3.3")
            implementation(npm("@js-joda/timezone", "2.3.0"))
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                implementation("io.ktor:ktor-client-cio:3.3.3")
            }
        }
    }
}

compose.desktop {
    application { mainClass = "planb.DesktopKt" }
}
