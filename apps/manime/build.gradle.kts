import java.util.Properties

plugins {
    id("maru.android.application")
    id("maru.android.compose")
}

android {
    namespace = "io.maru.manime"

    defaultConfig {
        applicationId = "io.maru.nanamianime"
        versionCode = System.getenv("NANAMI_ANIME_VERSION_CODE")?.toIntOrNull() ?: 2
        versionName = System.getenv("NANAMI_ANIME_VERSION_NAME") ?: "2.0.0"
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
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.google.zxing:core:3.5.3")

    // Network & serialization
    implementation(libs.bundles.network)
    implementation(libs.jsoup)

    // DataStore
    implementation(libs.androidx.datastore.prefs)

    // Media3 / ExoPlayer & Chromecast
    implementation(libs.bundles.media3)
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")

    // This release supports 16 KB pages and keeps compatibility with Android 8.
    val torrentVersion = "2.1.0-38"
    implementation("org.libtorrent4j:libtorrent4j:$torrentVersion")
    for (arch in listOf("arm", "arm64", "x86", "x86_64")) {
        runtimeOnly("org.libtorrent4j:libtorrent4j-android-$arch:$torrentVersion")
    }

    implementation(project(":libs:shared-utils"))
    implementation(project(":libs:shared-ui"))

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
