plugins {
    alias(libs.plugins.lxpro.android.library)
    alias(libs.plugins.lxpro.android.library.compose)
}

android {
    namespace = "com.lxpro.core.designsystem"
}

dependencies {
    implementation(project(":core:model"))
    // 文案库与轮换池（VersePicker）在 core:common
    implementation(project(":core:common"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // 本地歌曲分享要走 FileProvider（下载目录在应用私有空间）
    implementation(libs.androidx.core.ktx)

    debugImplementation(libs.androidx.compose.ui.tooling)
}