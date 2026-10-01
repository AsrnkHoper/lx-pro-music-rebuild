plugins {
    alias(libs.plugins.lxpro.android.library)
}

android {
    namespace = "com.lxpro.source.api"
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)
}