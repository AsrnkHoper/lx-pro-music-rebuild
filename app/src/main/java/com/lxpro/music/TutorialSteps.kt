package com.lxpro.music

/**
 * 新手教程的步骤表（2026-10-27 用户要求：游戏式实机引导，不是文本分页）。
 *
 * 前 5 步是偏好问答（浮层卡，选完即落盘）；之后是**真实界面上的高亮**：
 * 遮罩挖洞露出真控件、用户点到了才前进，跨 Tab 时由路由变化自动推进。
 *
 * 目标 id 与 `Modifier.tutorialTarget(id)` 挂载点一一对应，见 [TutorialTargets]。
 */
internal sealed interface TutorialStep {

    /** 开场：说明这次引导会做什么 */
    data object Welcome : TutorialStep

    /** 配色（六套） */
    data object Palette : TutorialStep

    /** 音质 + 省流量 */
    data object Sound : TutorialStep

    /** 搜索模式 + 记忆播放位置 */
    data object Habits : TutorialStep

    /** 白噪音默认场景 */
    data object NoiseScene : TutorialStep

    /**
     * 实机高亮一步。
     *
     * @property targetId 要高亮的控件 id（null = 屏幕居中卡片）
     * @property tapToAdvance 点高亮区即前进（区域被一层透明接收器盖住，不会真的触发控件）
     * @property advanceOnRoute 用户点真实控件产生了这个路由后前进（Tab 步骤用）
     * @property hint 气泡里的动作提示，如「点一下「歌单」」
     */
    data class Tour(
        val title: String,
        val body: String,
        val targetId: String? = null,
        val tapToAdvance: Boolean = false,
        val advanceOnRoute: String? = null,
        val hint: String? = null,
    ) : TutorialStep

    /** 结业：开始使用 */
    data object Done : TutorialStep
}

// ── 高亮目标 id（与控件上 tutorialTarget(...) 的字符串必须一致）──
internal const val TUTORIAL_TARGET_HOME_SEARCH = "home_search"
internal const val TUTORIAL_TARGET_PLAYLIST_NEW = "playlist_new"
internal const val TUTORIAL_TARGET_TAB_PLAYLISTS = "tab_" + Routes.PLAYLISTS
internal const val TUTORIAL_TARGET_TAB_STATS = "tab_" + Routes.STATS
internal const val TUTORIAL_TARGET_TAB_NOISE = "tab_" + Routes.NOISE
internal const val TUTORIAL_TARGET_TAB_SETTINGS = "tab_" + Routes.SETTINGS

internal val TUTORIAL_STEPS: List<TutorialStep> = listOf(
    TutorialStep.Welcome,
    TutorialStep.Palette,
    TutorialStep.Sound,
    TutorialStep.Habits,
    TutorialStep.NoiseScene,
    TutorialStep.Tour(
        title = "从这里开始搜歌",
        body = "在线和本地在同一个框里，左边的放大镜可以选音源。点一下搜索框，我们继续。",
        targetId = TUTORIAL_TARGET_HOME_SEARCH,
        tapToAdvance = true,
        hint = "点一下高亮的搜索框",
    ),
    TutorialStep.Tour(
        title = "五个主 Tab",
        body = "首页、歌单、统计、白噪音、设置 —— 全部功能都在这五格。点「歌单」看看。",
        targetId = TUTORIAL_TARGET_TAB_PLAYLISTS,
        advanceOnRoute = Routes.PLAYLISTS,
        hint = "点一下「歌单」",
    ),
    TutorialStep.Tour(
        title = "歌单就是你的抽屉",
        body = "点「新建」就能开自己的歌单；长按可拖动排序，右上角菜单能改名、删除。",
        targetId = TUTORIAL_TARGET_PLAYLIST_NEW,
        tapToAdvance = true,
        hint = "点一下「新建」",
    ),
    TutorialStep.Tour(
        title = "去「统计」看看",
        body = "你听的每一首都会记账。点「统计」，看看它能长成什么样。",
        targetId = TUTORIAL_TARGET_TAB_STATS,
        advanceOnRoute = Routes.STATS,
        hint = "点一下「统计」",
    ),
    TutorialStep.Tour(
        title = "数据只来自你的播放",
        body = "听歌报告、最常听、活跃时段、听歌日历、听歌画像都按本机记录实时聚合。" +
            "入账门槛默认 ≥30 秒且 ≥50%，可在「统计口径」里改。",
        targetId = TUTORIAL_TARGET_TAB_STATS,
    ),
    TutorialStep.Tour(
        title = "白噪音是独立音轨",
        body = "23 个音源、8 个预设场景，能单独播，也能垫在音乐下面。点「白噪音」试试。",
        targetId = TUTORIAL_TARGET_TAB_NOISE,
        advanceOnRoute = Routes.NOISE,
        hint = "点一下「白噪音」",
    ),
    TutorialStep.Tour(
        title = "自己搭一套",
        body = "进页面会按你选的默认场景铺好混音，每个图层能单独调音量；睡眠定时在页面里。白噪音也有独立统计。",
        targetId = TUTORIAL_TARGET_TAB_NOISE,
    ),
    TutorialStep.Tour(
        title = "最后是设置",
        body = "刚才选的配色、音质、省流量都在里面，随时能改。点「设置」。",
        targetId = TUTORIAL_TARGET_TAB_SETTINGS,
        advanceOnRoute = Routes.SETTINGS,
        hint = "点一下「设置」",
    ),
    TutorialStep.Tour(
        title = "一个文件带走全部",
        body = "「数据与备份」把统计、历史、歌单、标签、白噪音和全部设置装进一个 .lxmc。换机前导出一次就够。",
        targetId = TUTORIAL_TARGET_TAB_SETTINGS,
    ),
    TutorialStep.Done,
)
