plugins {
    alias(libs.plugins.lxpro.android.library)
    alias(libs.plugins.lxpro.hilt)
}

android {
    namespace = "com.lxpro.core.download"
}

dependencies {
    implementation(project(":core:common"))
    api(project(":core:model"))
    api(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:library"))
    implementation(project(":core:network"))
    // 取流要经音源（内置 B 站 + 将来导入的脚本音源）
    implementation(project(":source:api"))

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}