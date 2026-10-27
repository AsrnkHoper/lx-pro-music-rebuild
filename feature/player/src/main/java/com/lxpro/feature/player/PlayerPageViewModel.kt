package com.lxpro.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.media.PlayerController
import com.lxpro.core.media.RepeatMode
import com.lxpro.core.model.CommentInfo
import com.lxpro.core.model.Song
import com.lxpro.core.playlist.PlaylistRepository
import com.lxpro.source.api.SourceRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** 播放页主体：封面模式 / 歌词模式（原型 `#coverMode` / `#lyrMode`） */
@Serializable
enum class PlayerPageMode { COVER, LYRICS }

/** 歌词对齐（原型面板的 `data-lyr-align`，同时决定立体档的旋转轴与消失点） */
enum class LyricAlign(val id: String, val label: String) {
    LEFT("left", "左"),
    CENTER("center", "居中"),
    RIGHT("right", "右"),
}

/**
 * 立体歌词参数。
 *
 * ⚠️ 默认值逐项取自权威稿 `mockup_app.html` 的 `P3D_DEFAULTS`（3010-3021 行附近），
 * 范围与步进取自同一份 `P3D_FIELDS`；**不含「可见行数」**（14 §4.4:204 明确不做该参数，
 * 内部固定上限 5 —— `maxDepth`）。
 */
@Serializable
data class Lyric3dParams(
    val tiltOn: Boolean = true,
    val tilt: Float = 18f,
    val pitch: Float = 3f,
    val persp: Float = 1200f,
    val blurStep: Float = 1.2f,
    val dimBase: Float = 0.30f,
    val opDecay: Float = 0.18f,
    val fontSize: Float = 24f,
    val scrollDur: Float = 0.55f,
    val blurDur: Float = 0.50f,
) {
    companion object {
        /** 景深内部上限（`P3D.maxDepth`）：超过这个距离的行都按最远处理 */
        const val MAX_DEPTH = 5
    }
}

/**
 * 参数面板的一项（`P3D_FIELDS` 的 Kotlin 版）。
 * 分组不再用「空间/视觉/动画」，按 [shared] 划成「通用（平面与立体一起变）/ 立体档专用」
 * （2026-10-24 用户要求）。
 */
enum class P3dParam(
    val label: String,
    val min: Float,
    val max: Float,
    val step: Float,
    /** 是否**同时作用于平面档**（2026-10-03 用户要求：共用的要一起变） */
    val shared: Boolean = false,
) {
    TILT("后推角度", 0f, 40f, 1f),
    // 上翘角度：正负 10°（负值=反向翘起，2026-10-03 用户要求）
    PITCH("上翘角度", -10f, 10f, 0.5f),
    PERSP("透视距离", 400f, 3000f, 50f),
    // 模糊强度与透明度衰减：2026-10-24 用户裁决**共用**（平面档也按距离做连续衰减与近处模糊）
    BLUR_STEP("模糊强度", 0f, 3f, 0.1f, shared = true),
    OP_DECAY("透明度衰减", 0.05f, 0.4f, 0.01f, shared = true),
    FONT_SIZE("基准字号", 16f, 34f, 1f, shared = true),
    DIM_BASE("非当前行基准亮度", 0.05f, 1f, 0.01f, shared = true),
    SCROLL_DUR("滚动时长", 0.15f, 1.2f, 0.05f, shared = true),
    BLUR_DUR("模糊过渡时长", 0.1f, 1.2f, 0.05f, shared = true),
    ;

    fun value(params: Lyric3dParams): Float = when (this) {
        TILT -> params.tilt
        PITCH -> params.pitch
        PERSP -> params.persp
        BLUR_STEP -> params.blurStep
        OP_DECAY -> params.opDecay
        FONT_SIZE -> params.fontSize
        DIM_BASE -> params.dimBase
        SCROLL_DUR -> params.scrollDur
        BLUR_DUR -> params.blurDur
    }

    fun with(params: Lyric3dParams, value: Float): Lyric3dParams = when (this) {
        TILT -> params.copy(tilt = value)
        PITCH -> params.copy(pitch = value)
        PERSP -> params.copy(persp = value)
        BLUR_STEP -> params.copy(blurStep = value)
        OP_DECAY -> params.copy(opDecay = value)
        FONT_SIZE -> params.copy(fontSize = value)
        DIM_BASE -> params.copy(dimBase = value)
        SCROLL_DUR -> params.copy(scrollDur = value)
        BLUR_DUR -> params.copy(blurDur = value)
    }

    /** 面板上显示的数值（与原型 `fmt` 一致） */
    fun format(value: Float): String = when (this) {
        TILT, PITCH -> "${value.toInt()}°"
        PERSP -> "${value.toInt()}px"
        BLUR_STEP -> "%.1fpx".format(value)
        FONT_SIZE -> "${value.toInt()}px"
        OP_DECAY, DIM_BASE -> "%.2f".format(value)
        SCROLL_DUR, BLUR_DUR -> "%.2fs".format(value)
    }
}

/**
 * 持久化的歌词设置（DataStore 里存这段 JSON）。
 *
 * ⚠️ 2026-10-03 用户要求：① 进播放页直接就是歌词（默认 [mode] = LYRICS）；
 * ② 立体档开关、对齐、翻译、全部参数**退出再进要保留**。
 */
@Serializable
data class LyricSettings(
    val mode: PlayerPageMode = PlayerPageMode.LYRICS,
    val lyric3d: Boolean = false,
    val alignId: String = LyricAlign.LEFT.id,
    val translate: Boolean = true,
    val params: Lyric3dParams = Lyric3dParams(),
)

data class PlayerPageState(
    val mode: PlayerPageMode = PlayerPageMode.LYRICS,
    /** 立体歌词默认**关**（14 §4.4:183） */
    val lyric3d: Boolean = false,
    val params: Lyric3dParams = Lyric3dParams(),
    /** 原型默认左对齐（`P3D_DEFAULTS` 之后的 `align = 'left'`） */
    val align: LyricAlign = LyricAlign.LEFT,
    /** 翻译行开关（14 §4.4:174：第二行可关） */
    val translate: Boolean = true,
    val p3dSheetOpen: Boolean = false,
    val queueSheetOpen: Boolean = false,
    val commentSheetOpen: Boolean = false,
)

/**
 * 播放页的**页面状态**（与 playback 状态分开：那些在 `core:media` 的 PlayerState）。
 *
 * 挂播放页路由作用域，所以离开播放页后这些界面偏好归零 —— 除了循环模式，
 * 它在 PlayerController 里是常驻的（读 `state.repeatMode`，不靠界面自己记）。
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class PlayerPageViewModel @Inject constructor(
    private val player: PlayerController,
    private val playlists: PlaylistRepository,
    private val settings: SettingsRepository,
    private val sourceRegistry: SourceRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(PlayerPageState())
    val state: StateFlow<PlayerPageState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private var loaded = false

    init {
        // 读回上次的歌词设置（模式 / 立体档 / 对齐 / 翻译 / 参数）
        viewModelScope.launch {
            val saved = settings.lyricSettings.first()
            if (saved != null) {
                runCatching { json.decodeFromString(LyricSettings.serializer(), saved) }
                    .onSuccess { it.applyTo(_state) }
            }
            loaded = true
        }
        // 任何改动都落盘（滑块拖动会连续触发，防抖 300ms 合并成一次写）
        viewModelScope.launch {
            _state
                .drop(1)
                .debounce(SAVE_DEBOUNCE_MS)
                .collect { state ->
                    if (!loaded) return@collect
                    val payload = LyricSettings(
                        mode = state.mode,
                        lyric3d = state.lyric3d,
                        alignId = state.align.id,
                        translate = state.translate,
                        params = state.params,
                    )
                    settings.setLyricSettings(json.encodeToString(LyricSettings.serializer(), payload))
                }
        }
    }

    /** 当前歌是否已收藏（跟随换歌自动切换） */
    val liked: StateFlow<Boolean> = player.state
        .map { it.current }
        .flatMapLatest { song ->
            if (song == null) flowOf(false) else playlists.observeLiked(song.id, song.source.value)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 播放队列（队列弹层用） */
    val queue: StateFlow<List<Song>> = player.queue

    val currentIndex: StateFlow<Int> = player.currentIndex

    fun onModeChange(mode: PlayerPageMode) {
        _state.value = _state.value.copy(mode = mode)
    }

    fun onToggle3d() {
        _state.value = _state.value.copy(lyric3d = !_state.value.lyric3d)
    }

    fun onToggleTilt() {
        val params = _state.value.params
        _state.value = _state.value.copy(params = params.copy(tiltOn = !params.tiltOn))
    }

    fun onAlignChange(align: LyricAlign) {
        _state.value = _state.value.copy(align = align)
    }

    /**
     * 改参数即时生效。
     * ⚠️ 只有**立体专用**参数（后推/上翘/透视）在平面档下拖动时才自动开启立体——
     * 通用参数（字号/亮度/模糊/透明度/时长）在平面档同样生效，不应切模式
     * （2026-10-24 用户反馈：调通用参数被切到立体档）。
     *
     * ⚠️ 拖「后推角度」滑杆时若「启用 3D 倾斜」开关关着，自动打开——
     * tiltOn=false 时 tiltDeg 恒为 0，拖滑杆完全无反馈
     * （2026-10-24 #100 实机日志定位：用户报「专用参数不生效」即此因）。
     */
    fun onParamChange(param: P3dParam, value: Float) {
        val current = _state.value
        val newParams = param.with(current.params, value).let {
            if (param == P3dParam.TILT && !it.tiltOn) it.copy(tiltOn = true) else it
        }
        _state.value = current.copy(
            lyric3d = if (param.shared) current.lyric3d else true,
            params = newParams,
        )
    }

    /** 恢复默认（`c3dReset`） */
    fun onReset3d() {
        _state.value = _state.value.copy(params = Lyric3dParams())
    }

    fun onToggleTranslate() {
        _state.value = _state.value.copy(translate = !_state.value.translate)
    }

    fun onP3dSheetOpenChange(open: Boolean) {
        _state.value = _state.value.copy(p3dSheetOpen = open)
    }

    fun onQueueSheetOpenChange(open: Boolean) {
        _state.value = _state.value.copy(queueSheetOpen = open)
    }

    /** 喜欢 / 取消喜欢（写「收藏」系统歌单） */
    fun onToggleLike(song: Song) {
        viewModelScope.launch { playlists.toggleLiked(song) }
    }

    /**
     * 播放模式轮换（用户 2026-10-03 定的五档）：
     * 列表循环 → 单曲循环 → 顺序播放 → **随机列表** → **强随机** → 回到列表循环。
     *
     * 两档随机的区别（内核实现见 `PlayerControllerImpl`）：
     * - 随机列表：进这一档时打乱出**一份固定序列**，上一首 / 下一首都沿它走，「上一首」能回到刚才那首
     * - 强随机：每次上一首 / 下一首都重新随机，**没有**固定序列
     */
    fun onCycleRepeat(): RepeatMode {
        val next = when (player.state.value.repeatMode) {
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
            RepeatMode.OFF -> RepeatMode.SHUFFLE_ORDER
            RepeatMode.SHUFFLE_ORDER -> RepeatMode.SHUFFLE_RANDOM
            RepeatMode.SHUFFLE_RANDOM -> RepeatMode.ALL
        }
        player.setRepeatMode(next)
        return next
    }

    /** 长按上滑直接设定某一档（用户 2026-10-05 要求，替代一档一档点） */
    fun onPickRepeat(mode: RepeatMode) {
        player.setRepeatMode(mode)
    }

    // ── 评论（批十九）──

    data class CommentState(
        val loading: Boolean = false,
        val comments: List<CommentInfo> = emptyList(),
        val page: Int = 1,
        val hasMore: Boolean = false,
        val isHot: Boolean = false,
        val error: String? = null,
    )

    private val _commentState = MutableStateFlow(CommentState())
    val commentState: StateFlow<CommentState> = _commentState.asStateFlow()
    private var commentSong: Song? = null

    fun toggleCommentSheet() {
        val open = !_state.value.commentSheetOpen
        _state.value = _state.value.copy(commentSheetOpen = open)
        if (open && _commentState.value.comments.isEmpty()) {
            player.state.value.current?.let { loadComments(it, isHot = false) }
        }
    }

    fun loadComments(song: Song, isHot: Boolean = false) {
        commentSong = song
        _commentState.value = CommentState(loading = true, isHot = isHot)
        viewModelScope.launch {
            val source = sourceRegistry.byId(song.source)
            if (source == null) {
                _commentState.value = CommentState(error = "找不到音源")
                return@launch
            }
            val result = runCatching {
                if (isHot) source.getHotComment(song, page = 1)
                else source.getComment(song, page = 1)
            }.getOrNull()
            if (result == null) {
                _commentState.value = CommentState(error = "获取评论失败", isHot = isHot)
            } else {
                _commentState.value = CommentState(
                    comments = result.comments,
                    page = 1,
                    hasMore = result.page < result.maxPage,
                    isHot = isHot,
                )
            }
        }
    }

    fun loadMoreComments() {
        val current = _commentState.value
        if (current.loading || !current.hasMore) return
        val song = commentSong ?: return
        val nextPage = current.page + 1
        _commentState.value = current.copy(loading = true)
        viewModelScope.launch {
            val source = sourceRegistry.byId(song.source) ?: return@launch
            val result = runCatching {
                if (current.isHot) source.getHotComment(song, page = nextPage)
                else source.getComment(song, page = nextPage)
            }.getOrNull() ?: return@launch
            _commentState.value = current.copy(
                loading = false,
                comments = current.comments + result.comments,
                page = nextPage,
                hasMore = nextPage < result.maxPage,
            )
        }
    }

    fun switchCommentTab(isHot: Boolean) {
        val song = commentSong ?: player.state.value.current ?: return
        if (isHot != _commentState.value.isHot) loadComments(song, isHot)
    }

    private companion object {
        /** 设置落盘的防抖（拖滑块时不要每次 tick 都写一次 DataStore） */
        const val SAVE_DEBOUNCE_MS = 300L
    }

    /** 跳到队列里的某一首（队列弹层点击） */
    fun onPlayAt(index: Int) {
        viewModelScope.launch {
            player.playAt(index)
            _state.value = _state.value.copy(queueSheetOpen = false)
        }
    }
}

/** 把读回的设置贴到页面状态上 */
private fun LyricSettings.applyTo(target: MutableStateFlow<PlayerPageState>) {
    target.value = target.value.copy(
        mode = mode,
        lyric3d = lyric3d,
        align = LyricAlign.entries.firstOrNull { it.id == alignId } ?: LyricAlign.LEFT,
        translate = translate,
        params = params,
    )
}

/** 播放模式的五个档位（长按选择器与轮换共用同一份顺序） */
val PLAY_MODE_ORDER = listOf(
    RepeatMode.ALL,
    RepeatMode.ONE,
    RepeatMode.OFF,
    RepeatMode.SHUFFLE_ORDER,
    RepeatMode.SHUFFLE_RANDOM,
)

/** 循环模式的展示名（轮换时提示用） */
fun RepeatMode.displayName(): String = when (this) {
    RepeatMode.ALL -> "列表循环"
    RepeatMode.ONE -> "单曲循环"
    RepeatMode.OFF -> "顺序播放"
    RepeatMode.SHUFFLE_ORDER -> "随机列表（顺序固定）"
    RepeatMode.SHUFFLE_RANDOM -> "强随机（每次不同）"
}