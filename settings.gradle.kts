// ⚠️ 阿里云镜像**只在非 CI 环境**启用。
//
// 原因（2026-10-01 实测）：GitHub Actions 的 runner 在海外，访问 maven.aliyun.com 返回 **502**；
// 而 Gradle 遇到 5xx 会**直接让本次解析失败**，不会回退到列表里的下一个仓库 ——
// 结果就是本地能编、CI 报 “could not resolve plugin artifact”。
// 因此 CI 走官方仓库（实测 repo1.maven.org / plugins.gradle.org 均 200）。
//
// 注意：`pluginManagement {}` 必须是本脚本的第一个块，故此处只能内联条件。

pluginManagement {
    includeBuild("build-logic")
    repositories {
        if (System.getenv("CI").isNullOrEmpty()) {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val useAliyunMirror: Boolean = System.getenv("CI").isNullOrEmpty()

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        if (useAliyunMirror) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/central")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "lxpro"

include(":app")

// 基础设施
include(":core:common")
include(":core:model")
include(":core:designsystem")
include(":core:datastore")
include(":core:database")
include(":core:network")
include(":core:media")

// 音源体系
include(":source:api")

// 功能模块（其余模块随里程碑增设，见 06-开发路线图与里程碑）
include(":feature:home")