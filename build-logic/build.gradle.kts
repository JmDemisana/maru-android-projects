plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    compileOnly(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
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
