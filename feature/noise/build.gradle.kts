plugins {
    alias(libs.plugins.lxpro.android.library)
    alias(libs.plugins.lxpro.android.library.compose)
    alias(libs.plugins.lxpro.hilt)
}

android {
    namespace = "com.lxpro.feature.noise"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    // 白噪音引擎 + 目录仓库（NoiseEngine / SoundMetadataRepository / NoiseCatalog）
    implementation(project(":core:media"))
    // 「我的组合」持久化（NoiseUserComboDao）
    implementation(project(":core:database"))
    // 当前混音快照 / 总音量 / 与歌曲同时播放 开关
    implementation(project(":core:datastore"))
    // 在线搜索白噪音（17 §3）：复用音源聚合检索，不另做一套
    implementation(project(":source:api"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
