package com.lxpro.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersePickerTest {

    private val builtin = builtinVerseGroups()

    @Test
    fun `时段边界与原型一致`() {
        assertEquals(DaySlot.NIGHT, VersePicker.slotOf(0))
        assertEquals(DaySlot.NIGHT, VersePicker.slotOf(4))
        assertEquals(DaySlot.MORNING, VersePicker.slotOf(5))
        assertEquals(DaySlot.MORNING, VersePicker.slotOf(10))
        assertEquals(DaySlot.DAY, VersePicker.slotOf(11))
        assertEquals(DaySlot.DAY, VersePicker.slotOf(17))
        assertEquals(DaySlot.EVENING, VersePicker.slotOf(18))
        assertEquals(DaySlot.EVENING, VersePicker.slotOf(22))
        assertEquals(DaySlot.NIGHT, VersePicker.slotOf(23))
    }

    @Test
    fun `任何小时都有可轮换的文案`() {
        (0..23).forEach { hour ->
            assertFalse("hour=$hour 的文案池为空", VersePicker.pool(hour).isEmpty())
        }
    }

    @Test
    fun `池子只含该时段的偏好分类`() {
        // 日间偏好「水系与泪 / 神」
        val dayCategories = VersePicker.pool(12, builtin).map { it.category }.toSet()
        assertTrue(dayCategories.isNotEmpty())
        assertTrue(dayCategories.all { it in VersePicker.SLOT_CATEGORIES.getValue(DaySlot.DAY) })
    }

    @Test
    fun `只启用部分组时池子按交集收窄`() {
        // 日间偏好 水系与泪 / 神，只留「神」组
        val pool = VersePicker.pool(12, builtin, enabledIds = setOf(VerseCategories.GOD))
        assertTrue(pool.isNotEmpty())
        assertTrue(pool.all { it.category == VerseCategories.GOD })
    }

    @Test
    fun `交集为空时回落到全池而不是留白`() {
        // 日间偏好里没有「文学」，只启用它 → 交集为空
        val pool = VersePicker.pool(12, builtin, enabledIds = setOf(VerseCategories.LITERATURE))
        assertEquals(ALL_VERSES.size, pool.size)
    }

    @Test
    fun `自建组启用即始终入池不受时段收窄`() {
        // 日间偏好是 水系与泪 / 神；自建组不在偏好里，但必须仍在池中
        val custom = VerseGroup(id = "u1", name = "我的", verses = listOf("自建一句"))
        val pool = VersePicker.pool(12, builtin + custom)
        assertTrue(pool.any { it.text == "自建一句" && it.category == "我的" })
    }

    @Test
    fun `只停用一个组其余仍在池`() {
        val off = VersePicker.SLOT_CATEGORIES.getValue(DaySlot.DAY).first()
        val ids = VerseCategories.ALL.filter { it != off }.toSet()
        val pool = VersePicker.pool(12, builtin, enabledIds = ids)
        assertTrue(pool.isNotEmpty())
        assertTrue(pool.none { it.category == off })
    }

    @Test
    fun `当前时段被关掉时不做偏好收窄`() {
        // 关掉日间（12 点所在时段）→ 全部已启用内置组都入池，不再收窄到偏好分类
        val slots = DaySlot.entries.map { it.name }.toSet() - DaySlot.DAY.name
        val pool = VersePicker.pool(12, builtin, enabledSlots = slots)
        assertEquals(ALL_VERSES.size, pool.size)
    }

    @Test
    fun `重命名组后池里展示新组名`() {
        val renamed = builtin.map {
            if (it.id == VerseCategories.GOD) it.copy(name = "改过的神") else it
        }
        val pool = VersePicker.pool(12, renamed)
        assertTrue(pool.any { it.category == "改过的神" })
        assertTrue(pool.none { it.category == VerseCategories.GOD })
    }

    @Test
    fun `文案库不含空白条目`() {
        assertTrue(ALL_VERSES.size >= 100)
        assertTrue(ALL_VERSES.none { it.text.isBlank() })
        assertTrue(ALL_VERSES.all { it.category in VerseCategories.ALL })
    }
}
