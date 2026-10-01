plugins {
    alias(libs.plugins.android.library)
}

// Compile-time shapes of libxposed API 100 (LSPosed 1.9.x, Vector <= 2.0). Only ever used as `compileOnly`:
// frameworks refuse a module whose APK contains io.github.libxposed.api classes, and the real classes are provided
// by the framework at runtime. Bodies are placeholders that are never executed.
android {
    namespace = "io.github.trickhook.shadowzap.compat.api100.stubs"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.appJvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.appJvmTarget.get())
    }

    lint {
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }
}
