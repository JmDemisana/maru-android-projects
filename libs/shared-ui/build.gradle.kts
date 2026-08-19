plugins {
    id("maru.android.library")
    id("maru.android.compose")
}

android {
    namespace = "io.maru.ui"
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.bundles.compose.core)
    implementation(libs.compose.icons.extended)
    implementation(libs.androidx.core.ktx)
}
