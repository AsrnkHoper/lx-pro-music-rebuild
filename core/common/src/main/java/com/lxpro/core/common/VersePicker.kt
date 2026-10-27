package com.lxpro.core.common

/** 一天四个时段（`mockup_home.html` 的 `slotOf`） */
enum class DaySlot { NIGHT, MORNING, DAY, EVENING }

/**
 * 文案轮换池的选取（`mockup_home.html` / `mockup_playlist.html` 的 `versePool`）。
 *
 * 2026-10-25 重构为**文案组**模型（用户：「高度自定义」）：
 * 池子 = 当前时段偏好的内置组 ∩ 已启用的组 + 已启用的自建组（自建组不受时段收窄影响）。
 * 时段决定**偏好分类**（只收窄内置组），用户在设置里勾选**启用哪些组**，两者取交集。
 * 交集为空时回落到全部组的全部文案，**绝不留空白**（页面顶部那一行不能空着）。
 */
object VersePicker {

    /** 时段 → 偏好分类（原型 `SLOT_CATS`）；值为内置组 id（= 分类名） */
    val SLOT_CATEGORIES: Map<DaySlot, List<String>> = mapOf(
        DaySlot.NIGHT to listOf(VerseCategories.LITERATURE, VerseCategories.FATE_TIME_SELF),
        DaySlot.MORNING to listOf(VerseCategories.GOD, VerseCategories.BLADE_ANCIENT),
        DaySlot.DAY to listOf(VerseCategories.WATER, VerseCategories.GOD),
        DaySlot.EVENING to listOf(VerseCategories.LOVE_HATE, VerseCategories.FATE_TIME_SELF),
    )

    /** 夜深 0-5 / 清晨 5-11 / 日间 11-18 / 夜晚 18-23，其余归夜深 */
    fun slotOf(hour: Int): DaySlot = when (hour.coerceIn(0, 23)) {
        in 0..4 -> DaySlot.NIGHT
        in 5..10 -> DaySlot.MORNING
        in 11..17 -> DaySlot.DAY
        in 18..22 -> DaySlot.EVENING
        else -> DaySlot.NIGHT
    }

    /**
     * @param groups 全部文案组；空列表 = 设置还没读到，回落为内置 6 组（首帧也不留白）
     * @param enabledIds 启用的组 id；空集 = 全开
     * @param enabledSlots 时段分组启停（DaySlot.name）；空集 = 全开。
     *   当前时段被关掉时，这个时段不做偏好收窄，直接用全部已启用组（原型 vgroups 的语义）。
     */
    fun pool(
        hour: Int,
        groups: List<VerseGroup> = emptyList(),
        enabledIds: Set<String> = emptySet(),
        enabledSlots: Set<String> = emptySet(),
    ): List<Verse> {
        val all = groups.ifEmpty { builtinVerseGroups() }
        val enabled = if (enabledIds.isEmpty()) all else all.filter { it.id in enabledIds }
        val slot = slotOf(hour)
        val slotOn = enabledSlots.isEmpty() || slot.name in enabledSlots
        // 时段收窄只作用于内置组；自建组启用即始终入池（用户亲手写的，不受时段影响）
        val preferred = if (slotOn) SLOT_CATEGORIES.getValue(slot) else null
        val picked = enabled.filter { group ->
            !group.builtin || preferred == null || group.id in preferred
        }
        val matched = picked.flatMap { group -> group.verses.map { Verse(group.name, it) } }
        // 交集为空时回落到全部组的全部文案，**绝不留空白**（页面顶部那一行不能空着）
        return matched.ifEmpty { all.flatMap { group -> group.verses.map { Verse(group.name, it) } } }
    }
}
