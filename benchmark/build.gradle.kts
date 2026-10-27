/**
 * M5 性能基线模块（Macrobenchmark + Baseline Profile 生成器）。
 *
 * 方案决策（见 06 §M5 / 会话记录）：**不走** androidx.baselineprofile Gradle 插件
 * （与 AGP 9.1 兼容风险），走「com.android.test 自插桩模块 + BaselineProfileRule 手动落盘」：
 * 生成器跑完后把产出的 baseline-prof.txt 拷到 app/src/main/baselineProfiles/ 由 AGP 原生打包。
 */
plugins {
    // ⚠️ 不带版本号：AGP 已被 build-logic 约定插件拖入 classpath，
    // 带版本会报「already on the classpath with an unknown version」（同 app 模块的处理）
    id("com.android.test")
}

android {
    namespace = "com.lxpro.benchmark"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    /** 被测应用模块 */
    targetProjectPath = ":app"

    /** 自插桩：测试 APK 以自身为插桩目标（AGP 8.2+ 推荐，取代 targetApplicationId） */
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.uiautomator)
    implementation(libs.junit)
    // AndroidJUnit4 runner（与 app androidTestImplementation 同一坐标，未入版本目录）
    implementation("androidx.test.ext:junit:1.2.1")
}
