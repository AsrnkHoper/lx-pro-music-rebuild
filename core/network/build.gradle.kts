plugins {
    alias(libs.plugins.lxpro.android.library)
    alias(libs.plugins.lxpro.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lxpro.core.network"
}

dependencies {
    api(project(":core:model"))
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}