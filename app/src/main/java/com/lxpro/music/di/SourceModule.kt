package com.lxpro.music.di

import com.lxpro.core.model.SourceId
import com.lxpro.core.model.SourceLabels
import com.lxpro.source.api.SourceApi
import com.lxpro.source.api.SourceRegistry
import com.lxpro.source.bilibili.BilibiliSource
import com.lxpro.source.kg.KgSource
import com.lxpro.source.kw.KwSource
import com.lxpro.source.mg.MgSource
import com.lxpro.source.tx.TxSource
import com.lxpro.source.wy.WySource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音源注册表实现。
 * 内置酷我 / QQ / 网易云（批十八：搜索+歌词+封面内置，取流委托脚本）+ B 站；
 * `register/unregister` 供脚本音源动态注册（同 id 时内置优先，见 [register]）。
 */
@Singleton
class DefaultSourceRegistry @Inject constructor(
    kw: KwSource,
    tx: TxSource,
    wy: WySource,
    kg: KgSource,
    mg: MgSource,
    bilibili: BilibiliSource,
) : SourceRegistry {

    private val builtinSources = listOf(kw, tx, wy, kg, mg, bilibili)
    private val builtinIds: Set<String> = builtinSources.map { it.id.value }.toSet()

    /** 内置源覆盖到的**平台**（别名归一后），如 qq/bilibili 也算已有平台 */
    private val builtinPlatforms: Set<String> =
        builtinSources.map { SourceLabels.canonical(it.id.value) }.toSet()

    private val _all = MutableStateFlow<List<SourceApi>>(builtinSources)
    override val all: StateFlow<List<SourceApi>> = _all.asStateFlow()

    private val _enabled = MutableStateFlow<List<SourceApi>>(builtinSources)
    override val enabled: StateFlow<List<SourceApi>> = _enabled.asStateFlow()

    /** 内置源优先：byId 返回内置源（搜索/歌词/评论由内置实现），脚本源仅用于取流委托 */
    override fun byId(id: SourceId): SourceApi? = _all.value.firstOrNull { it.id == id }

    /**
     * 勾选 / 取消勾选**平台**（不是单个脚本）。
     *
     * 同一平台无论有几个实现（内置 + 脚本、或 id 别名 qq/bilibili），`enabled` 里
     * 只保留一个代表：`_all` 中该平台首次出现的源（内置优先）。这样搜索不会对
     * 同一个平台重复请求，脚本仍由 [ScriptSourceManager.apiForSource] 用于取流。
     */
    override fun setEnabled(id: SourceId, enabled: Boolean) {
        val platform = SourceLabels.canonical(id.value)
        val representative = _all.value.firstOrNull { SourceLabels.canonical(it.id.value) == platform }
            ?: return
        val others = _enabled.value.filterNot { SourceLabels.canonical(it.id.value) == platform }
        _enabled.value = if (enabled) others + representative else others
    }

    /**
     * 脚本源注册：**允许与内置源同 id 共存**（批十九）。
     * - 内置源（kw/tx/wy/kg/mg）保留在 _all 列表的前部，负责 search/lyric/pic/comment。
     * - 脚本源追加到尾部，仅被 [ScriptSourceManager.apiForSource] 检索用于 getMusicUrl 委托。
     * - UI 搜索只用 _all 中**首次出现的同 id 源**（= 内置源），脚本源不参与搜索。
     */
    override fun register(api: SourceApi) {
        _all.value = _all.value + api
        // 脚本源不自动加入 enabled（搜索不用它），除非它带来**内置没有的新平台**。
        // 别名 id（qq/bilibili）归一后仍是已有的 QQ/B站，不能当成新平台再搜一遍。
        val platform = SourceLabels.canonical(api.id.value)
        if (platform !in builtinPlatforms && _enabled.value.none { SourceLabels.canonical(it.id.value) == platform }) {
            _enabled.value = _enabled.value + api
        }
    }

    override fun unregister(id: SourceId) {
        // 只移除脚本源（尾部同 id 的），不动内置源
        val builtin = _all.value.filter { it.id == id && it.id.value in builtinIds }
        val scripts = _all.value.filter { it.id == id && it.id.value !in builtinIds }
        _all.value = builtin + _all.value.filterNot { it.id == id }
        if (scripts.isNotEmpty() && _enabled.value.none { it.id == id && it.id.value in builtinIds }) {
            _enabled.value = _enabled.value.filterNot { it.id == id }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object SourceModule {

    @Provides
    @Singleton
    fun provideSourceRegistry(impl: DefaultSourceRegistry): SourceRegistry = impl
}