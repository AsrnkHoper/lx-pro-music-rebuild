plugins {
    alias(libs.plugins.lxpro.android.library)
    alias(libs.plugins.lxpro.hilt)
}

android {
    namespace = "com.lxpro.core.stats"
}

dependencies {
    implementation(project(":core:common"))
    api(project(":core:model"))
    // api 而非 implementation：仓储的公开签名里出现 PlayHistoryEntity / TopArtistRow
    api(project(":core:database"))
    // 实现 core:media 定义的 PlaybackHistorySink（依赖方向：stats → media，不可反向）。
    // ⚠️ 必须 api：PlayHistoryRepository 的公开签名里带着这个父接口，用 implementation
    //    会让消费方（feature:stats）编译不过 ——「cannot access PlaybackHistorySink」。
    api(project(":core:media"))
    // 备份导入要写歌单（收藏 / 用户歌单），故 stats → playlist（反向没有依赖，不成环）
    implementation(project(":core:playlist"))
    // 统计口径（最低计入秒数 / 最低播放比例）持久化在 DataStore，写入时读取最新值
    implementation(project(":core:datastore"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}