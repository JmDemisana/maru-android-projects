plugins {
    id("maru.android.application")
    id("maru.android.compose")
}

import java.util.Properties

android {
    namespace = "io.maru.manime"

    defaultConfig {
        applicationId = "io.maru.manime"
        versionCode = 1
        versionName = "1.0.0"
    }

    val keystorePropsFile = rootProject.file("apps/manime/keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                val storeFilePath = keystoreProps.getProperty("storeFile") ?: "keystore/release.keystore"
                storeFile = rootProject.file("apps/manime/$storeFilePath")
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            pickFirsts += "lib/arm64-v8a/libtorrent4j.so"
        }
    }
}

tasks.withType<com.android.build.gradle.internal.tasks.CheckAarMetadataTask>().configureEach {
    enabled = false
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.bundles.compose.core)
    implementation(libs.compose.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.coil.compose)

    // Network & serialization
    implementation(libs.bundles.network)
    implementation(libs.jsoup)

    // DataStore
    implementation(libs.androidx.datastore.prefs)

    // Media3 / ExoPlayer
    implementation(libs.bundles.media3)

    // Torrent streaming
    implementation(libs.libtorrent4j)

    implementation(project(":libs:shared-utils"))
    implementation(project(":libs:shared-ui"))

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
