plugins {
    alias(libs.plugins.lxpro.android.library)
    alias(libs.plugins.lxpro.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lxpro.source.script"
}

dependencies {
    api(project(":source:api"))
    api(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // QuickJS 引擎（walnut 版 lx-music-mobile 同款，Maven 拉取，无需 NDK/GitHub 源码）
    implementation("wang.harlon.quickjs:wrapper-android:2.4.0")

    testImplementation(libs.junit)
}
