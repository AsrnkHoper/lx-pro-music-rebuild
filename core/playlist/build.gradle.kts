plugins {
    alias(libs.plugins.lxpro.android.library)
    alias(libs.plugins.lxpro.hilt)
}

android {
    namespace = "com.lxpro.core.playlist"
}

dependencies {
    implementation(project(":core:common"))
    api(project(":core:model"))
    // api 而非 implementation：仓储的公开签名里出现 PlaylistEntity / PlaylistSummaryRow
    api(project(":core:database"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}