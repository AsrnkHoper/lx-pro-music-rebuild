import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** 全局 SDK 档位（与 03-技术架构详解 §9.3 一致；minSdk 已由 33 下调为 26，见 02 §13 #3 裁决） */
internal const val COMPILE_SDK = 36
internal const val MIN_SDK = 26
internal const val TARGET_SDK = 35

/** 在约定插件里访问版本目录（`libs.` 访问符在 build-logic 中不可用，必须走 findLibrary） */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")