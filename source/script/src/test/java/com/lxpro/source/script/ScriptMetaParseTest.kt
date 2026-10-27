package com.lxpro.source.script

import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptMetaParseTest {

    @Test
    fun `解析标准 LX-Music 脚本头`() {
        val script = """
            /**
             * @name 六音源
             * @description 汇总六大平台
             * @version 1.2.3
             * @author somebody
             * @homepage https://example.com
             */
            lx_setup('key', 'id', 'name', 'desc', '1.0.0', 'author', 'home', rawScript)
        """.trimIndent()

        val meta = ScriptSandboxManager.parseScriptMeta(script, fallbackId = "user_api_1")

        assertEquals("六音源", meta.name)
        assertEquals("汇总六大平台", meta.description)
        assertEquals("1.2.3", meta.version)
        assertEquals("somebody", meta.author)
        assertEquals("https://example.com", meta.homepage)
        // 无 @id 字段 → 回落 fallbackId
        assertEquals("user_api_1", meta.id)
    }

    @Test
    fun `无注释头时回落默认值`() {
        val script = "lx_setup('key', 'id', 'name')\n"
        val meta = ScriptSandboxManager.parseScriptMeta(script, fallbackId = "user_api_2")

        assertEquals("未命名脚本", meta.name)
        assertEquals("", meta.version)
        assertEquals("user_api_2", meta.id)
    }

    @Test
    fun `注释头内嵌代码注释不误伤`() {
        // 只取首个块注释；脚本体内的后续注释不参与解析
        //（单行式「/* @name x */」头不被支持 —— 与 walnut 正则行为一致）
        val script = """
            /**
             * @name 真头
             * @version 9.9.9
             */
            const a = 1
            /**
             * @name 假头
             * @version 0.0.1
             */
        """.trimIndent()

        val meta = ScriptSandboxManager.parseScriptMeta(script, fallbackId = "x")
        assertEquals("真头", meta.name)
        assertEquals("9.9.9", meta.version)
    }
}
