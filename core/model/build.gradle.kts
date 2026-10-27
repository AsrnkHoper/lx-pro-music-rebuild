plugins {
    alias(libs.plugins.lxpro.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lxpro.core.model"
}

dependencies {
    api(libs.kotlinx.serialization.json)
    // StateFlow 镜像（省流量模式的 Compose 响应式读取，#128）
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}