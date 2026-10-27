plugins {
    alias(libs.plugins.lxpro.android.library)
    alias(libs.plugins.lxpro.android.library.compose)
    alias(libs.plugins.lxpro.hilt)
    // 歌词设置要持久化（DataStore 里存一段 JSON）
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lxpro.feature.player"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:media"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:library"))
    implementation(project(":core:datastore"))
    // 「喜欢」写的是「收藏」这个系统歌单，循环模式读的是播放内核的 RepeatMode
    implementation(project(":core:playlist"))
    implementation(project(":source:api"))
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}