import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("com.android.application")
}

kotlin {
    jvm("desktop")

    androidTarget()

    wasmJs {
        browser()
        binaries.executable()
    }

    // iOS (device + Apple-silicon simulator). Static framework consumed by frontend/kmp/iosApp (Xcode, generated
    // with xcodegen). Kotlin/Native iOS targets only build on macOS; other hosts skip them with a warning.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                implementation("io.ktor:ktor-client-core:3.3.3")
                implementation("io.ktor:ktor-client-content-negotiation:3.3.3")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.3")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("io.ktor:ktor-client-cio:3.3.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            }
        }
        val wasmJsMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-js:3.3.3")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-android:3.3.3")
                implementation("androidx.activity:activity-compose:1.9.3")
            }
        }
        // Convention accessor: iosMain comes from the default hierarchy template (created after the targets).
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.3.3")
        }
    }
}

android {
    namespace = "com.lauzhauk.alt"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lauzhauk.alt"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
}

compose.resources {
    publicResClass = true
}

compose.desktop {
    application {
        mainClass = "alt.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "ALT"
            packageVersion = "1.0.0"
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

tasks.register<JavaExec>("smokeSearch") {
    group = "verification"
    description = "Headless API smoke test against the deployed backend"
    dependsOn("desktopMainClasses")
    mainClass.set("alt.SmokeKt")
    val comp = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = comp.output.allOutputs + comp.runtimeDependencyFiles
}
