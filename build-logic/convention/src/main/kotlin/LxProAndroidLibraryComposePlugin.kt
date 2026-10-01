import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** 启用 Jetpack Compose 的库模块（须在 LxProAndroidLibraryPlugin 之后应用） */
class LxProAndroidLibraryComposePlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        extensions.configure<LibraryExtension> {
            buildFeatures {
                compose = true
            }
        }
    }
}