plugins {
    alias(libs.plugins.lxpro.android.library)
}

android {
    namespace = "com.lxpro.core.common"
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}