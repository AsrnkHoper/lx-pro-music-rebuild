package com.lxpro.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lxpro.source.script.ScriptSandboxManager
import com.lxpro.source.script.ScriptSourceApi
import com.lxpro.source.script.quickjs.LxScriptMeta
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * QuickJS 沙箱真机全链路验证（M2 #1-5 的验收测试，06 M2.2 协议验证）：
 * 脚本加载 → INIT 声明 → lx.request 网络代理 → crypto.md5 → musicUrl 校验 → 歌词。
 *
 * 不走 Hilt（沙箱的依赖只有 Context + OkHttpClient，直接构造更接近单元语义）。
 */
@RunWith(AndroidJUnit4::class)
class ScriptSandboxTest {

    private fun newManager(): ScriptSandboxManager {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return ScriptSandboxManager(
            context,
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request()
                    android.util.Log.i("LxScript", "HTTP → ${req.method} ${req.url}")
                    try {
                        val resp = chain.proceed(req)
                        android.util.Log.i("LxScript", "HTTP ← ${resp.code} ${req.url} (${resp.body?.contentLength()}B)")
                        resp
                    } catch (e: Exception) {
                        android.util.Log.i("LxScript", "HTTP ✗ ${req.url} ${e.javaClass.simpleName}: ${e.message}")
                        throw e
                    }
                }
                .build(),
        )
    }

    private fun testScript(): String =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("lx_test.js").use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * 第三方社区脚本不随仓库分发（合规：不分发音源脚本），本地把脚本放入
     * androidTest/assets 后对应测试自动启用，缺失时跳过而非失败。
     */
    private fun communityAssetOrNull(name: String): String? = try {
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(name).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: java.io.FileNotFoundException) {
        null
    }

    @Test
    fun sandboxFullChain(): Unit = runBlocking {
        val manager = newManager()
        val script = testScript()

        // 1. 加载 + INIT：应声明 kw 源（musicUrl/lyric/pic + 三档音质）
        val meta = LxScriptMeta(
            id = "lxpro_test",
            name = "LX Pro 沙箱测试脚本",
            description = "仪器测试专用",
            version = "1.0.0",
            author = "lxpro-dev",
        )
        val initInfo = manager.load(meta, script)
        assertNotNull("INIT 应返回声明", initInfo)
        val kw = initInfo.sources["kw"]
        assertNotNull("应声明 kw 源", kw)
        assertTrue("kw 应含 musicUrl", kw!!.actions.contains("musicUrl"))
        assertTrue("kw 应含 128k", kw.qualitys.contains("128k"))

        // 2. musicUrl：一次调用同时验证 lx.request（OkHttp 代理）与 crypto.md5（已知值拼接）
        // 载荷必须是 { type, musicInfo } 双层（lx 2.0 真实形态；lx_test.js handler 已按新版解构）
        val info = kotlinx.serialization.json.buildJsonObject {
            put("type", "320k")
            put("musicInfo", kotlinx.serialization.json.buildJsonObject {
                put("songmid", "test_song_1")
                put("name", "测试歌曲")
                put("singer", "测试歌手")
                put("source", "kw")
                put("interval", "03:55")
                put("albumName", "测试专辑")
            })
        }
        val response = manager.callAction("lxpro_test", "kw", "musicUrl", info)
        val url = manager.musicUrlOf(response)
        assertNotNull("musicUrl 应有 URL", url)
        // md5("test") = 098f6bcd4621d373cade4e832627b4f6 —— 脚本把 md5 结果拼进了 URL
        assertTrue(
            "URL 应含正确 md5（crypto 链路）: $url",
            url!!.contains("098f6bcd4621d373cade4e832627b4f6"),
        )
        assertTrue("URL 应以 http 开头", url.startsWith("https://"))

        // 3. 歌词：纯数据链路
        val lyricResponse = manager.callAction("lxpro_test", "kw", "lyric", info)
        val data = manager.responseData(lyricResponse)
        assertNotNull("lyric 应有 data", data)

        manager.unload("lxpro_test")
    }

    /**
     * aes / rsa 加密桥端到端校验（openssl 参考值，确定性向量）。
     * 现实说明：2026 年的真实社区脚本（六音/野花/独家等）已全部转为 API 转发，
     * 不再携带客户端加密——md5 已由 [sandboxFullChain] 用真实值验证，aes/rsa
     * 用固定向量验证桥的正确性（CBC+PKCS7 与 RSA-NoPadding 均无随机性）。
     */
    @Test
    fun cryptoVectors(): Unit = runBlocking {
        val manager = newManager()
        val script = InstrumentationRegistry.getInstrumentation().context.assets
            .open("lx_crypto_test.js").use { it.readBytes().toString(Charsets.UTF_8) }
        val meta = ScriptSandboxManager.parseScriptMeta(script, "lxpro_crypto")
        val initInfo = manager.load(meta, script)
        assertTrue(initInfo.sources.containsKey("kw"))

        val response = manager.callAction("lxpro_crypto", "kw", "musicUrl", buildJsonObject {})
        val url = manager.musicUrlOf(response)
        assertNotNull("crypto musicUrl 应有 URL", url)
        assertTrue(
            "AES-128-CBC 结果与 openssl 参考不一致: $url",
            url!!.contains("fd28a50213ef1ff29c5c1ec5edc87b7fae8209b22c6c895a8a1eb8c26d95cb9e"),
        )
        assertTrue(
            "RSA-NoPadding 结果与 openssl 参考不一致: $url",
            url.contains(
                "5d05a3903341e733a8ffa12826e1050efa0865d40e3888251de42db293f3097c95" +
                    "27e5e984b5d48c5c1158ee8d3944e5fdedf9d73347dc5391a08d185ee5237786b220" +
                    "335869efa18f5da742a003795eb1d9af6dd14c92ef17fef1b00115edc71616d082" +
                    "bd0b5ded98b5a9a069fd04f7bf28d8e995b644a2cab5d24ce825b83d",
            ),
        )
        manager.unload("lxpro_crypto")
    }

    @Test
    fun timerProbe(): Unit = runBlocking {
        val manager = newManager()
        val script = InstrumentationRegistry.getInstrumentation().context.assets
            .open("lx_timer_probe.js").use { it.readBytes().toString(Charsets.UTF_8) }
        val meta = ScriptSandboxManager.parseScriptMeta(script, "timer_probe")
        manager.load(meta, script)
        val response = manager.callAction("timer_probe", "kw", "musicUrl", buildJsonObject {})
        android.util.Log.i("LxScript", "timerProbe response: $response")
        assertNotNull(manager.musicUrlOf(response))
        manager.unload("timer_probe")
    }

    /** 用户真机上挂起的场景复现：屿溪-见川脚本 + tx musicUrl（观察脚本内部网络链） */

    @Test
    fun yuxiMusicUrl(): Unit = runBlocking {
        val manager = newManager()
        val script = communityAssetOrNull("yuxi.js")
        assumeTrue("yuxi.js 未随仓库分发，本地放入 androidTest/assets 后本测试自动启用", script != null)
        val meta = ScriptSandboxManager.parseScriptMeta(script!!, "yuxi_test")
        val initInfo = manager.load(meta, script)
        android.util.Log.i("LxScript", "yuxi loaded: ${initInfo.sources.keys}")
        val info = buildJsonObject {
            put("type", "320k")
            put("musicInfo", buildJsonObject {
                put("songmid", "001RaE0n4RrGX9") // 一个常见 tx songmid
                put("name", "晴天")
                put("singer", "周杰伦")
                put("source", "tx")
                put("interval", "04:29")
                put("albumName", "叶惠美")
            })
        }
        val response = manager.callAction("yuxi_test", "tx", "musicUrl", info)
        android.util.Log.i("LxScript", "yuxi musicUrl response: $response")
        val url = manager.musicUrlOf(response)
        assertNotNull(url)
        assertTrue("yuxi 应返回真实可播放 URL", url!!.startsWith("http"))
        manager.unload("yuxi_test")
    }

    /** ScriptSourceApi 端到端验证：musicInfoPayload 必须与社区脚本兼容 */
    @Test
    fun scriptSourceApiPayload(): Unit = runBlocking {
        val manager = newManager()
        val script = communityAssetOrNull("yuxi.js")
        assumeTrue("yuxi.js 未随仓库分发，本地放入 androidTest/assets 后本测试自动启用", script != null)
        val meta = ScriptSandboxManager.parseScriptMeta(script!!, "yuxi_api_test")
        manager.load(meta, script)

        val api = ScriptSourceApi(
            scriptId = "yuxi_api_test",
            id = com.lxpro.core.model.SourceId("tx"),
            name = "屿溪",
            declaredActions = setOf(com.lxpro.source.api.SourceAction.MUSIC_URL),
            qualities = listOf(com.lxpro.core.model.Quality.K128, com.lxpro.core.model.Quality.K320),
            manager = manager,
        )
        val song = com.lxpro.core.model.Song(
            id = "001RaE0n4RrGX9",
            name = "晴天",
            singer = "周杰伦",
            albumName = "叶惠美",
            interval = 269L,
            source = com.lxpro.core.model.SourceId("tx"),
            qualitys = listOf(com.lxpro.core.model.Quality.K320),
            raw = mapOf("songmid" to "001RaE0n4RrGX9", "strMediaMid" to "001RaE0n4RrGX9", "mediaMid" to "001RaE0n4RrGX9"),
        )
        val musicUrl = api.getMusicUrl(song, com.lxpro.core.model.Quality.K320)
        android.util.Log.i("LxScript", "ScriptSourceApi musicUrl: ${musicUrl.url}")
        assertTrue("应返回真实 URL", musicUrl.url.startsWith("http"))
        manager.unload("yuxi_api_test")
    }

    /**
     * 真实社区脚本兼容性验证（M2 #7）：六音（333KB 重度混淆）/ 野花 / 独家音源，
     * 原样未改加载 → INIT 必须成功且声明源非空。混淆脚本普遍含环境检测与
     * Function('return this') 之类探针——preload 已封 eval/Function，能跑通即
     * 与官方 lx-music-mobile 沙箱等价兼容。
     */
    @Test
    fun communityScriptsLoad(): Unit = runBlocking {
        val manager = newManager()
        val scripts = listOf("lx_sixyin.js", "lx_flower.js", "lx_lx.js")
            .mapNotNull { asset -> communityAssetOrNull(asset)?.let { asset to it } }
        assumeTrue("第三方社区脚本未随仓库分发，本地放入 androidTest/assets 后本测试自动启用", scripts.isNotEmpty())
        for ((index, pair) in scripts.withIndex()) {
            val (asset, script) = pair
            val meta = ScriptSandboxManager.parseScriptMeta(script, "community_$index")
            android.util.Log.i("LxScript", "loading $asset: name=${meta.name} version=${meta.version}")
            val initInfo = manager.load(meta, script)
            assertTrue("$asset(${meta.name}) 应声明至少一个源", initInfo.sources.isNotEmpty())
            android.util.Log.i(
                "LxScript",
                "$asset 声明: " + initInfo.sources.entries.joinToString { (id, s) ->
                    "$id(actions=${s.actions},qualitys=${s.qualitys})"
                },
            )
            manager.unload(meta.id)
        }
    }
}
