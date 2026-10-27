package com.lxpro.music

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lxpro.core.common.FlowRandom
import com.lxpro.core.database.entity.TimerStateEntity
import com.lxpro.core.designsystem.ambient.LXAmbientBackground
import com.lxpro.core.designsystem.ambient.LXDynamicBackground
import com.lxpro.core.designsystem.component.LXBottomBar
import com.lxpro.core.designsystem.component.LXMiniPlayerBar
import com.lxpro.core.designsystem.component.LXSongAction
import com.lxpro.core.designsystem.component.LXSongActionSheet
import com.lxpro.core.designsystem.component.LXSongTimerTarget
import com.lxpro.core.designsystem.component.LXTabItem
import com.lxpro.core.designsystem.component.LXToast
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.component.LocalVerseRotation
import com.lxpro.core.designsystem.component.VerseRotationConfig
import com.lxpro.core.designsystem.component.copyToClipboard
import com.lxpro.core.designsystem.component.shareSong
import com.lxpro.core.designsystem.component.shortLabel
import com.lxpro.core.designsystem.theme.LXMotion
import com.lxpro.core.designsystem.theme.LXPalette
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.model.Song
import com.lxpro.core.model.SongTagBus
import com.lxpro.feature.download.DownloadBall
import com.lxpro.feature.download.DownloadScreen
import com.lxpro.feature.home.HomeScreen
import com.lxpro.feature.library.ARG_LIBRARY_ACTION
import com.lxpro.feature.library.LocalLibraryAction
import com.lxpro.feature.library.LocalLibraryScreen
import com.lxpro.feature.player.LyricsViewModel
import com.lxpro.feature.player.PlayerPageViewModel
import com.lxpro.feature.player.PlayerScreen
import com.lxpro.feature.playlist.ARG_PLAYLIST_ID
import com.lxpro.feature.playlist.PlaylistDetailScreen
import com.lxpro.feature.playlist.PlaylistsScreen
import com.lxpro.feature.playlist.PlaylistsViewModel
import com.lxpro.feature.search.ARG_AA_KIND
import com.lxpro.feature.search.ARG_AA_KEY
import com.lxpro.feature.search.ArtistAlbumScreen
import com.lxpro.feature.search.ListKind
import com.lxpro.feature.search.SearchScreen
import com.lxpro.feature.search.ROUTE_ARTIST_ALBUM
import com.lxpro.feature.search.artistAlbumRoute
import com.lxpro.feature.stats.ARG_STATS_LIST_KIND
import com.lxpro.feature.stats.ARG_STATS_LIST_KEY
import com.lxpro.feature.stats.ROUTE_STATS_LIST
import com.lxpro.feature.stats.StatsListScreen
import com.lxpro.feature.stats.StatsScreen
import com.lxpro.feature.stats.statsListRoute
import com.lxpro.feature.noise.NoiseScreen
import com.lxpro.feature.eq.ARG_EQ_COLLECTION_ID
import com.lxpro.feature.eq.EqCollectionScreen
import com.lxpro.feature.eq.EqScreen
import com.lxpro.feature.eq.ROUTE_EQ_COLLECTION
import com.lxpro.feature.eq.eqCollectionRoute
import com.lxpro.core.media.noise.NoiseEngine
import javax.inject.Inject
import com.lxpro.music.tags.SongTagsSheet
import com.lxpro.music.tags.SongTagsViewModel
import com.lxpro.music.ui.AboutScreen
import com.lxpro.music.ui.ComingSoonScreen
import com.lxpro.music.ui.TutorialOverlay
import com.lxpro.music.ui.settings.SettingsCategoryScreen
import com.lxpro.music.ui.settings.SettingsHubScreen
import com.lxpro.music.ui.settings.VerseGroupScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlin.math.abs

object Routes {
    // ── 5 个 Tab 根（15 §2：首页 · 歌单 · 统计 · 白噪音 · 设置）──
    const val HOME = "home"
    const val PLAYLISTS = "playlists"
    const val STATS = "stats"
    const val NOISE = "noise"
    const val SETTINGS = "settings"

    // ── 二级 / 独立全屏 ──
    const val ARG_SEARCH_QUERY = "q"
    const val SEARCH = "search?$ARG_SEARCH_QUERY={$ARG_SEARCH_QUERY}"
    const val LIBRARY = "library?$ARG_LIBRARY_ACTION={$ARG_LIBRARY_ACTION}"
    const val PLAYER = "player"
    const val ABOUT = "about"
    const val DOWNLOAD = "download"
    const val PLAYLIST_DETAIL = "playlists/{$ARG_PLAYLIST_ID}"

    /** 均衡器页（17 §5，feature/eq；设置 → 播放与音效 → 均衡器） */
    const val EQ = "eq"

    /** 收藏集合详情页（17 §5.7：均衡器页「我的收藏」网格 → 详情两级） */
    val EQ_COLLECTION = ROUTE_EQ_COLLECTION

    // ── 设置详情（M3 任务 9 的第二级：hub → 分类详情）──
    const val ARG_SETTINGS_CAT = "cat"
    const val SETTINGS_CATEGORY = "settings/{$ARG_SETTINGS_CAT}"

    /** 文案组详情（设置 → 文案自定义 → 点某个组；组内文案的增删改） */
    const val ARG_VERSE_GROUP = "groupId"
    const val VERSE_GROUP = "settings/verseGroup/{$ARG_VERSE_GROUP}"

    /** 歌手 / 专辑详情（M3 任务 10）—— 路由常量定义在 feature:search 里 */
    val ARTIST_ALBUM = ROUTE_ARTIST_ALBUM

    /** 统计榜单列表页（歌手 / 专辑）—— 路由常量定义在 feature:stats 里 */
    val STATS_LIST = ROUTE_STATS_LIST

    fun playlistDetail(playlistId: String) = "playlists/$playlistId"

    /** 设置分类详情页（key ∈ source|local|look|play|noise|down|list|verse|about） */
    fun settingsCategory(key: String) = "settings/$key"

    /** 文案组详情页 */
    fun verseGroup(id: String) = "settings/verseGroup/$id"

    /** 首页/详情页把关键词交给搜索页（信息容量大的地方不在同级展开） */
    fun search(query: String) = "search?$ARG_SEARCH_QUERY=${Uri.encode(query)}"

    /** 本地音乐页：带动作进（扫描 / 导入各司其职） */
    fun library(action: String = "") = "library?$ARG_LIBRARY_ACTION=$action"
}

/** Tab 顺序按 12 §2 #1 裁决：白噪音为第 4 项，设置保持最后一个 */
private val TAB_ITEMS = listOf(
    LXTabItem(Routes.HOME, "首页", LxIcons.Home),
    LXTabItem(Routes.PLAYLISTS, "歌单", LxIcons.Playlist),
    LXTabItem(Routes.STATS, "统计", LxIcons.Stats),
    LXTabItem(Routes.NOISE, "白噪音", LxIcons.Noise),
    LXTabItem(Routes.SETTINGS, "设置", LxIcons.Settings),
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** 白噪音引擎（M4 批二）：迷你条「白噪音在响」角标接它的状态 */
    @Inject
    lateinit var noiseEngine: NoiseEngine

    /** 设置仓库：读分享模板 / 写分享模板 */
    @Inject
    lateinit var settings: com.lxpro.core.datastore.SettingsRepository

    /** 音源注册表：跨源搜索（deeplink 接收端用） */
    @Inject
    lateinit var sourceRegistry: com.lxpro.source.api.SourceRegistry

    /** 跨音源搜索：title + artist → 取最匹配的 Song。
     *  并发调所有已启用音源的 SourceApi.search，按歌名+艺术家匹配分数排序取最高。
     *  匹配逻辑：歌名完全匹配优先 > 歌名包含 > 歌名模糊。 */
    private suspend fun searchAndPick(title: String, artist: String): com.lxpro.core.model.Song? {
        val keyword = if (artist.isNotBlank()) "$title $artist" else title
        val sources = sourceRegistry.enabled.first()
        if (sources.isEmpty()) return null
        val matches = kotlinx.coroutines.coroutineScope {
            sources.map { source ->
                async {
                    runCatching { source.search(keyword, 1, 10).list }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        if (matches.isEmpty()) return null
        // 按匹配分数排序：歌名完全匹配 + 艺术家包含 = 最高分
        val best = matches.maxByOrNull { song ->
            var score = 0
            if (song.name.trim() == title.trim()) score += 100
            if (song.name.contains(title) || title.contains(song.name)) score += 50
            if (artist.isNotBlank()) {
                if (song.singer.contains(artist) || artist.contains(song.singer)) score += 30
            }
            score
        }
        return best
    }

    /** 记录最近收到的 deeplink（onCreate 读初始 intent，onNewIntent 更新）——用 StateFlow 让 Compose 能 collect */
    private val _pendingDeeplink = kotlinx.coroutines.flow.MutableStateFlow<android.net.Uri?>(null)
    private val pendingDeeplink: kotlinx.coroutines.flow.StateFlow<android.net.Uri?> = _pendingDeeplink

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        _pendingDeeplink.value = intent.data
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        _pendingDeeplink.value = intent.data

        setContent {
            val appViewModel: AppViewModel = hiltViewModel()
            val playerViewModel: PlayerViewModel = hiltViewModel()
            val songActions: SongActionsViewModel = hiltViewModel()
            // 歌曲标签页（第 8 项）：全局唯一的标签弹层宿主，SongTagBus 触发
            val tagsViewModel: SongTagsViewModel = hiltViewModel()
            val tagsState by tagsViewModel.state.collectAsStateWithLifecycle()
            val tagsMessage by tagsViewModel.message.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) {
                SongTagBus.songs.collect { tagsViewModel.show(it) }
            }
            // ⚠️ 播放页状态升到 app 级：顶部「更多」弹层里的「立体歌词 / 歌词参数」也要操作它，
            //    而弹层挂在 NavHost 之外（route 作用域拿不到）
            val playerPageViewModel: PlayerPageViewModel = hiltViewModel()
            // 下载悬浮球：跨页面常驻，有任务才出现
            val downloadIndicatorViewModel: DownloadIndicatorViewModel = hiltViewModel()
            // 新手引导（游戏式实机引导）：状态与遮罩挂在 app 级，跨页面不中断
            val tutorialViewModel: TutorialViewModel = hiltViewModel()
            val tutorialState by tutorialViewModel.ui.collectAsStateWithLifecycle()
            val downloadIndicator by downloadIndicatorViewModel.state.collectAsStateWithLifecycle()
            val ballPosition by downloadIndicatorViewModel.ballPosition.collectAsStateWithLifecycle()
            // 悬浮球的可拖动范围 = 内容区尺寸（px），拖动与位置还原都要用它
            var contentSize by remember { mutableStateOf(IntSize.Zero) }

            val paletteOrNull by appViewModel.palette.collectAsStateWithLifecycle()
            // ⚠️ 等首个**真实**配色到位再套主题：否则会先用默认 ④ 白渲一帧、再动画过渡到
            // 用户存的那套，启动时就是一段「变色过程」（用户 2026-10-05 反馈）。
            // DataStore 首读只有几毫秒，这段时间系统还在显示启动图，不会看到空白。
            val palette = paletteOrNull ?: return@setContent
            // 跟随系统深浅色（13 §4）：系统处于深色且当前是浅色系时，回落到 ① 暗绿
            val followSystemDark by appViewModel.followSystemDark.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val effectivePalette = remember(palette, followSystemDark, systemDark) {
                if (followSystemDark && systemDark && palette.colors.bg.luminance() > 0.5f) {
                    LXPalette.GreenHard
                } else {
                    palette
                }
            }
            // 文案轮换（设置）通过 CompositionLocal 下发，页面无需各自注入
            val verseGroups by appViewModel.verseGroups.collectAsStateWithLifecycle()
            val verseGroupIds by appViewModel.enabledVerseGroupIds.collectAsStateWithLifecycle()
            val verseSlots by appViewModel.enabledVerseSlots.collectAsStateWithLifecycle()
            val verseIntervalSec by appViewModel.verseIntervalSec.collectAsStateWithLifecycle()
            val verseRotation = remember(verseGroups, verseGroupIds, verseSlots, verseIntervalSec) {
                VerseRotationConfig(verseGroups, verseGroupIds, verseSlots, verseIntervalSec * 1000L)
            }
            val ambientEnabled by appViewModel.ambientEnabled.collectAsStateWithLifecycle()
            // 动态背景（对齐 LX-Music「使用动态背景」）：开启后全局背景 = 封面模糊 + 配色遮罩
            val dynamicBgEnabled by appViewModel.dynamicBgEnabled.collectAsStateWithLifecycle()
            val dynamicBgBlur by appViewModel.dynamicBgBlur.collectAsStateWithLifecycle()
            val dynamicBgMask by appViewModel.dynamicBgMaskOpacity.collectAsStateWithLifecycle()
            val dynamicBgCover by appViewModel.currentCover.collectAsStateWithLifecycle()
            val onboarded by appViewModel.onboarded.collectAsStateWithLifecycle()
            val playerState by playerViewModel.state.collectAsStateWithLifecycle()
            // M4 批二：白噪音引擎状态（迷你条角标）
            val noiseEngineState by noiseEngine.state.collectAsStateWithLifecycle()
            // M4 批三：歌曲定时（17 §7）——「更多」菜单状态 + 播放页提示条
            val songTimer by playerViewModel.songTimer.collectAsStateWithLifecycle()
            // 分钟级倒计时走 20s 一跳就够；只有「N 分钟后」目标需要走表，其余是静态文案
            var songTimerTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(songTimer?.targetKind, songTimer?.setAt) {
                if (songTimer?.targetKind == TimerStateEntity.TargetKind.AFTER_MINUTES.id) {
                    while (true) {
                        songTimerTick = System.currentTimeMillis()
                        delay(20_000L)
                    }
                }
            }
            val songTimerText = remember(songTimer, songTimerTick) {
                val entity = songTimer ?: return@remember null
                when (entity.targetKind) {
                    TimerStateEntity.TargetKind.AFTER_CURRENT_TRACK.id -> "播完当前歌曲后停止"
                    TimerStateEntity.TargetKind.UNTIL_HOUR.id ->
                        "将于早上 ${entity.targetHour ?: 7}:00 停止播放"
                    else -> {
                        val remain = playerViewModel.songTimerRemainingMs() ?: return@remember null
                        val minutes = (remain + 59_999L) / 60_000L
                        "$minutes 分钟后停止播放"
                    }
                }
            }
            val playlistOptions by songActions.playlists.collectAsStateWithLifecycle()
            val sourceNames by songActions.sourceNames.collectAsStateWithLifecycle()
            val swapState by songActions.swapState.collectAsStateWithLifecycle()
            val swapPreview by songActions.swapPreviewState.collectAsStateWithLifecycle()
            // 分享邀请语模板（实时读 DataStore）
            val shareTemplateText by settings.shareTemplate.collectAsStateWithLifecycle(
                initialValue = "我在阿玉音乐听《{title}-{artist}》，复制前往阿玉音乐聆听！{link}",
            )
            // 把用户自定义模板同步到全局缓存——所有 shareSong() 调用点自动生效
            LaunchedEffect(shareTemplateText) {
                com.lxpro.core.designsystem.component.ShareTemplateCache.template = shareTemplateText
            }
            // 歌词长按分享弹窗状态
            var shareSongForLine by remember { mutableStateOf<com.lxpro.core.model.Song?>(null) }
            var shareLineForDialog by remember { mutableStateOf<com.lxpro.core.common.LyricLine?>(null) }
            var shareTagInput by remember { mutableStateOf("") }
            val actionMessage by songActions.message.collectAsStateWithLifecycle()

            val context = LocalContext.current
            // 通知权限（Android 13+）：媒体通知是系统「正在播放」控制的载体，
            // 没有它就只剩声音、通知栏/锁屏/蓝牙都控制不到（2026-10-25 反馈）。
            // 等第一首歌真的开始播再要，避免一启动就弹窗。
            val notifPermission =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            LaunchedEffect(playerState.current?.let { "${it.source.value}:${it.id}" }) {
                if (playerState.current != null &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            // 跨页面共用的「歌曲操作」弹层：搜索页与播放页都只是把它叫起来
            var actionSong by remember { mutableStateOf<Song?>(null) }
            // 「更多」是从哪打开的：播放页要多出「立体歌词 / 歌词参数」两项
            var actionFromPlayer by remember { mutableStateOf(false) }
            var toastText by remember { mutableStateOf<String?>(null) }
            val pageState by playerPageViewModel.state.collectAsStateWithLifecycle()
            // 软链接起播后要求「进播放页」：navController 在本组合函数后半段才创建，
            // 所以这里只递一个请求计数，等导航就绪后消费。
            var openPlayerRequest by remember { mutableIntStateOf(0) }

            // 统一轻提示：任何页面都能叫它（歌曲操作回执、播放页的循环模式提示…）
            // ⚠️ 错误提示单独给更长停留时间：内容是可操作的（去音源管理导入脚本），2.4s 读不完
            var toastDurationMs by remember { mutableLongStateOf(TOAST_DURATION_MS) }
            val postToast: (String) -> Unit = {
                toastDurationMs = TOAST_DURATION_MS
                toastText = it
            }
            val postErrorToast: (String) -> Unit = {
                toastDurationMs = ERROR_TOAST_DURATION_MS
                toastText = it
            }

            // ════════════════════════════════════════════════════════
            // 软链接播放：三个入口共用同一段逻辑
            //   ① 系统 deeplink（lxpro://...）
            //   ② 搜索框粘贴的分享文案（ShareLinkBus）
            // ════════════════════════════════════════════════════════
            val playLink: suspend (String) -> Unit = { raw ->
                // 1. 先试分享协议（跨源匹配：歌名+艺术家 → 搜索 → 播放 → seek）
                val sharePayload = com.lxpro.core.model.SongLink.decodeShare(raw)
                if (sharePayload != null) {
                    val matched = searchAndPick(sharePayload.title, sharePayload.artist)
                    if (matched != null) {
                        playerViewModel.play(matched, listOf(matched))
                        // 起播即进播放页（搜索框粘贴分享文案 / 外部链接都走这里），
                        // 不要停在搜索页只出声。seek 在播放页里继续完成。
                        openPlayerRequest++
                        if (sharePayload.timestampMs > 0) {
                            // 在线歌曲要等取流完成才能 seek——固定延时不可靠（实测 500ms 时
                            // 播放器还没 ready，seek 被丢弃）。这里等「确实开始推进」再跳。
                            val ready = withTimeoutOrNull(20_000) {
                                playerViewModel.state.first { it.current?.id == matched.id && it.positionMs > 0 }
                            }
                            android.util.Log.i("ShareDeeplink", "ready=${ready != null}, target=${sharePayload.timestampMs}")
                            // SwappablePlayer 交叉淡变期间 seek 可能被吞（换播放器那一刻），
                            // 所以循环重试直到位置真的落到目标附近。
                            for (attempt in 0 until 10) {
                                playerViewModel.seekTo(sharePayload.timestampMs)
                                delay(400)
                                val pos = playerViewModel.state.value.positionMs
                                android.util.Log.i("ShareDeeplink", "seek attempt=$attempt pos=$pos")
                                if (kotlin.math.abs(pos - sharePayload.timestampMs) < 3000L) break
                            }
                        }
                        if (sharePayload.tag.isNotBlank()) {
                            postToast("${matched.name} - ${matched.singer}\n「${sharePayload.tag}」")
                        } else {
                            postToast("${matched.name} - ${matched.singer}")
                        }
                    } else {
                        postToast("没搜到《${sharePayload.title}》，可能需要启用更多音源")
                    }
                } else {
                    // 2. 再试单曲直接播放协议
                    val directSong = com.lxpro.core.model.SongLink.decode(raw)
                    if (directSong != null) {
                        playerViewModel.play(directSong, listOf(directSong))
                        openPlayerRequest++
                        postToast("${directSong.name} - ${directSong.singer}")
                    }
                }
            }

            val deeplinkUri by pendingDeeplink.collectAsStateWithLifecycle()
            LaunchedEffect(deeplinkUri) {
                val uri = deeplinkUri ?: return@LaunchedEffect
                playLink(uri.toString())
                _pendingDeeplink.value = null
            }
            // 搜索框粘贴分享文案 → 直接起播
            LaunchedEffect(Unit) {
                com.lxpro.core.common.ShareLinkBus.links.collect { link ->
                    playLink(link)
                }
            }

            LaunchedEffect(actionMessage) {
                val message = actionMessage
                if (message != null) {
                    songActions.onMessageShown()
                    postToast(message)
                }
            }

            // 播放失败必须让用户看见：取流失败/未导入音源脚本等错误原先只写进
            // PlayerState.error，UI 从未消费 —— 表现就是「一直缓冲，什么都不说」。
            LaunchedEffect(playerState.error) {
                val message = playerState.error
                if (message != null) {
                    // 先消费再提示：同一错误连续发生（用户再点一次）时 StateFlow 才会重新发射
                    playerViewModel.clearError()
                    postErrorToast(message)
                }
            }

            LaunchedEffect(toastText) {
                if (toastText != null) {
                    delay(toastDurationMs)
                    toastText = null
                }
            }

            // 动态背景激活状态抬到主题外：既要喂给主题（容器透出封面），也决定氛围层停画
            val dynamicBgActive = dynamicBgEnabled && dynamicBgCover != null
            CompositionLocalProvider(LocalVerseRotation provides verseRotation) {
            LXTheme(palette = effectivePalette, dynamicBgActive = dynamicBgActive) {
                // 动态背景盖住氛围层时，氛围层整层停画（省电：被封面铺满了反正看不见）
                LXAmbientBackground(enabled = ambientEnabled && !dynamicBgActive) {
                    if (dynamicBgActive) {
                        LXDynamicBackground(
                            coverModel = dynamicBgCover,
                            blur = dynamicBgBlur,
                            maskOpacity = dynamicBgMask,
                        )
                    }
                    val navController = rememberNavController()
                    val backStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = backStackEntry?.destination?.route
                    // Tab 栏只在 5 个根页出现（14 §4.1：详情页/播放页全屏沉浸）
                    val isTabRoot = TAB_ITEMS.any { it.route == currentRoute }
                    // 键盘打开时藏掉迷你条与 Tab 栏：edge-to-edge 下窗口不缩，
                    // 这两层会留在键盘后面，而内容又被 safeDrawing（含 IME）顶上去，
                    // 中间就空出一条与底部 chrome 等高的「空白带」挡住界面
                    //（2026-10-11 用户反馈）；藏起来让内容直贴键盘顶。
                    //（isImeVisible 是实验 API，这里用稳定写法：ime 底部 inset > 0 即键盘在弹）
                    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

                    // 首启引导（M3 任务 16）：只在**读到明确未看过**时启动一次，
                    // null（还没读到 DataStore）不启动，免得给老用户闪一下引导。
                    // 引导是盖在真实界面上的遮罩（游戏式），不占导航路由。
                    LaunchedEffect(onboarded) {
                        if (onboarded == false) tutorialViewModel.start()
                    }

                    // 引导从首页开始：重看教程时可能正停在设置二级页，先拉回首页；
                    // 已在首页时 launchSingleTop 不会叠栈
                    LaunchedEffect(tutorialState.active) {
                        if (!tutorialState.active) return@LaunchedEffect
                        navController.navigate(Routes.HOME) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }

                    // 某一步声明了「用户点了哪个 Tab 就前进」——由真实路由变化驱动，
                    // 用户点的是真控件而不是教程按钮
                    LaunchedEffect(currentRoute, tutorialState.active, tutorialState.stepIndex) {
                        tutorialViewModel.onRouteChanged(currentRoute)
                    }

                    // 去某个 Tab 根页：必须和底部 Tab 用同一套选项，否则连点会叠栈
                    val navigateToTab: (String) -> Unit = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }

                    // 横滑切 Tab（任务 #10）：左滑 direction=+1 下一项，右滑 -1 上一项；
                    // 边界不循环（getOrNull 越界即放弃）。复用 navigateToTab，
                    // 与点 Tab 完全同一套导航调用 → 交叉淡入转场与状态恢复行为一致
                    val switchTabBySwipe: (Int) -> Unit = { direction ->
                        val index = TAB_ITEMS.indexOfFirst { it.route == currentRoute }
                        TAB_ITEMS.getOrNull(index + direction)?.let { navigateToTab(it.route) }
                    }

                    // 点歌 = 起播 + 进播放页（15 §2：播放页**从任意歌曲行进入**）。
                    // 已经在播放页时不再叠栈（连点歌曲行只换歌，不重复入栈）
                    val openPlayer: () -> Unit = {
                        if (currentRoute != Routes.PLAYER) {
                            navController.navigate(Routes.PLAYER) { launchSingleTop = true }
                        }
                    }

                    // 软链接起播（搜索框粘贴 / 外部 deeplink）→ 立刻进播放页
                    LaunchedEffect(openPlayerRequest) {
                        if (openPlayerRequest > 0) openPlayer()
                    }

                    // 二级页统一入口：`launchSingleTop` 保证「连点两下」不会叠两层同一页
                    // （用户 2026-10-05 反馈：设置页连点两下会出两次，要退两次）
                    val openDetail: (String) -> Unit = { route ->
                        navController.navigate(route) { launchSingleTop = true }
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                // 任务 #10：仅 5 个 Tab 根页挂横滑手势（二级页 isTabRoot=false 不挂）
                                .tabSwipeSwitchTab(enabled = isTabRoot, onSwitch = switchTabBySwipe)
                                .onSizeChanged { contentSize = it },
                        ) {
                            NavHost(
                                navController = navController,
                                startDestination = Routes.HOME,
                                modifier = Modifier.fillMaxSize(),
                                // 默认 = Tab 切换：交叉淡入淡出（不做横向滑动，避免与返回手势冲突）
                                enterTransition = { fadeIn(tween(LXMotion.tabCrossFade)) },
                                exitTransition = { fadeOut(tween(LXMotion.tabCrossFade)) },
                                // 返回：当前页右滑出，被返回的那页轻微左移（视差 28%）
                                popEnterTransition = {
                                    slideInHorizontally(tween(LXMotion.exitDetail)) { full ->
                                        -(full * LXMotion.parallaxReturn).toInt()
                                    }
                                },
                                popExitTransition = {
                                    slideOutHorizontally(tween(LXMotion.exitDetail)) { full -> full }
                                },
                            ) {
                                composable(Routes.HOME) {
                                    HomeScreen(
                                        onOpenSearch = { keyword -> openDetail(Routes.search(keyword)) },
                                        onScanFolder = { openDetail(Routes.library(LocalLibraryAction.SCAN)) },
                                        onImportMusic = { openDetail(Routes.library(LocalLibraryAction.IMPORT)) },
                                        onOpenPlaylists = { navigateToTab(Routes.PLAYLISTS) },
                                        onOpenPlaylist = { id -> openDetail(Routes.playlistDetail(id)) },
                                        onOpenArtist = { name ->
                                            openDetail(artistAlbumRoute(ListKind.ARTIST.id, name))
                                        },
                                        onOpenDownloadManager = { openDetail(Routes.DOWNLOAD) },
                                        onOpenPlayer = { openPlayer() },
                                    )
                                }

                                composable(Routes.PLAYLISTS) {
                                    val playlistsViewModel: PlaylistsViewModel = hiltViewModel()
                                    val state by playlistsViewModel.uiState.collectAsStateWithLifecycle()
                                    PlaylistsScreen(
                                        state = state,
                                        onQueryChange = playlistsViewModel::onQueryChange,
                                        onFilterChange = playlistsViewModel::onFilterChange,
                                        onLayoutChange = playlistsViewModel::onLayoutChange,
                                        onCreatePlaylist = playlistsViewModel::createPlaylist,
                                        onOpenPlaylist = { id ->
                                            openDetail(Routes.playlistDetail(id))
                                        },
                                        onRenamePlaylist = playlistsViewModel::renamePlaylist,
                                        onDeletePlaylist = playlistsViewModel::deletePlaylist,
                                        onReorderPlaylists = playlistsViewModel::reorderPlaylists,
                                        // 组合播放 · 临时混合：合并去重后的队列直接起播（不入库）
                                        onPlayCombined = { ids ->
                                            playlistsViewModel.loadCombinedSongs(ids) { songs ->
                                                songs.firstOrNull()?.let { first ->
                                                    playerViewModel.play(first, songs)
                                                    openPlayer()
                                                }
                                            }
                                        },
                                        // 组合播放 · 真合并：落库为新歌单，完成后跳详情页确认
                                        onMergePlaylists = { ids, name, onCreated ->
                                            playlistsViewModel.mergePlaylists(ids, name, onCreated)
                                        },
                                    )
                                }

                                composable(
                                    route = Routes.PLAYLIST_DETAIL,
                                    // 进详情：从右侧滑入（300ms）
                                    enterTransition = {
                                        slideInHorizontally(tween(LXMotion.enterDetail)) { it }
                                    },
                                    exitTransition = { fadeOut(tween(LXMotion.quick)) },
                                ) {
                                    PlaylistDetailScreen(
                                        onBack = { navController.popBackStack() },
                                        onPlaySong = { song, queue ->
                                            playerViewModel.play(song, queue)
                                            openPlayer()
                                        },
                                        onPlayShuffle = { songs ->
                                            // 真随机：每次重新取时间种子，不用固定序列（用户 2026-10-02 要求）
                                            val shuffled = FlowRandom.shuffled(songs)
                                            shuffled.firstOrNull()?.let { first ->
                                                playerViewModel.play(first, shuffled)
                                                openPlayer()
                                            }
                                        },
                                        // 播放中的行要高亮 + 显示均衡条（mockup 的 .song.playing）
                                        playingKey = playerState.current?.let {
                                            "${it.source.value}:${it.id}"
                                        },
                                    )
                                }

                                composable(Routes.STATS) {
                                    StatsScreen(
                                        onPlaySong = { song, queue ->
                                            playerViewModel.play(song, queue)
                                            openPlayer()
                                        },
                                        onOpenScope = { scope, key ->
                                            openDetail(statsListRoute(scope, key))
                                        },
                                    )
                                }

                                composable(
                                    route = Routes.STATS_LIST,
                                    arguments = listOf(
                                        navArgument(ARG_STATS_LIST_KIND) {
                                            type = NavType.StringType
                                            defaultValue = ""
                                        },
                                        navArgument(ARG_STATS_LIST_KEY) {
                                            type = NavType.StringType
                                            defaultValue = ""
                                        },
                                    ),
                                    // 进详情：从右侧滑入（300ms），与歌单详情同一套
                                    enterTransition = {
                                        slideInHorizontally(tween(LXMotion.enterDetail)) { it }
                                    },
                                    exitTransition = { fadeOut(tween(LXMotion.quick)) },
                                ) {
                                    StatsListScreen(
                                        onBack = { navController.popBackStack() },
                                        onPlaySong = { song, queue ->
                                            playerViewModel.play(song, queue)
                                            openPlayer()
                                        },
                                    )
                                }
                                composable(Routes.NOISE) {
                                    NoiseScreen(onNotice = postToast)
                                }
                                composable(Routes.SETTINGS) {
                                    // M3 任务 9 第一级：分类 hub（每行带状态摘要）+ 跨分类搜索
                                    SettingsHubScreen(
                                        onOpenCategory = {
                                            openDetail(Routes.settingsCategory(it))
                                        },
                                        onNotice = postToast,
                                    )
                                }

                                composable(
                                    route = Routes.SETTINGS_CATEGORY,
                                    arguments = listOf(
                                        navArgument(Routes.ARG_SETTINGS_CAT) {
                                            type = NavType.StringType
                                            defaultValue = ""
                                        },
                                    ),
                                    // 进详情：从右侧滑入，与歌单/统计榜单同一套
                                    enterTransition = {
                                        slideInHorizontally(tween(LXMotion.enterDetail)) { it }
                                    },
                                    exitTransition = { fadeOut(tween(LXMotion.quick)) },
                                ) { entry ->
                                    // M3 任务 9 第二级：分类详情（返回回 hub，15 §6.5）
                                    SettingsCategoryScreen(
                                        categoryKey = entry.arguments
                                            ?.getString(Routes.ARG_SETTINGS_CAT)
                                            .orEmpty(),
                                        onBack = { navController.popBackStack() },
                                        onScanFolder = {
                                            openDetail(Routes.library(LocalLibraryAction.SCAN))
                                        },
                                        onImportMusic = {
                                            openDetail(Routes.library(LocalLibraryAction.IMPORT))
                                        },
                                        onOpenAbout = { openDetail(Routes.ABOUT) },
                                        onOpenEq = { openDetail(Routes.EQ) },
                                        onReplayTutorial = { tutorialViewModel.start() },
                                        onOpenVerseGroup = { openDetail(Routes.verseGroup(it)) },
                                        onNotice = postToast,
                                    )
                                }

                                composable(
                                    route = Routes.VERSE_GROUP,
                                    arguments = listOf(
                                        navArgument(Routes.ARG_VERSE_GROUP) {
                                            type = NavType.StringType
                                            defaultValue = ""
                                        },
                                    ),
                                    // 进详情：从右侧滑入，与设置分类同一套
                                    enterTransition = {
                                        slideInHorizontally(tween(LXMotion.enterDetail)) { it }
                                    },
                                    exitTransition = { fadeOut(tween(LXMotion.quick)) },
                                ) { entry ->
                                    VerseGroupScreen(
                                        groupId = entry.arguments
                                            ?.getString(Routes.ARG_VERSE_GROUP)
                                            .orEmpty(),
                                        onBack = { navController.popBackStack() },
                                        onNotice = postToast,
                                    )
                                }

                                composable(
                                    route = Routes.EQ,
                                    // 进详情：从右侧滑入，与歌单/设置分类同一套
                                    enterTransition = {
                                        slideInHorizontally(tween(LXMotion.enterDetail)) { it }
                                    },
                                    exitTransition = { fadeOut(tween(LXMotion.quick)) },
                                ) {
                                    EqScreen(
                                        onBack = { navController.popBackStack() },
                                        // 17 §5.7：网格 → 详情两级，不做同屏
                                        onOpenCollection = { id ->
                                            openDetail(eqCollectionRoute(id))
                                        },
                                    )
                                }

                                composable(
                                    route = Routes.EQ_COLLECTION,
                                    arguments = listOf(
                                        navArgument(ARG_EQ_COLLECTION_ID) {
                                            type = NavType.StringType
                                            defaultValue = ""
                                        },
                                    ),
                                    // 进详情：从右侧滑入，与歌单/统计榜单同一套
                                    enterTransition = {
                                        slideInHorizontally(tween(LXMotion.enterDetail)) { it }
                                    },
                                    exitTransition = { fadeOut(tween(LXMotion.quick)) },
                                ) {
                                    EqCollectionScreen(onBack = { navController.popBackStack() })
                                }

                                composable(Routes.DOWNLOAD) {
                                    DownloadScreen(
                                        onBack = { navController.popBackStack() },
                                        onPlaySong = { song, queue ->
                                            playerViewModel.play(song, queue)
                                            openPlayer()
                                        },
                                        onNotice = postToast,
                                    )
                                }

                                composable(
                                    route = Routes.SEARCH,
                                    arguments = listOf(
                                        navArgument(Routes.ARG_SEARCH_QUERY) {
                                            type = NavType.StringType
                                            defaultValue = ""
                                        },
                                    ),
                                ) {
                                    SearchScreen(
                                        onBack = { navController.popBackStack() },
                                        onSongClick = { song, queue ->
                                            playerViewModel.play(song, queue)
                                            openPlayer()
                                        },
                                        onMoreSong = {
                                            actionFromPlayer = false
                                            actionSong = it
                                            songActions.resetSwap(it)
                                        },
                                        // 「歌单」分组命中本地歌单 → 沿用歌单详情页（两级跳转不破例）
                                        onOpenPlaylist = { id ->
                                            openDetail(Routes.playlistDetail(id))
                                        },
                                        // 歌手 / 专辑卡片 → 详情页（M3 任务 10）
                                        onOpenArtist = { name ->
                                            openDetail(artistAlbumRoute(ListKind.ARTIST.id, name))
                                        },
                                        onOpenAlbum = { name ->
                                            openDetail(artistAlbumRoute(ListKind.ALBUM.id, name))
                                        },
                                    )
                                }

                                composable(
                                    route = Routes.ARTIST_ALBUM,
                                    arguments = listOf(
                                        navArgument(ARG_AA_KIND) {
                                            type = NavType.StringType
                                            defaultValue = ""
                                        },
                                        navArgument(ARG_AA_KEY) {
                                            type = NavType.StringType
                                            defaultValue = ""
                                        },
                                    ),
                                    // 进详情：从右侧滑入，与歌单/统计榜单同一套
                                    enterTransition = {
                                        slideInHorizontally(tween(LXMotion.enterDetail)) { it }
                                    },
                                    exitTransition = { fadeOut(tween(LXMotion.quick)) },
                                ) {
                                    ArtistAlbumScreen(
                                        onBack = { navController.popBackStack() },
                                        onPlaySong = { song, queue ->
                                            playerViewModel.play(song, queue)
                                            openPlayer()
                                        },
                                        onMoreSong = {
                                            actionFromPlayer = false
                                            actionSong = it
                                            songActions.resetSwap(it)
                                        },
                                    )
                                }
                                composable(
                                    route = Routes.LIBRARY,
                                    arguments = listOf(
                                        navArgument(ARG_LIBRARY_ACTION) {
                                            type = NavType.StringType
                                            defaultValue = ""
                                        },
                                    ),
                                ) { entry ->
                                    LocalLibraryScreen(
                                        onSongClick = { song, queue ->
                                            playerViewModel.play(song, queue)
                                            openPlayer()
                                        },
                                        initialAction = entry.arguments
                                            ?.getString(ARG_LIBRARY_ACTION)
                                            .orEmpty(),
                                    )
                                }
                                composable(
                                    route = Routes.PLAYER,
                                    // 打开播放页：从底部滑入全屏（对齐「从迷你条上拉」的直觉）
                                    enterTransition = {
                                        slideInVertically(tween(LXMotion.playerEnter)) { it }
                                    },
                                    // 关闭播放页：向下滑出
                                    popExitTransition = {
                                        slideOutVertically(tween(LXMotion.playerExit)) { it }
                                    },
                                ) {
                                    // 歌词 VM 挂在播放页的路由作用域上：只在打开播放页时取词
                                    val lyricsViewModel: LyricsViewModel = hiltViewModel()
                                    val lyricsState by lyricsViewModel.uiState
                                        .collectAsStateWithLifecycle()
                                    PlayerScreen(
                                        state = playerState,
                                        lyrics = lyricsState,
                                        onBack = { navController.popBackStack() },
                                        onTogglePlay = playerViewModel::togglePlayPause,
                                        onPrevious = playerViewModel::previous,
                                        onNext = playerViewModel::next,
                                        onSeek = playerViewModel::seekTo,
                                        onMoreSong = {
                                            actionFromPlayer = true
                                            actionSong = it
                                            songActions.resetSwap(it)
                                        },
                                        onDownloadSong = { song, quality ->
                                            songActions.download(song, quality)
                                        },
                                        onNotice = postToast,
                                        // 歌词长按分享：弹分享弹窗（贴标签 + 生成带时间戳的深链接 + 系统分享）
                                        onShareLine = { song, line ->
                                            shareSongForLine = song
                                            shareLineForDialog = line
                                            // 默认预填当前行歌词——用户可以改成自己想说的话
                                            shareTagInput = line.text.take(40)
                                        },
                                        // 定时关闭提示条（17 §7）：一键取消
                                        songTimerText = songTimerText,
                                        onCancelSongTimer = {
                                            playerViewModel.cancelSongTimer()
                                            postToast("定时关闭已取消")
                                        },
                                        pageViewModel = playerPageViewModel,
                                        crossfadeMs = playerViewModel.crossfadeMs,
                                    )
                                }
                                composable(Routes.ABOUT) {
                                    AboutScreen(onBack = { navController.popBackStack() })
                                }
                            }

                            // ════════════════════════════════════════════════════════
                            // 歌词长按分享弹窗：贴标签 → 生成带时间戳的深链接 → 系统分享
                            // ════════════════════════════════════════════════════════
                            val shareSong = shareSongForLine
                            val shareLine = shareLineForDialog
                            if (shareSong != null && shareLine != null) {
                                // 生成分享协议链接（带时间戳 + 标签）
                                val sharePayload = remember(shareSong, shareLine, shareTagInput) {
                                    com.lxpro.core.model.SongLink.SharePayload(
                                        title = shareSong.name,
                                        artist = shareSong.singer,
                                        timestampMs = shareLine.timeMs,
                                        tag = shareTagInput.trim(),
                                    )
                                }
                                val shareLink = remember(sharePayload) {
                                    com.lxpro.core.model.SongLink.encodeShare(sharePayload)
                                }
                                val previewText = remember(shareTemplateText, shareSong, shareLink) {
                                    settings.fillShareTemplate(
                                        shareTemplateText,
                                        shareSong.name,
                                        shareSong.singer,
                                        shareLink,
                                    )
                                }
                                androidx.compose.material3.AlertDialog(
                                    onDismissRequest = {
                                        shareSongForLine = null
                                        shareLineForDialog = null
                                        shareTagInput = ""
                                    },
                                    title = { androidx.compose.material3.Text("分享这句歌词") },
                                    text = {
                                        androidx.compose.foundation.layout.Column {
                                            androidx.compose.material3.Text(
                                                text = "当前行：「${shareLine.text}」",
                                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            androidx.compose.material3.OutlinedTextField(
                                                value = shareTagInput,
                                                onValueChange = { shareTagInput = it },
                                                label = { androidx.compose.material3.Text("给它贴个标签（说点什么）") },
                                                placeholder = { androidx.compose.material3.Text("此刻的心情、想对TA说的话…") },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                            )
                                            androidx.compose.material3.Text(
                                                text = "分享预览：",
                                                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                                                color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                                            )
                                            androidx.compose.material3.Text(
                                                text = previewText,
                                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(bottom = 4.dp),
                                            )
                                            androidx.compose.material3.Text(
                                                text = shareLink,
                                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                                color = androidx.compose.material3.MaterialTheme.colorScheme.tertiary,
                                            )
                                        }
                                    },
                                    confirmButton = {
                                        androidx.compose.material3.TextButton(
                                            onClick = {
                                                val intent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, previewText)
                                                    putExtra(Intent.EXTRA_TITLE, "分享歌词")
                                                }
                                                runCatching {
                                                    startActivity(Intent.createChooser(intent, "分享到"))
                                                }
                                                postToast("已打开系统分享面板")
                                                shareSongForLine = null
                                                shareLineForDialog = null
                                                shareTagInput = ""
                                            },
                                        ) {
                                            androidx.compose.material3.Text("分享")
                                        }
                                    },
                                    dismissButton = {
                                        androidx.compose.material3.TextButton(
                                            onClick = {
                                                runCatching {
                                                    copyToClipboard(context, "阿玉音乐分享", previewText)
                                                }
                                                postToast("已复制到剪贴板")
                                                shareSongForLine = null
                                                shareLineForDialog = null
                                                shareTagInput = ""
                                            },
                                        ) {
                                            androidx.compose.material3.Text("复制文案")
                                        }
                                    },
                                )
                            }

                            // 全局轻提示：搜索页/播放页的操作回执都走这里
                            // （浮在内容区底部，不压到迷你条与 Tab 栏）
                            LXToast(
                                message = toastText,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 24.dp),
                            )

                            // 下载悬浮球：有排队/下载中的任务才出现，全部结束自动消失
                            // （下载管理页自己不需要它，所以那页隐藏）
                            if (downloadIndicator.activeCount > 0 && currentRoute != Routes.DOWNLOAD) {
                                DownloadBall(
                                    activeCount = downloadIndicator.activeCount,
                                    progress = downloadIndicator.progress,
                                    bounds = contentSize,
                                    savedPosition = ballPosition,
                                    onClick = { openDetail(Routes.DOWNLOAD) },
                                    onPositionSettled = { x, y ->
                                        downloadIndicatorViewModel.saveBallPosition(x, y)
                                    },
                                )
                            }
                        }

                        // 迷你条：有歌才出现；播放页不带（避免重复）；
                        // 键盘打开时也藏（见 isTabRoot 处注释）
                        playerState.current?.let { song ->
                            if (currentRoute != Routes.PLAYER && !imeVisible) {
                                LXMiniPlayerBar(
                                    title = song.name,
                                    subtitle = song.singer,
                                    coverUrl = song.picUrl,
                                    isPlaying = playerState.isPlaying,
                                    isBuffering = playerState.isBuffering,
                                    onTogglePlay = playerViewModel::togglePlayPause,
                                    onPrevious = playerViewModel::previous,
                                    onNext = playerViewModel::next,
                                    onOpenPlayer = { openPlayer() },
                                    // M4 批二：白噪音在响时封面右上角 19px 角标（17 §4 跨页面状态指示）
                                    noiseActive = noiseEngineState.activeSourceIds.isNotEmpty(),
                                )
                            }
                        }

                        if (isTabRoot && !imeVisible) {
                            LXBottomBar(
                                items = TAB_ITEMS,
                                selectedRoute = currentRoute,
                                onSelect = { item ->
                                    // 每个 Tab 记住自己的返回栈（15 §3）：从歌单详情返回应回到歌单 Tab
                                    navigateToTab(item.route)
                                },
                            )
                        }
                    }

                    // 跨页面共用的「歌曲操作」弹层（歌单详情页有自己的那份，多出歌单上下文动作）
                    actionSong?.let { song ->
                        LXSongActionSheet(
                            song = song,
                            sourceLabel = sourceNames[song.source.value] ?: song.source.value,
                            actions = if (actionFromPlayer) PLAYER_SONG_ACTIONS else CROSS_PAGE_SONG_ACTIONS,
                            lyric3dOn = if (actionFromPlayer) pageState.lyric3d else null,
                            onToggleLyric3d = {
                                playerPageViewModel.onToggle3d()
                                postToast(
                                    if (pageState.lyric3d) "已回到平面歌词：四态连续淡化"
                                    else "立体歌词已开：整块歌词板倾斜入深，当前句逐字点亮",
                                )
                            },
                            onOpenLyricSettings = { playerPageViewModel.onP3dSheetOpenChange(true) },
                            crossfadeMs = if (actionFromPlayer) playerViewModel.crossfadeMs else null,
                            onSetCrossfadeMs = { ms ->
                                playerViewModel.setCrossfadeMs(ms)
                                postToast(
                                    if (ms == 0L) "已切换为即切（无淡变）"
                                    else "切换淡变已设为 ${ms / 1000} 秒：上一首渐弱、下一首渐强同步重叠",
                                )
                            },
                            playbackSpeed = if (actionFromPlayer) playerViewModel.playbackSpeed else null,
                            onSetPlaybackSpeed = { speed ->
                                playerViewModel.setSpeed(speed)
                                postToast(
                                    if (speed == 1.0f) "已恢复正常速度"
                                    else "倍速播放已设为 ${speed}x：${
                                        when {
                                            speed < 1.0f -> "慢放重温"
                                            speed > 1.5f -> "快听精华"
                                            else -> "稍稍加快"
                                        }
                                    }",
                                )
                            },
                            songTimerTarget = songTimerTargetOf(songTimer),
                            songTimerCustomMinutes = songTimerCustomMinutes(songTimer),
                            songTimerStopNoise = songTimer?.stopNoiseToo ?: true,
                            songTimerFinishSongFirst = songTimer?.finishSongFirst ?: false,
                            onSetSongTimer = { target, stopNoise, customMins, finishFirst ->
                                applySongTimer(playerViewModel, target, stopNoise, customMins, finishFirst)
                                postToast(
                                    when {
                                        target == LXSongTimerTarget.OFF -> "定时关闭已取消"
                                        target == LXSongTimerTarget.CURRENT_TRACK -> "已设定：播完当前歌曲后停止"
                                        target == LXSongTimerTarget.HOUR_7 -> "已设定：到早上 7:00 停止播放"
                                        target == LXSongTimerTarget.CUSTOM && customMins != null -> {
                                            val base = "已设定：$customMins 分钟后停止播放"
                                            if (finishFirst) "$base（等当前歌播完再停）" else base
                                        }
                                        else -> {
                                            val base = "已设定：${target.shortLabel()}后停止播放"
                                            if (finishFirst) "$base（等当前歌播完再停）" else base
                                        }
                                    },
                                )
                            },
                            playlistOptions = playlistOptions,
                            swapState = swapState,
                            swapSourceNames = sourceNames,
                            onOpenSwapPanel = { songActions.loadSwapCandidates() },
                            swapPreviewKey = swapPreview.key,
                            swapPreviewLoading = swapPreview.loading,
                            onPreviewToggle = { songActions.toggleSwapPreview(it) },
                            onPickSwap = { target ->
                                songActions.pinSwap(target)
                                // 正在播放这首歌：立即用新源重播使其生效（对齐 LX）
                                if (playerState.current?.let { it.id == song.id && it.source == song.source } == true) {
                                    val toggled = song.copy(toggleSong = target)
                                    playerViewModel.play(
                                        toggled,
                                        playerViewModel.queue.value.map {
                                            if (it.id == song.id && it.source == song.source) toggled else it
                                        },
                                    )
                                }
                            },
                            onClearSwap = {
                                songActions.clearSwap()
                                if (playerState.current?.let { it.id == song.id && it.source == song.source } == true) {
                                    val restored = song.copy(toggleSong = null)
                                    playerViewModel.play(
                                        restored,
                                        playerViewModel.queue.value.map {
                                            if (it.id == song.id && it.source == song.source) restored else it
                                        },
                                    )
                                }
                            },
                            noiseActive = if (actionFromPlayer) noiseEngineState.activeSourceIds.isNotEmpty() else null,
                            // 「白噪音」项：关掉弹层后切到白噪音 Tab（17 §4 一级页；不往播放页塞控件）
                            onOpenNoise = { navigateToTab(Routes.NOISE) },
                            // 「均衡器」项：关掉弹层后进均衡器二级页（12 §4 要求播放页也可达）
                            onOpenEqualizer = { openDetail(Routes.EQ) },
                            onDismiss = {
                                songActions.stopSwapPreview()
                                actionSong = null
                            },
                            onAddToPlaylist = { id ->
                                val name = playlistOptions.firstOrNull { it.id == id }?.label.orEmpty()
                                songActions.addToPlaylist(id, name, song)
                            },
                            onCreatePlaylistAndAdd = { songActions.createPlaylistAndAdd(song, it) },
                            onDownload = { quality -> songActions.download(song, quality) },
                            onRenameSong = { songActions.renameSong(song, it) },
                            onRenameSinger = { songActions.renameSinger(song, it) },
                            // 标签页（第 8 项）：关掉本弹层后由 app 级宿主接管
                            onOpenTags = { SongTagBus.emit(song) },
                            onShare = { shareSong(context, song) },
                        )
                    }

                    // 歌曲标签页（第 8 项）：全局唯一宿主。
                    // 搜索 / 播放 / 统计 / 歌单详情任何一处「更多 → 标签」都经 SongTagBus 落到这里。
                    SongTagsSheet(
                        state = tagsState,
                        message = tagsMessage,
                        onAddTag = tagsViewModel::addTag,
                        onRemoveTag = tagsViewModel::removeTag,
                        onMessageShown = tagsViewModel::clearMessage,
                        onDismiss = tagsViewModel::dismiss,
                    )

                    // 新手引导遮罩：盖在**真实界面**上（挖洞高亮真控件），
                    // 必须最后挂，才能压住 Tab 栏 / 迷你条 / 弹层
                    TutorialOverlay(
                        state = tutorialState,
                        viewModel = tutorialViewModel,
                        onFinish = { appViewModel.markOnboarded() },
                    )
                }
            }
            }
        }
    }
}

/** 搜索结果/播放页没有歌单上下文，故不含「移除 / 移动位置」 */
private val CROSS_PAGE_SONG_ACTIONS = listOf(
    LXSongAction.DOWNLOAD,
    LXSongAction.ADD_TO_PLAYLIST,
    LXSongAction.SWAP_SOURCE,
    LXSongAction.RENAME_SONG,
    LXSongAction.RENAME_SINGER,
    LXSongAction.TAGS,
    LXSongAction.SHARE,
)

/** 播放页的「更多」：在最前面多出「立体歌词 / 歌词参数 / 切换淡变 / 倍速播放 / 定时关闭 / 白噪音 / 均衡器」（17 §4/§7、12 §4） */
private val PLAYER_SONG_ACTIONS = listOf(
    LXSongAction.LYRIC_3D,
    LXSongAction.LYRIC_SETTINGS,
    LXSongAction.CROSSFADE,
    LXSongAction.SPEED,
    LXSongAction.TIMER,
    LXSongAction.NOISE,
    LXSongAction.EQUALIZER,
) + CROSS_PAGE_SONG_ACTIONS

/**
 * 设计层定时选项 → 实际设定（17 §7；OFF=取消）。
 *
 * [customMinutes] 仅在 target=CUSTOM 时有值（1..720）。
 * [finishSongFirst] 是问题2 的开关：到点后等歌曲播完再停（SongSleepTimer 内部走
 * PlayerControllerImpl 的 stopAfterCurrentTrack 拦截，避免播下一首的竞态）。
 */
private fun applySongTimer(
    vm: PlayerViewModel,
    target: LXSongTimerTarget,
    stopNoise: Boolean,
    customMinutes: Int? = null,
    finishSongFirst: Boolean = false,
) {
    when (target) {
        LXSongTimerTarget.OFF -> vm.cancelSongTimer()
        LXSongTimerTarget.CUSTOM -> customMinutes?.let {
            vm.setSongTimerAfterMinutes(it, stopNoise, finishSongFirst)
        }
        LXSongTimerTarget.MIN_15 -> vm.setSongTimerAfterMinutes(15, stopNoise, finishSongFirst)
        LXSongTimerTarget.MIN_30 -> vm.setSongTimerAfterMinutes(30, stopNoise, finishSongFirst)
        LXSongTimerTarget.MIN_45 -> vm.setSongTimerAfterMinutes(45, stopNoise, finishSongFirst)
        LXSongTimerTarget.MIN_60 -> vm.setSongTimerAfterMinutes(60, stopNoise, finishSongFirst)
        LXSongTimerTarget.MIN_90 -> vm.setSongTimerAfterMinutes(90, stopNoise, finishSongFirst)
        LXSongTimerTarget.CURRENT_TRACK -> vm.setSongTimerAfterCurrentTrack(stopNoise)
        LXSongTimerTarget.HOUR_7 -> vm.setSongTimerUntilHour(7, stopNoise, finishSongFirst)
    }
}

/** 持久化的定时状态 → 设计层选项（菜单「当前」标记用） */
private fun songTimerTargetOf(entity: TimerStateEntity?): LXSongTimerTarget = when (entity?.targetKind) {
    TimerStateEntity.TargetKind.AFTER_MINUTES.id -> when (entity.valueMinutes) {
        15 -> LXSongTimerTarget.MIN_15
        30 -> LXSongTimerTarget.MIN_30
        45 -> LXSongTimerTarget.MIN_45
        60 -> LXSongTimerTarget.MIN_60
        90 -> LXSongTimerTarget.MIN_90
        // 不在预设列表里 → CUSTOM（问题1）
        else -> if (entity.valueMinutes != null) LXSongTimerTarget.CUSTOM else LXSongTimerTarget.OFF
    }
    TimerStateEntity.TargetKind.AFTER_CURRENT_TRACK.id -> LXSongTimerTarget.CURRENT_TRACK
    TimerStateEntity.TargetKind.UNTIL_HOUR.id -> LXSongTimerTarget.HOUR_7
    else -> LXSongTimerTarget.OFF
}

/** 持久化定时状态里的自定义分钟数（CUSTOM 项显示用） */
private fun songTimerCustomMinutes(entity: TimerStateEntity?): Int? = when (entity?.targetKind) {
    TimerStateEntity.TargetKind.AFTER_MINUTES.id -> entity.valueMinutes
    else -> null
}

private const val TOAST_DURATION_MS = 2_400L

/** 播放错误提示停留更久：文案带「去哪导入脚本」这类可操作信息 */
private const val ERROR_TOAST_DURATION_MS = 4_500L

/** 横滑切 Tab 的水平位移阈值（48~64dp 量级，取 56dp） */
private val TAB_SWIPE_THRESHOLD = 56.dp

/**
 * 任务 #10：Tab 根页横滑切换手势。
 *
 * - Main pass 观察（子节点先于本容器拿到事件），只累计**未被消费**的位移：
 *   首页/统计页横向 LazyRow、垂直列表、下载悬浮球消费过的事件一律跳过，
 *   所以货架自己滑动、垂直滚动都不会算进横滑；
 * - 拖动全程不消费事件，避免抢走页内既有手势；**松手时才判定**：
 *   水平累计 ≥ 56dp 且 |dx| > 2×|dy| 才触发，左滑 direction=+1、右滑 -1；
 * - 只跟踪第一根手指，多指触控直接忽略。
 */
@Composable
private fun Modifier.tabSwipeSwitchTab(
    enabled: Boolean,
    onSwitch: (Int) -> Unit,
): Modifier {
    if (!enabled) return this
    // 手势协程里始终读最新的 onSwitch（它捕获了 currentRoute，重组后会换新实例）
    val currentOnSwitch by rememberUpdatedState(onSwitch)
    return pointerInput(Unit) {
        val thresholdPx = TAB_SWIPE_THRESHOLD.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            var dragX = 0f
            var dragY = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break // 松手，结束本轮累计
                // 子节点消费过（横向货架滚动、垂直列表滚动、悬浮球拖动）的事件不计入
                if (!change.isConsumed) {
                    val delta = change.positionChange()
                    dragX += delta.x
                    dragY += delta.y
                }
            }
            if (abs(dragX) >= thresholdPx && abs(dragX) > 2 * abs(dragY)) {
                currentOnSwitch(if (dragX < 0) 1 else -1)
            }
        }
    }
}