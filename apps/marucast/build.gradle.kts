plugins {
    id("maru.android.application")
    id("maru.android.compose")
}

import java.util.Properties

android {
    namespace = "com.maru.marucast"

    defaultConfig {
        applicationId = "com.maru.marucast"
        versionCode = 2
        versionName = "2.0.0"
    }

    val keystorePropsFile = rootProject.file("apps/marucast/keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                val storeFilePath = keystoreProps.getProperty("storeFile") ?: "keystore/release.keystore"
                storeFile = rootProject.file("apps/marucast/$storeFilePath")
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
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

    // Network & serialization
    implementation(libs.bundles.network)

    // CameraX & QR Scanning
    implementation(libs.bundles.camera)
    implementation(libs.mlkit.barcode)

    implementation(project(":libs:shared-utils"))
    implementation(project(":libs:shared-ui"))

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
