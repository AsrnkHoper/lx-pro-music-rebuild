package com.lxpro.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowRandomTest {

    @Test
    fun `nextIndex 不会连抽同一句`() {
        // 用大量不同种子覆盖：真随机允许重复，但去重逻辑必须挡住「和上一句一样」
        repeat(2_000) { i ->
            val picked = FlowRandom.nextIndex(size = 7, previous = 3, seed = i)
            assertNotEquals(3, picked)
            assertTrue(picked in 0..6)
        }
    }

    @Test
    fun `池子只有一个元素时返回 0`() {
        assertEquals(0, FlowRandom.nextIndex(size = 1, previous = 0, seed = 42))
        assertEquals(0, FlowRandom.nextIndex(size = 0, previous = -1, seed = 42))
    }

    @Test
    fun `时间种子每次都不同`() {
        val seeds = List(50) { FlowRandom.seed() }
        // 至少不能是「全都一样」—— 那才是用户抱怨的假随机
        assertTrue(seeds.toSet().size > 40)
    }

    @Test
    fun `shuffled 保留全部元素且顺序会变`() {
        val source = (1..40).toList()
        val shuffled = FlowRandom.shuffled(source)
        assertEquals(source.toSet(), shuffled.toSet())
        assertEquals(source.size, shuffled.size)
        // 40 个元素打乱后与元序完全一致的概率极低；这里只证明它不是恒等映射
        assertNotEquals(source, shuffled)
    }
}