package com.lxpro.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {

    @Test
    fun `标点被剥离_带括号的歌名可被无标点关键词命中`() {
        assertTrue(TextNormalizer.matches("Luv(sic.)pt3", "luvsicpt3"))
        assertTrue(TextNormalizer.matches("Luv(sic.)pt3", "Luv(sic.)pt3"))
    }

    @Test
    fun `纯符号输入不可搜_应回落初始态`() {
        assertEquals("", TextNormalizer.normalizeForSearch("!!!"))
        assertFalse(TextNormalizer.isSearchable("!!!"))
        assertFalse(TextNormalizer.matches("任意歌名", "!!!"))
    }

    @Test
    fun `空白与大小写被归一化`() {
        assertEquals("周杰伦", TextNormalizer.normalizeForSearch(" 周 杰 伦 "))
        assertEquals("abc", TextNormalizer.normalizeForSearch("A B C"))
    }
}