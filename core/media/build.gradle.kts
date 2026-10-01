plugins {
    alias(libs.plugins.lxpro.android.library)
    alias(libs.plugins.lxpro.hilt)
}

android {
    namespace = "com.lxpro.core.media"
}

dependencies {
    api(project(":core:model"))
    // ⚠️ 有意偏离 03 §1.2 的依赖表：播放器需要按 source 解析播放地址，
    //    因此 core:media → source:api（仅接口，不含任何具体音源实现）
    api(project(":source:api"))
    implementation(project(":core:network"))
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.kotlinx.coroutines.android)
}