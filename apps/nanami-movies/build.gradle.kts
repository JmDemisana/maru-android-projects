import java.util.Properties

plugins {
    id("maru.android.application")
    id("maru.android.compose")
}

// Compile the same Stremio client and verified-range torrent helpers as Nanami Anime.
val streamingSourceDir = layout.buildDirectory.dir("generated/nanamiStreaming")
val syncStreamingSources by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("apps/manime/src/main/java")) {
        include(
            "io/maru/manime/HttpRequests.kt",
            "io/maru/manime/extensions/StremioAddonClient.kt",
            "io/maru/manime/player/SnippetExporter.kt",
            "io/maru/manime/player/SubtitleBurnIn.kt",
            "io/maru/manime/torrent/StreamSafety.kt"
        )
    }
    into(streamingSourceDir)
}

android {
    namespace = "io.maru.movies"
    defaultConfig {
        applicationId = "io.maru.nanamimovies"
        versionCode = providers.environmentVariable("NANAMI_MOVIES_VERSION_CODE").orNull?.toIntOrNull() ?: 1
        versionName = providers.environmentVariable("NANAMI_MOVIES_VERSION_NAME").getOrElse("1.0.0")
        val simklId = providers.gradleProperty("SIMKL_MOVIES_CLIENT_ID")
            .orElse(providers.environmentVariable("SIMKL_MOVIES_CLIENT_ID"))
            .getOrElse("ac2e17ef7382fa9f3113b74345d22eadb36de496d3f2b5b52ebd1d030abce6fe")
        buildConfigField("String", "SIMKL_CLIENT_ID", "\"${simklId.filter { it.isLetterOrDigit() || it in "-_" }}\"")
    }

    buildFeatures.buildConfig = true
    sourceSets.named("main") {
        java.srcDir(streamingSourceDir.get().asFile)
    }

    val signingFile = file("keystore.properties")
    if (signingFile.isFile) {
        val key = Properties().apply { signingFile.inputStream().use(::load) }
        val releaseKey = signingConfigs.create("release") {
            storeFile = file(key.getProperty("storeFile"))
            storePassword = key.getProperty("storePassword")
            keyAlias = key.getProperty("keyAlias")
            keyPassword = key.getProperty("keyPassword")
        }
        buildTypes.named("release") { signingConfig = releaseKey }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(syncStreamingSources)
}
tasks.withType<JavaCompile>().configureEach {
    dependsOn(syncStreamingSources)
}

tasks.withType<com.android.build.gradle.internal.tasks.CheckAarMetadataTask>().configureEach {
    enabled = false
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose.core)
    implementation(libs.compose.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.browser:browser:1.8.0")
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.bundles.media3)
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")

    val torrentVersion = "2.1.0-38"
    implementation("org.libtorrent4j:libtorrent4j:$torrentVersion")
    for (arch in listOf("arm", "arm64", "x86", "x86_64")) {
        runtimeOnly("org.libtorrent4j:libtorrent4j-android-$arch:$torrentVersion")
    }

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
