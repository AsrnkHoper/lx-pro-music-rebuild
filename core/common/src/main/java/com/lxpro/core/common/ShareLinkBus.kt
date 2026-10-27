package com.lxpro.core.common

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 搜索框 / 外部入口 → 播放器的「软链接播放」总线。
 *
 * 为什么需要它：搜索页（feature:search）没有播放能力，而播放与跨音源匹配逻辑在
 * MainActivity（app）里。用一个进程内单例事件总线把「用户粘进搜索框的软链接」
 * 传给上层，避免 feature 模块反向依赖 app，也避免把同一段匹配逻辑抄两份。
 */
object ShareLinkBus {

    private val _links = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** 待播放的原始链接字符串（lxpro://song/... 或 lxpro://share?...） */
    val links: SharedFlow<String> = _links.asSharedFlow()

    fun emit(link: String) {
        _links.tryEmit(link)
    }
}
