pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle provision the JDK 25 toolchain on machines (and CI runners) that do not have one.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven("https://api.xposed.info/") { name = "XposedLegacyApi" }
            }
            filter { includeGroup("de.robv.android.xposed") }
        }
    }
}

rootProject.name = "shadowzap-xposed"

include(":app")
include(":api")
// libxposed API 100 entry and the compile-only API 100 shapes it is built against.
include(":compat-api100")
include(":compat-api100-stubs")
