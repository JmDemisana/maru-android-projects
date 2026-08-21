pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "maru-android-projects"

// Native Android apps
include(":apps:lastnotif")
include(":apps:marucast")
include(":apps:tup-ers")

// Shared libraries
include(":libs:shared-ui")
include(":libs:shared-utils")
include(":libs:shared-network")
