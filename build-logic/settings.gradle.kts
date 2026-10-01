// ⚠️ 与根 settings.gradle.kts 同理：aliyun 镜像在 CI runner 上返回 502，
//    而 Gradle 遇 5xx 直接失败，故 CI 只用官方仓库。
val useAliyunMirror: Boolean = System.getenv("CI").isNullOrEmpty()

dependencyResolutionManagement {
    repositories {
        if (useAliyunMirror) {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")