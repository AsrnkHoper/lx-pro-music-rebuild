package com.lxpro.core.common

import kotlin.random.Random

/**
 * 「真随机」的统一做法（用户 2026-10-02 要求）。
 *
 * 规则：**不要依赖固定序列** —— 种子取时间这类流动因素，每次调用都重新取。
 * 文案轮换、随机播放、以后任何「随机」都走这里，避免各处各写一个假随机。
 */
object FlowRandom {

    /** 时间种子：纳秒与毫秒混合，保证同一毫秒内两次调用也不同 */
    fun seed(): Int = (System.nanoTime() xor (System.currentTimeMillis() shl 20)).toInt()

    /**
     * 取下一个下标。
     *
     * 池子大于 1 时**避免与上一句相同**：真随机允许连抽同一个，但文案行连抽两次
     * 看起来就是「没换」。这里只做这一次去重，不引入任何固定序列。
     */
    fun nextIndex(size: Int, previous: Int, seed: Int = seed()): Int {
        if (size <= 1) return 0
        val random = Random(seed)
        val candidate = random.nextInt(size)
        if (candidate != previous) return candidate
        return (candidate + 1 + random.nextInt(size - 1)) % size
    }

    /** 打乱一副播放顺序（真随机：每次调用重新取时间种子） */
    fun <T> shuffled(items: List<T>): List<T> = items.shuffled(Random(seed()))
}