plugins {
    alias(libs.plugins.lxpro.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lxpro.core.common"
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}