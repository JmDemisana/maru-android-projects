plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    compileOnly("com.android.tools.build:gradle:8.13.0")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    compileOnly("org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:2.0.21")
}

gradlePlugin {
    plugins {
        register("androidApp") {
            id = "maru.android.application"
            implementationClass = "AndroidAppConventionPlugin"
        }
        register("androidCompose") {
            id = "maru.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidLib") {
            id = "maru.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
    }
}
