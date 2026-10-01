package com.lxpro.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 用户通过 SAF 授权的目录（`ACTION_OPEN_DOCUMENT_TREE`）。
 *
 * ⚠️ 必须调用 `takePersistableUriPermission`，否则重启后权限失效、下次扫描直接抛 SecurityException。
 */
@Entity(tableName = "saf_roots")
data class SafRootEntity(
    /** tree uri，同时是主键 */
    @PrimaryKey val treeUri: String,
    /** 展示名（通常是目录名） */
    val displayName: String,
    val addedAt: Long,
)