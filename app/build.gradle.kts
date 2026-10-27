import java.util.Properties

plugins {
    alias(libs.plugins.lxpro.android.application)
    alias(libs.plugins.lxpro.hilt)
    alias(libs.plugins.kotlin.compose)
}

// 正式签名配置：凭据在 keystore.properties（已被 .gitignore 排除）
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProperties["storePassword"] != null

android {
    namespace = "com.lxpro.music"
    ndkVersion = "27.0.12077973"

    buildFeatures {
        compose = true
        buildConfig = true
    }

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        release {
            // 有正式 keystore 用正式签名；没有（他人 clone 源码构建）回退 debug 签名保证可装机。
            // R8 仍开（见 build-logic 约定插件）。
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
            // 显式加载自定义 Proguard 规则（opencc4j keep class / dictionary 资源等）。
            proguardFiles("proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:datastore"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:media"))
    implementation(project(":core:library"))
    implementation(project(":core:playlist"))
    implementation(project(":core:stats"))
    implementation(project(":core:download"))
    implementation(project(":source:api"))
    implementation(project(":source:bilibili"))
    implementation(project(":source:kw"))
    implementation(project(":source:tx"))
    implementation(project(":source:wy"))
    implementation(project(":source:kg"))
    implementation(project(":source:mg"))
    implementation(project(":source:script"))
    implementation(project(":feature:home"))
    implementation(project(":feature:search"))
    implementation(project(":feature:player"))
    implementation(project(":feature:library"))
    implementation(project(":feature:playlist"))
    implementation(project(":feature:stats"))
    implementation(project(":feature:download"))
    implementation(project(":feature:noise"))
    implementation(project(":feature:eq"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // M5：打包应用专属 Baseline Profile 后由其在首装/更新时触发预编译
    implementation(libs.androidx.profileinstaller)

    // 省流量模式（#128）：自定义 Coil ImageLoader，OkHttp 拦截器在省流中硬拒图片网络请求
    // coil-compose 传递带入 coil artifact（SingletonImageLoader 所在）
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)

    // 真机仪器测试：QuickJS 沙箱全链路验证（INIT / lx.request / crypto / musicUrl）
    androidTestImplementation(libs.junit)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.okhttp)
    androidTestImplementation(project(":source:script"))

    debugImplementation(libs.androidx.compose.ui.tooling)
}