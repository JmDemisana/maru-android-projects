plugins {
    id("maru.android.library")
}

android {
    namespace = "io.maru.network"
}

dependencies {
    implementation(libs.bundles.network)
    implementation(libs.coroutines.android)
}
