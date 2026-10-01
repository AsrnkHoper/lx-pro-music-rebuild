pluginManagement {
    includeBuild("build-logic")
    repositories {
        // 阿里云镜像加速（国内网络）
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/central")
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