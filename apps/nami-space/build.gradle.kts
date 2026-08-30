plugins {
    id("maru.android.application")
    id("maru.android.compose")
}

// Read Gemini API key from local.properties (never committed to git)
val localPropsFile = rootProject.file("local.properties")
val localProps = mutableMapOf<String, String>()
if (localPropsFile.exists()) {
    localPropsFile.readLines().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                localProps[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
            }
        }
    }
}

android {
    namespace = "com.maru.namispace"

    defaultConfig {
        applicationId = "com.maru.namispace"
        versionCode = 1
        versionName = "0.1.0-alpha"

        buildConfigField(
            "String",
            "GEMINI_API_KEY",
            "\"${localProps["GEMINI_API_KEY"] ?: ""}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        noCompress += listOf("gguf", "bin")
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)

    implementation(libs.bundles.compose.core)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.compose.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.activity.compose)

    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.coroutines.android)

    implementation(libs.coil.compose)

    // On-Device Local LLM Inference (Google MediaPipe GenAI & Native In-Process llama.cpp via JNA)
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
