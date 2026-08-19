plugins {
    id("maru.android.application")
}

android {
    namespace = "io.maru.lastnotif"

    defaultConfig {
        applicationId = "io.maru.lastnotif"
        versionCode = 1
        versionName = "1.0.0"
    }

    val keystorePropsFile = rootProject.file("apps/lastnotif/keystore.properties")
    val keystoreProps = java.util.Properties().apply {
        if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                val storeFilePath = keystoreProps.getProperty("storeFile") ?: "keystore/release.keystore"
                storeFile = rootProject.file("apps/lastnotif/$storeFilePath")
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material.mdc)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.media)
    implementation(project(":libs:shared-utils"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
