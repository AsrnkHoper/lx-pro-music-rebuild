package com.lxpro.core.common

import kotlinx.serialization.Serializable

/**
 * 文案组（「设置 → 文案自定义」的组织单位）：
 * - **内置组**：id = 分类名（[VerseCategories.ALL] 之一），内容来自 [ALL_VERSES]；
 * - **自建组**：用户创建，id = "u" + 创建时间戳；旧版 `custom_verses` 迁移来的组 id = "custom"。
 *
 * 内置组同样允许重命名/增删文案/删除整组（用户 2026-10-25：「高度自定义」）。
 * 组级启停复用 DataStore 的 `enabled_verse_categories` 键（值语义 = 启用的组 id 集合）。
 */
@Serializable
data class VerseGroup(
    val id: String,
    val name: String,
    val verses: List<String> = emptyList(),
) {
    /** 内置组 = id 即分类名；时段收窄只作用于内置组，自建组启用即始终入池 */
    val builtin: Boolean get() = id in VerseCategories.ALL
}

/** 内置 6 组：从 [ALL_VERSES] 按分类折叠（设置页首启初始化用） */
fun builtinVerseGroups(): List<VerseGroup> =
    VerseCategories.ALL.map { category ->
        VerseGroup(
            id = category,
            name = category,
            verses = ALL_VERSES.filter { it.category == category }.map { it.text },
        )
    }
