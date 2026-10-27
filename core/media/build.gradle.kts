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
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))  // Crossfader 接线批三需要读 crossfadeMs/pauseResumeFadeEnabled
    implementation(project(":core:database"))  // 歌曲定时（批三）需要 TimerStateDao
    // 系统识别歌词批：LyricsCenter 取本地曲目的 .lrc/内嵌歌词要走 LocalMusicRepository
    // （core:library 只依赖 common/model/datastore/database，与 core:media 无环）
    implementation(project(":core:library"))
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    // 繁简转换（歌词繁体开关，opencc4j 纯 JVM 实现 Apache 2.0）
    implementation("com.github.houbb:opencc4j:1.14.0")
}