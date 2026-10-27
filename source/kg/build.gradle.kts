plugins {
    alias(libs.plugins.lxpro.android.library)
    alias(libs.plugins.lxpro.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lxpro.source.kg"
}

dependencies {
    api(project(":source:api"))
    api(project(":core:model"))
    implementation(project(":source:script"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
