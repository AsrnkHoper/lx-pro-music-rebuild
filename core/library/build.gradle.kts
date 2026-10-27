plugins {
    alias(libs.plugins.lxpro.android.library)
    alias(libs.plugins.lxpro.hilt)
}

android {
    namespace = "com.lxpro.core.library"
}

dependencies {
    implementation(project(":core:common"))
    api(project(":core:model"))
    // 扫描时长下限跟随设置（「忽略短于 30 秒的文件」）
    implementation(project(":core:datastore"))
    // api 而非 implementation：仓储的公开签名里出现 LocalTrackEntity，消费方必须能看见该类型
    api(project(":core:database"))
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}