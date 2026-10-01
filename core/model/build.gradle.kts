plugins {
    alias(libs.plugins.lxpro.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lxpro.core.model"
}

dependencies {
    api(libs.kotlinx.serialization.json)
}