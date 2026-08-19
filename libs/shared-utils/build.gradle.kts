plugins {
    id("maru.android.library")
}

android {
    namespace = "io.maru.utils"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.android)
}
