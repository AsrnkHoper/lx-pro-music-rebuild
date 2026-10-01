import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Android 库模块基础约定：SDK 档位 / Java 17。
 *
 * ⚠️ AGP 9.x **内置 Kotlin**（`agp-built-in-kotlin`），因此**不再单独 apply**
 * `org.jetbrains.kotlin.android`（见 hoperbook2/Android环境验证报告 §三 问题 3）。
 */
class LxProAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            compileSdk = COMPILE_SDK
            defaultConfig {
                minSdk = MIN_SDK
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            testOptions {
                unitTests.isReturnDefaultValues = true
            }
        }
    }
}