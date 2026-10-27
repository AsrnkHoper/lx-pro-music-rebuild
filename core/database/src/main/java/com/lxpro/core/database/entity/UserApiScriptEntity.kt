package com.lxpro.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 用户导入的 lx 2.0 音源脚本（M2 #6，03 §6.1）。
 *
 * - `filePath`：脚本正文在应用私有目录（files/scripts/{id}.js）的绝对路径
 * - `sources`：INIT 声明的源 JSON（`{kw:{actions,qualitys}}`），加载成功后回填，
 *   供设置页展示与注册前预览；未加载过为空串
 */
@Entity(tableName = "user_api_scripts")
data class UserApiScriptEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val filePath: String,
    val sources: String,
    val enabled: Boolean,
    val importedAt: Long,
)
