package com.lxpro.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lxpro.source.kw.KwSource
import com.lxpro.source.tx.TxSource
import com.lxpro.source.wy.WySource
import com.lxpro.source.script.ScriptSourceManager
import dagger.Lazy
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 批十八内置三源（kw/tx/wy）真机联网验证：搜索出结果 + 歌词可用。
 *
 * 与 walnut musicSdk 逐字段对齐的验收点：
 * - kw：search.kuwo.cn abslist 解析 + newlyric.lrc 解密歌词
 * - tx：musics.fcg zzcSign 签名被服务端接受 + fcg 歌词 base64
 * - wy：eapi 加密被 interface.music.163.com 接受 + eapi 歌词
 *
 * 取流不在这里测（委托脚本，由 ScriptSandboxTest 覆盖链路）；Lazy 注入空实现。
 */
@RunWith(AndroidJUnit4::class)
class SourceBuiltinTest {

    private val client = OkHttpClient.Builder().build()

    /** 本测试不触发取流，脚本管理器永远不会被取到 */
    private fun noScript(): Lazy<ScriptSourceManager> = Lazy { error("not needed in this test") }

    @Test
    fun kwSearchAndLyric(): Unit = runBlocking {
        val kw = KwSource(client, noScript())
        val result = kw.search("周杰伦", 1, 10)
        android.util.Log.i("SourceTest", "kw total=${result.total} size=${result.list.size}")
        result.list.take(3).forEach {
            android.util.Log.i("SourceTest", "kw ← ${it.name} / ${it.singer} / ${it.id} / ${it.interval}s / ${it.qualitys}")
        }
        assertTrue("kw 搜索应出结果", result.list.isNotEmpty())

        val lyric = kw.getLyric(result.list.first())
        android.util.Log.i("SourceTest", "kw lyric head: ${lyric.lyric.take(80)}")
        assertTrue("kw 歌词应带时间轴", lyric.lyric.contains("[00"))
    }

    @Test
    fun txSearchAndLyric(): Unit = runBlocking {
        val tx = TxSource(client, noScript())
        val result = tx.search("周杰伦", 1, 10)
        android.util.Log.i("SourceTest", "tx total=${result.total} size=${result.list.size}")
        result.list.take(3).forEach {
            android.util.Log.i("SourceTest", "tx ← ${it.name} / ${it.singer} / ${it.id} / ${it.interval}s / ${it.qualitys}")
        }
        assertTrue("tx 搜索应出结果", result.list.isNotEmpty())

        val lyric = tx.getLyric(result.list.first())
        android.util.Log.i("SourceTest", "tx lyric head: ${lyric.lyric.take(80)}")
        assertTrue("tx 歌词应带时间轴", lyric.lyric.contains("[00"))
    }

    @Test
    fun wySearchAndLyric(): Unit = runBlocking {
        val wy = WySource(client, noScript())
        val result = wy.search("周杰伦", 1, 10)
        android.util.Log.i("SourceTest", "wy total=${result.total} size=${result.list.size}")
        result.list.take(3).forEach {
            android.util.Log.i("SourceTest", "wy ← ${it.name} / ${it.singer} / ${it.id} / ${it.interval}s / ${it.qualitys}")
        }
        assertTrue("wy 搜索应出结果", result.list.isNotEmpty())

        val lyric = wy.getLyric(result.list.first())
        android.util.Log.i("SourceTest", "wy lyric head: ${lyric.lyric.take(80)}")
        assertTrue("wy 歌词应带时间轴", lyric.lyric.contains("[00"))
    }
}
