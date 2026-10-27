plugins {
    alias(libs.plugins.lxpro.android.library)
    alias(libs.plugins.lxpro.android.library.compose)
    alias(libs.plugins.lxpro.hilt)
}

android {
    namespace = "com.lxpro.feature.stats"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:stats"))
    // 统计口径要套用「重命名歌曲 / 作者」的修正（与歌单、歌手归并同一口径）
    implementation(project(":core:playlist"))
    // 「配置」子页：口径设置（DataStore）读写
    implementation(project(":core:datastore"))
    // 平台占比要拿已装音源的展示名
    implementation(project(":source:api"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    // 「配置」子页的数据管理要 SAF 文件选择器（CreateDocument / OpenDocument）
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}