plugins {
    alias(libs.plugins.lxpro.android.library)
    alias(libs.plugins.lxpro.android.library.compose)
    alias(libs.plugins.lxpro.hilt)
}

android {
    namespace = "com.lxpro.feature.library"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:library"))
    // 「歌曲行显示封面」设置（默认视图）
    implementation(project(":core:datastore"))
    // 本地曲目也要套用「重命名」修正（SongOverrideRepository 在 core:playlist）
    implementation(project(":core:playlist"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}