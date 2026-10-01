import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.trickhook.shadowzap.api"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdkApi.get().toInt()
    }

    sourceSets {
        named("main") {
            kotlin.directories += "src/main/kotlin"
        }
        named("test") {
            kotlin.directories += "src/test/kotlin"
        }
    }

    compileOptions {
        // Java 17 bytecode keeps the API module buildable by older toolchains.
        sourceCompatibility = JavaVersion.toVersion(libs.versions.apiJvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.apiJvmTarget.get())
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
    explicitApi()
    // Conservative Kotlin metadata and stdlib level for the API module; the app ships its own, newer stdlib.
    coreLibrariesVersion = libs.versions.apiKotlinStdlib.get()
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.apiJvmTarget.get()))
        apiVersion.set(KotlinVersion.fromVersion(libs.versions.apiKotlinLanguage.get()))
        languageVersion.set(KotlinVersion.fromVersion(libs.versions.apiKotlinLanguage.get()))
    }
}

dependencies {
    // Public API types (CoroutineScope, Duration-based calls) come from here, so it must be `api`.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test.junit)
}
