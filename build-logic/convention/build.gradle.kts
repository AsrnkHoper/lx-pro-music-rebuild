plugins {
    `kotlin-dsl`
}

group = "com.lxpro.buildlogic"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.compose.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
    implementation(libs.hilt.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "lxpro.android.application"
            implementationClass = "LxProAndroidApplicationPlugin"
        }
        register("androidLibrary") {
            id = "lxpro.android.library"
            implementationClass = "LxProAndroidLibraryPlugin"
        }
        register("androidLibraryCompose") {
            id = "lxpro.android.library.compose"
            implementationClass = "LxProAndroidLibraryComposePlugin"
        }
        register("hilt") {
            id = "lxpro.hilt"
            implementationClass = "LxProHiltPlugin"
        }
        register("room") {
            id = "lxpro.room"
            implementationClass = "LxProRoomPlugin"
        }
    }
}