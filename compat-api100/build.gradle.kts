import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

// The libxposed API 100 entry (LSPosed 1.9.x, Vector <= 2.0). It cannot live in :app, which compiles against
// API 102 whose XposedModule has a different constructor, so it compiles against hand-written API 100 stubs and
// reaches the loader only through Api100Boot, implemented in :app and loaded by name.
android {
    namespace = "io.github.trickhook.shadowzap.compat.api100"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    sourceSets {
        named("main") {
            kotlin.directories += "src/main/kotlin"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.appJvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.appJvmTarget.get())
    }

    lint {
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.appJvmTarget.get()))
    }
}

dependencies {
    // Never packaged: the framework provides the real API 100 classes.
    compileOnly(project(":compat-api100-stubs"))
}
