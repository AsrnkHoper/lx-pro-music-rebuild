package com.lxpro.music.di

import com.lxpro.core.model.SourceId
import com.lxpro.source.api.SourceApi
import com.lxpro.source.api.SourceRegistry
import com.lxpro.source.bilibili.BilibiliSource
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
 * 首版只有内置 B 站；`register/unregister` 为 M2 的脚本音源预留（导入脚本后动态注册）。
 */
@Singleton
class DefaultSourceRegistry @Inject constructor(
    bilibili: BilibiliSource,
) : SourceRegistry {

    private val _all = MutableStateFlow<List<SourceApi>>(listOf(bilibili))
    override val all: StateFlow<List<SourceApi>> = _all.asStateFlow()

    private val _enabled = MutableStateFlow<List<SourceApi>>(listOf(bilibili))
    override val enabled: StateFlow<List<SourceApi>> = _enabled.asStateFlow()

    override fun byId(id: SourceId): SourceApi? = _all.value.firstOrNull { it.id == id }

    override fun register(api: SourceApi) {
        if (_all.value.any { it.id == api.id }) return
        _all.value = _all.value + api
        _enabled.value = _enabled.value + api
    }

    override fun unregister(id: SourceId) {
        _all.value = _all.value.filterNot { it.id == id }
        _enabled.value = _enabled.value.filterNot { it.id == id }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object SourceModule {

    @Provides
    @Singleton
    fun provideSourceRegistry(impl: DefaultSourceRegistry): SourceRegistry = impl
}