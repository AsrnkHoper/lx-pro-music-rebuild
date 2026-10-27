package com.lxpro.core.media.eq

import com.lxpro.core.database.dao.EqAliasDao
import com.lxpro.core.database.dao.EqFavoriteCollectionDao
import com.lxpro.core.database.dao.EqFavoriteItemDao
import com.lxpro.core.database.entity.EqAliasEntity
import com.lxpro.core.database.entity.EqFavoriteCollectionEntity
import com.lxpro.core.database.entity.EqFavoriteItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一个收藏集合（17 §5.7）——feature 层可见的干净形态，不含 Room 实体。
 *
 * @param presetNames 集合内曲线的**原始型号名**（卡片预览时再过别名映射显示）
 */
data class EqCollection(
    val id: String,
    val name: String,
    val updatedAt: Long,
    val presetNames: List<String>,
)

/**
 * EQ 用户层数据（17 §5.6 别名 / §5.7 收藏集合）。
 *
 * ⚠️ **非破坏性**：别名只是显示名映射，原始型号名一个字都不改（17 §5.6）——
 * 导入新预设、去重、与 AutoEQ 对版本都靠原始型号名，用户随时能「还原原名」。
 *
 * ⚠️ **收藏是集合不是扁平喜欢列表**（17 §5.7）：几百条预设挑出来的东西天然按场景分组
 * （通勤 / 夜里 / 工作），只是清单两周后又是一团乱。集合 → 详情两级。
 *
 * 放在 core:media（而非 core:database）是为了不让 feature 层直接依赖 Room 实体，
 * 与 [EqPresetRepository] 的定位一致。
 */
@Singleton
class EqUserRepository @Inject constructor(
    private val aliasDao: EqAliasDao,
    private val collectionDao: EqFavoriteCollectionDao,
    private val itemDao: EqFavoriteItemDao,
) {

    // ── 别名（17 §5.6）────────────────────────────────────────────────────────

    /** presetName → 显示别名（UI 上：有别名时标题显示别名、副标题写原型号） */
    fun observeAliases(): Flow<Map<String, String>> =
        aliasDao.observeAll().map { list -> list.associate { it.presetName to it.alias } }

    /**
     * 写入别名。空串或与原始型号名相同一律视作「还原原名」（删本表对应行）。
     * 界面入口：当前音效卡的「别名」按钮（17 §5.6）。
     */
    suspend fun setAlias(presetName: String, alias: String) {
        val trimmed = alias.trim()
        if (trimmed.isEmpty() || trimmed == presetName) {
            aliasDao.delete(presetName)
            return
        }
        aliasDao.upsert(EqAliasEntity(presetName, trimmed, System.currentTimeMillis()))
    }

    /** 还原原名 = 删别名行（17 §5.6） */
    suspend fun clearAlias(presetName: String) = aliasDao.delete(presetName)

    // ── 收藏集合（17 §5.7）────────────────────────────────────────────────────

    /**
     * 全部集合（按 updatedAt 降序，DAO 已排序）+ 各自条目。
     *
     * 两张表 join 在内存里做：条目量 = 用户手动收藏条数（个位数~几十），全量观察无压力。
     */
    fun observeCollections(): Flow<List<EqCollection>> =
        combine(collectionDao.observeAll(), itemDao.observeAll()) { collections, items ->
            val grouped = items.groupBy { it.collectionId }
            collections.map { c ->
                EqCollection(
                    id = c.id,
                    name = c.name,
                    updatedAt = c.updatedAt,
                    // 同集合内按 addedAt 降序（最近加入的排前，卡片预览也照这个序）
                    presetNames = grouped[c.id].orEmpty()
                        .sortedByDescending { it.addedAt }
                        .map { it.presetName },
                )
            }
        }

    /** 集合详情页：集合内的型号名（最近加入在前） */
    fun observeItems(collectionId: String): Flow<List<String>> =
        itemDao.observeByCollection(collectionId).map { list -> list.map { it.presetName } }

    /** 某条曲线当前落在哪些集合（多选弹层初始勾选） */
    suspend fun collectionsOf(presetName: String): Set<String> =
        withContext(Dispatchers.IO) { itemDao.getByPreset(presetName).map { it.collectionId }.toSet() }

    /** 新建集合（集合名不可为空；**重名一律拒绝**，见 [collectionIdByName]） */
    suspend fun createCollection(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        // 17 §5.7 用户订正：不允许同时存在两个同名集合（大小写不敏感）
        if (collectionIdByName(trimmed) != null) return null
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        collectionDao.upsert(EqFavoriteCollectionEntity(id, trimmed, now, now))
        return id
    }

    /** 已存在的同名集合 id（不存在为 null）—— UI 内联校验与「重名即复用」都靠它 */
    suspend fun collectionIdByName(name: String): String? =
        withContext(Dispatchers.IO) { collectionDao.findIdByName(name.trim()) }

    /**
     * 「新建并加入」（选集合弹层底部入口）：同名集合已存在则**复用它**，
     * 不新建第二个 —— 与 17 §5.7「不允许同时存在两个同名集合」一致。
     */
    suspend fun createCollectionAndAdd(name: String, presetName: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val id = collectionIdByName(trimmed) ?: createCollection(trimmed) ?: return false
        setMembership(presetName, collectionsOf(presetName) + id)
        return true
    }

    /** 重命名集合（17 §5.7）；改成重名返回 false 且不改动 */
    suspend fun renameCollection(id: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val current = collectionDao.getById(id) ?: return false
        // 改回自己原名（含大小写差异）= 无改动，直接算成功
        if (current.name.equals(trimmed, ignoreCase = true)) return true
        if (collectionIdByName(trimmed) != null) return false
        collectionDao.rename(id, trimmed, System.currentTimeMillis())
        return true
    }

    /** 删除集合（条目随外键 CASCADE 一并删除；界面必须先确认，见 17 §5.7） */
    suspend fun deleteCollection(id: String) = collectionDao.delete(id)

    /**
     * 把某条曲线的归属集合**整体设成** [collectionIds]（多选弹层确定时调用）。
     *
     * 差量写库：原来在、这次没勾的删掉；这次勾了、原来没有的补上（同集合内同型号不重复）。
     */
    suspend fun setMembership(presetName: String, collectionIds: Set<String>) {
        withContext(Dispatchers.IO) {
            val existing = itemDao.getByPreset(presetName).map { it.collectionId }.toSet()
            (existing - collectionIds).forEach { itemDao.removeFromCollection(it, presetName) }
            val now = System.currentTimeMillis()
            (collectionIds - existing).forEach { id ->
                itemDao.insert(EqFavoriteItemEntity(collectionId = id, presetName = presetName, addedAt = now))
            }
        }
    }

    /** 从集合里移除单条（17 §5.7：集合详情页的「移除」） */
    suspend fun removeFromCollection(collectionId: String, presetName: String) =
        withContext(Dispatchers.IO) { itemDao.removeFromCollection(collectionId, presetName) }
}
