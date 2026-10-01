import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.trickhook.shadowzap"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.trickhook.shadowzap"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.appVersionCode.get().toInt()
        versionName = libs.versions.appVersionName.get()
    }

    sourceSets {
        named("main") {
            kotlin.directories += "src/main/kotlin"
        }
        named("debug") {
            kotlin.directories += "src/debug/kotlin"
        }
        named("release") {
            kotlin.directories += "src/release/kotlin"
        }
        named("test") {
            kotlin.directories += "src/test/kotlin"
        }
        named("testDebug") {
            kotlin.directories += "src/testDebug/kotlin"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.appJvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.appJvmTarget.get())
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += listOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "META-INF/*.version",
                "META-INF/INDEX.LIST",
            )
        }
    }

    testOptions {
        // Kernel code logs through android.util.Log; stubs must return defaults in JVM tests.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Versions are pinned on purpose in gradle/libs.versions.toml.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
        // The module APK itself stores no data (allowBackup is off); nothing to describe.
        disable += "DataExtractionRules"
    }
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.appJvmTarget.get()))
    }
}

dependencies {
    implementation(project(":api"))
    // Api100Entry for LSPosed 1.9.x / Vector <= 2.0; boots through entry/Api100BootImpl.
    implementation(project(":compat-api100"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Provided by the Xposed framework at runtime.
    compileOnly(libs.xposed.api)
    compileOnly(libs.libxposed.api)

    testImplementation(libs.kotlin.test.junit)
}
