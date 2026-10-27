plugins {
    alias(libs.plugins.lxpro.android.library)
    alias(libs.plugins.lxpro.hilt)
}

android {
    namespace = "com.lxpro.source.api"
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)
}