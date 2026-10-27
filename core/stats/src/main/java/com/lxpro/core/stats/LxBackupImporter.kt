package com.lxpro.core.stats

import com.lxpro.core.database.dao.ArtistIconOverrideDao
import com.lxpro.core.database.dao.NoiseEventDao
import com.lxpro.core.database.dao.SongOverrideDao
import com.lxpro.core.database.dao.SongTagDao
import com.lxpro.core.database.entity.ArtistIconOverrideEntity
import com.lxpro.core.database.entity.LIKED_PLAYLIST_ID
import com.lxpro.core.database.entity.NoiseEventEntity
import com.lxpro.core.database.entity.PlayHistoryEntity
import com.lxpro.core.database.entity.SongOverrideEntity
import com.lxpro.core.database.entity.SongTagEntity
import com.lxpro.core.database.entity.StatsEventEntity
import com.lxpro.core.datastore.PrefSnapshot
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.model.Song
import com.lxpro.core.model.SourceId
import com.lxpro.core.playlist.PlaylistRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.InputStream
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全量备份（单文件）的格式标记与版本：`gzip(JSON)`，文件名建议 `lxpro_backup.lxmc`。
 * 导出器 [LxBackupExporter] 与导入器共用。
 */
internal const val LX_ALL_TYPE = "lxpro_all"
internal const val LX_ALL_VERSION = 1

/**
 * 导入的是哪一种备份。
 *
 * - [UNIFIED]：`lxpro_backup.lxmc`（type `lxpro_all`）—— 本应用**全量备份**（2026-10-27）
 * - [STATS]：`lx_stats.lxmc`（type `lx_stats_data`）—— LX-Pro **新增**的「听歌统计」（旧格式）
 * - [BACKUP]：`lx_backup.lxmc`（type `allData_v3`）—— LX-Music **自带**的「歌单备份」（旧格式）
 *
 * 参考旧项目：`src/core/player/stats.ts`（exportStatsData / importStatsData）
 * 与 `src/screens/Home/Views/Setting/settings/Backup/actions.ts`（exportAllList / importBackupData）。
 */
enum class ImportKind(val label: String) {
    /** 本应用的全量备份（`lxpro_all`）：统计、歌单、标签、白噪音…全在一个文件里 */
    UNIFIED("全量备份"),
    STATS("听歌统计"),
    BACKUP("歌单备份"),
}

/** 导入结果（用于回执文案） */
data class ImportResult(
    val kind: ImportKind,
    /** 统计事件条数 */
    val events: Int = 0,
    /** 播放历史条数 */
    val historyRows: Int = 0,
    val likedSongs: Int = 0,
    val playlists: Int = 0,
    val playlistSongs: Int = 0,
    /** 用户标签条数（全量备份） */
    val tags: Int = 0,
    /** 白噪音会话段数（全量备份） */
    val noiseSessions: Int = 0,
    /** 歌曲名 / 作者名修正条数（全量备份） */
    val overrides: Int = 0,
    /** 常听歌手图标条数（全量备份） */
    val artistIcons: Int = 0,
    val failed: Boolean = false,
    val error: String? = null,
) {
    val isEmptyButOk: Boolean
        get() = !failed && events == 0 && historyRows == 0 && playlistSongs == 0 &&
            tags == 0 && noiseSessions == 0 && overrides == 0 && artistIcons == 0
}

/**
 * LX（前作）备份导入：**统计与歌单两条独立入口，互不清空、互不影响**。
 *
 * 两类文件都是 **gzip 压缩的 JSON**（`.lxmc`）：
 * - `lx_stats_data`：`{daily, song, events}` → 只写统计账本 `stats_events`
 * - `allData_v3`：`{lists, playHistory, …}` → 只写歌单与播放历史 `play_history`
 *
 * 口径对齐（两种入口共用）：
 * - `playTime`（真实播放秒数）→ `playedDurationMs`
 * - `interval`（"03:47"）→ `totalDurationMs` 与 `Song.interval`
 * - `isEffective` 优先取备份里的值；备份没有该字段时用本产品的门槛重算
 * - 歌曲 id：优先 `meta.songId`（音源原生 id），否则从 `"<source>_<id>"` 里剥掉前缀
 */
@Singleton
class LxBackupImporter @Inject constructor(
    private val history: PlayHistoryRepository,
    private val ledger: StatsLedgerRepository,
    private val playlists: PlaylistRepository,
    private val tagDao: SongTagDao,
    private val noiseDao: NoiseEventDao,
    private val overrideDao: SongOverrideDao,
    private val artistIconDao: ArtistIconOverrideDao,
    private val settings: SettingsRepository,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 统一入口（2026-10-27）：「导入备份」卡片只留一个，按 `type` 自动分流——
     * 本应用全量备份走 [importUnifiedRoot]，旧版 `lx_stats_data` / `allData_v3` 仍照旧可用。
     */
    suspend fun importAny(input: InputStream, onProgress: (String) -> Unit = {}): ImportResult {
        val root = readRootAny(input)
            ?: return ImportResult(
                ImportKind.UNIFIED,
                failed = true,
                error = "读不出内容：不是有效的 .lxmc 备份（gzip JSON）",
            )
        return when (root.str("type")) {
            LX_ALL_TYPE -> importUnifiedRoot(root, onProgress)
            STATS_TYPE -> importStatsRoot(root, onProgress)
            ALL_DATA_TYPE -> importBackupRoot(root, onProgress)
            else -> ImportResult(
                ImportKind.UNIFIED,
                failed = true,
                error = "这个文件是「${guessLabel(root.str("type"))}」，不是可导入的备份",
            )
        }
    }

    /**
     * 「导入听歌统计」：只认 `lx_stats_data`，**覆盖统计账本**，不碰歌单与播放历史。
     */
    suspend fun importStats(input: InputStream, onProgress: (String) -> Unit = {}): ImportResult {
        val root = readRoot(input, ImportKind.STATS) ?: return rootFailure(ImportKind.STATS)
        return importStatsRoot(root, onProgress)
    }

    private suspend fun importStatsRoot(root: JsonObject, onProgress: (String) -> Unit): ImportResult {
        val type = root.str("type")
        if (type != STATS_TYPE) {
            return wrongFile(ImportKind.STATS, type, "这个文件是「${guessLabel(type)}」")
        }

        val events = root.arr("events").orEmpty()
        onProgress("解析听歌统计（${events.size} 条事件）…")
        val rows = events.mapNotNull { statsRow(it as? JsonObject ?: return@mapNotNull null) }

        onProgress("写入统计账本…")
        ledger.replaceAll(rows)
        return ImportResult(ImportKind.STATS, events = rows.size)
    }

    /**
     * 「导入歌单备份」：只认 `allData_v3`，**覆盖歌单与播放历史**，不碰统计账本。
     */
    suspend fun importBackup(input: InputStream, onProgress: (String) -> Unit = {}): ImportResult {
        val root = readRoot(input, ImportKind.BACKUP) ?: return rootFailure(ImportKind.BACKUP)
        return importBackupRoot(root, onProgress)
    }

    private suspend fun importBackupRoot(root: JsonObject, onProgress: (String) -> Unit): ImportResult {
        val type = root.str("type")
        if (type != ALL_DATA_TYPE) {
            return wrongFile(ImportKind.BACKUP, type, "这个文件是「${guessLabel(type)}」")
        }
        val data = root.obj("data")
            ?: return ImportResult(ImportKind.BACKUP, failed = true, error = "备份缺少 data 字段")

        val historyRows = data.arr("playHistory").orEmpty()
            .mapNotNull { historyRow(it as? JsonObject ?: return@mapNotNull null) }
        onProgress("写入播放历史（${historyRows.size} 条）…")
        history.replaceAll(historyRows)

        val lists = data.obj("lists")
        var liked = 0
        var playlistCount = 0
        var playlistSongs = 0

        if (lists != null) {
            playlists.ensureSystemPlaylists()
            val existing = playlists.existingNames().toMutableSet()

            val loveSongs = lists.arr("loveList").orEmpty().toSongs()
            if (loveSongs.isNotEmpty()) {
                onProgress("导入收藏（${loveSongs.size} 首）…")
                liked = playlists.addSongs(LIKED_PLAYLIST_ID, loveSongs)
            }

            // 用户歌单：名字已存在则跳过（重复导入不叠加）
            lists.arr("userList").orEmpty().forEach { element ->
                val entry = element as? JsonObject ?: return@forEach
                val name = entry.str("name") ?: return@forEach
                if (name in existing) return@forEach
                val songs = entry.arr("list").orEmpty().toSongs()
                if (songs.isEmpty()) return@forEach
                onProgress("导入歌单「$name」（${songs.size} 首）…")
                val id = playlists.createPlaylist(name)
                playlistSongs += playlists.addSongs(id, songs)
                playlistCount++
                existing += name
            }

            // 默认列表：前作的主播放列表，导入为一个普通歌单
            val defaultSongs = lists.arr("defaultList").orEmpty().toSongs()
            if (defaultSongs.isNotEmpty() && DEFAULT_LIST_NAME !in existing) {
                onProgress("导入默认列表（${defaultSongs.size} 首）…")
                val id = playlists.createPlaylist(DEFAULT_LIST_NAME)
                playlistSongs += playlists.addSongs(id, defaultSongs)
                playlistCount++
            }
        }

        return ImportResult(
            kind = ImportKind.BACKUP,
            historyRows = historyRows.size,
            likedSongs = liked,
            playlists = playlistCount,
            playlistSongs = playlistSongs,
        )
    }

    /**
     * 本应用全量备份（`lxpro_all`）。
     *
     * 覆盖 vs 合并（按数据性质分开，宁可重复也不误删）：
     * - 账本类（统计 / 播放历史 / 白噪音）→ **覆盖**
     * - 歌单 → 按名字合并（同名的跳过，不重复建）
     * - 用户内容（标签 / 修正 / 歌手图标）→ **合并**（按各自唯一键去重）
     * - 搜索历史与启用的音源 → 覆盖
     */
    private suspend fun importUnifiedRoot(
        root: JsonObject,
        onProgress: (String) -> Unit,
    ): ImportResult {
        val data = root.obj("data")
            ?: return ImportResult(ImportKind.UNIFIED, failed = true, error = "备份缺少 data 字段")

        // ── 听歌统计（软件自贴标签由它实时聚合）──
        var events = 0
        data.arr("statsEvents")?.let { arr ->
            onProgress("写入听歌统计（${arr.size} 条事件）…")
            val rows = arr.mapNotNull { ownStatsRow(it as? JsonObject ?: return@mapNotNull null) }
            ledger.replaceAll(rows)
            events = rows.size
        }

        // ── 播放历史 ──
        var historyRows = 0
        data.arr("playHistory")?.let { arr ->
            onProgress("写入播放历史（${arr.size} 条）…")
            val rows = arr.mapNotNull { ownHistoryRow(it as? JsonObject ?: return@mapNotNull null) }
            history.replaceAll(rows)
            historyRows = rows.size
        }

        // ── 白噪音账本 ──
        var noiseSessions = 0
        data.arr("noiseEvents")?.let { arr ->
            onProgress("写入白噪音账本（${arr.size} 段）…")
            val rows = arr.mapNotNull { noiseRow(it as? JsonObject ?: return@mapNotNull null) }
            noiseDao.clear()
            if (rows.isNotEmpty()) noiseDao.insertAll(rows)
            noiseSessions = rows.size
        }

        // ── 歌单（收藏 + 我的歌单），按名字合并 ──
        var liked = 0
        var playlistCount = 0
        var playlistSongs = 0
        data.arr("playlists")?.let { arr ->
            playlists.ensureSystemPlaylists()
            val existing = playlists.existingNames().toMutableSet()
            val createdIds = mutableListOf<String>()
            arr.forEach { element ->
                val entry = element as? JsonObject ?: return@forEach
                val name = entry.str("name") ?: return@forEach
                val songs = entry.arr("songs").orEmpty().toOwnSongs()
                val isLiked = entry.bool("isSystem") == true || name == LIKED_NAME
                if (isLiked) {
                    if (songs.isNotEmpty()) {
                        onProgress("导入收藏（${songs.size} 首）…")
                        liked = playlists.addSongs(LIKED_PLAYLIST_ID, songs)
                    }
                    return@forEach
                }
                if (name in existing) return@forEach
                if (songs.isEmpty()) return@forEach
                onProgress("导入歌单「$name」（${songs.size} 首）…")
                val id = playlists.createPlaylist(name)
                playlistSongs += playlists.addSongs(id, songs)
                playlistCount++
                createdIds += id
                // 自定义封面：歌曲封面优先，其次本地图（换设备后本地图可能失效，如实保留 uri）
                val coverSongId = entry.str("coverSongId")
                val coverSource = entry.str("coverSource")
                if (coverSongId != null && coverSource != null) {
                    playlists.setCoverFromSong(id, coverSongId, coverSource)
                } else {
                    entry.str("coverImageUri")?.let { playlists.setCoverFromImage(id, it) }
                }
            }
            if (createdIds.size > 1) playlists.reorderPlaylists(createdIds)
        }

        // ── 用户标签（合并）──
        var tags = 0
        data.arr("songTags")?.let { arr ->
            val rows = arr.mapNotNull { tagRow(it as? JsonObject ?: return@mapNotNull null) }
            if (rows.isNotEmpty()) {
                onProgress("合并歌曲标签（${rows.size} 条）…")
                tagDao.insertAll(rows)
            }
            tags = rows.size
        }

        // ── 歌曲名 / 作者名修正（合并）──
        var overrides = 0
        data.arr("songOverrides")?.let { arr ->
            val rows = arr.mapNotNull { overrideRow(it as? JsonObject ?: return@mapNotNull null) }
            if (rows.isNotEmpty()) {
                onProgress("合并本地修正（${rows.size} 条）…")
                overrideDao.insertAll(rows)
            }
            overrides = rows.size
        }

        // ── 常听歌手图标（合并）──
        var artistIcons = 0
        data.arr("artistIconOverrides")?.let { arr ->
            val rows = arr.mapNotNull { iconRow(it as? JsonObject ?: return@mapNotNull null) }
            if (rows.isNotEmpty()) {
                onProgress("合并歌手图标（${rows.size} 条）…")
                artistIconDao.upsertAll(rows)
            }
            artistIcons = rows.size
        }

        // ── 搜索历史 / 常搜 / 启用的音源 ──
        data.obj("prefs")?.let { prefs ->
            val historyArr = prefs.arr("searchHistory")
            if (historyArr != null) {
                val historyList = historyArr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                val counts = prefs.obj("searchCounts")?.mapNotNull { (key, value) ->
                    val count = (value as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                    key to count
                }?.toMap().orEmpty()
                settings.restoreSearchState(historyList, counts)
            }
            prefs.arr("enabledSourceIds")?.let { arr ->
                settings.setEnabledSourceIds(
                    arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet(),
                )
            }
            // 全部设置偏好（原始键 + 类型 + 值）：覆盖同名键，不动备份里没有的键
            prefs.arr("settings")?.let { arr ->
                val entries = arr.mapNotNull { element ->
                    val entry = element as? JsonObject ?: return@mapNotNull null
                    val key = entry.str("key") ?: return@mapNotNull null
                    val type = entry.str("type") ?: return@mapNotNull null
                    val value = (entry["value"] as? JsonPrimitive)?.contentOrNull
                        ?: return@mapNotNull null
                    PrefSnapshot(key, type, value)
                }
                if (entries.isNotEmpty()) {
                    onProgress("恢复设置偏好（${entries.size} 项）…")
                    settings.restorePreferences(entries)
                }
            }
        }

        return ImportResult(
            kind = ImportKind.UNIFIED,
            events = events,
            historyRows = historyRows,
            likedSongs = liked,
            playlists = playlistCount,
            playlistSongs = playlistSongs,
            tags = tags,
            noiseSessions = noiseSessions,
            overrides = overrides,
            artistIcons = artistIcons,
        )
    }

    /**
     * 「扫描 Download 导入」用的类型识别：只读 JSON 的 `type` 字段，不写任何数据。
     * 返回 null = 不是能识别的备份（页面标「未知格式」，不可勾选）。
     */
    fun sniffKind(input: InputStream): ImportKind? =
        when ((runCatching { json.parseToJsonElement(readMaybeGzip(input)) }.getOrNull() as? JsonObject).str("type")) {
            LX_ALL_TYPE -> ImportKind.UNIFIED
            STATS_TYPE -> ImportKind.STATS
            ALL_DATA_TYPE -> ImportKind.BACKUP
            else -> null
        }

    // ── 读文件 ──

    private fun readRoot(input: InputStream, kind: ImportKind): JsonObject? =
        runCatching { json.parseToJsonElement(readMaybeGzip(input)) as? JsonObject }.getOrNull()

    private fun readRootAny(input: InputStream): JsonObject? =
        runCatching { json.parseToJsonElement(readMaybeGzip(input)) as? JsonObject }.getOrNull()

    private fun rootFailure(kind: ImportKind) =
        ImportResult(kind, failed = true, error = "读不出内容：不是有效的 .lxmc 备份（gzip JSON）")

    private fun wrongFile(kind: ImportKind, actual: String?, hint: String) = ImportResult(
        kind,
        failed = true,
        error = "$hint，不能从「导入${kind.label}」进来（识别到 type=${actual ?: "无"}）",
    )

    private fun guessLabel(type: String?): String = when (type) {
        LX_ALL_TYPE -> "全量备份（本应用）"
        STATS_TYPE -> "听歌统计（LX-Pro 新增）"
        ALL_DATA_TYPE -> "歌单备份（LX-Music 自带）"
        else -> "无法识别的文件"
    }

    private fun readMaybeGzip(input: InputStream): String {
        val buffered = input.buffered()
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        val stream = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(buffered) else buffered
        return stream.readBytes().decodeToString()
    }

    // ── 解析：事件 → 两类历史行 ──

    /** 统计账本行（`lx_stats_data.events[]`） */
    private fun statsRow(event: JsonObject): StatsEventEntity? {
        val parsed = parseEvent(event) ?: return null
        return StatsEventEntity(
            songId = parsed.song.id,
            source = parsed.song.source.value,
            name = parsed.song.name,
            singer = parsed.song.singer,
            albumName = parsed.song.albumName,
            coverUrl = parsed.song.picUrl,
            songJson = Json.encodeToString(Song.serializer(), parsed.song),
            playedAt = parsed.playedAt,
            playedDurationMs = parsed.playedMs,
            totalDurationMs = parsed.totalMs,
            isEffective = parsed.effective,
        )
    }

    /** 播放历史行（`allData_v3.data.playHistory[]`） */
    private fun historyRow(event: JsonObject): PlayHistoryEntity? {
        val parsed = parseEvent(event) ?: return null
        return PlayHistoryEntity(
            songId = parsed.song.id,
            source = parsed.song.source.value,
            name = parsed.song.name,
            singer = parsed.song.singer,
            albumName = parsed.song.albumName,
            coverUrl = parsed.song.picUrl,
            songJson = Json.encodeToString(Song.serializer(), parsed.song),
            playedAt = parsed.playedAt,
            playedDurationMs = parsed.playedMs,
            totalDurationMs = parsed.totalMs,
            isEffective = parsed.effective,
        )
    }

    private data class ParsedEvent(
        val song: Song,
        val playedAt: Long,
        val playedMs: Long,
        val totalMs: Long,
        val effective: Boolean,
    )

    private fun parseEvent(event: JsonObject): ParsedEvent? {
        val info = event.obj("musicInfo") ?: return null
        val song = songOf(info) ?: return null
        val playedAt = event.long("playedAt") ?: return null
        // ⚠️ playTime 是**浮点秒**（如 142.808）。早期用 longOrNull 解析小数会返回 null，
        //    结果 2429 条里 2426 条被当成 0 —— 统计时长只剩「9 分钟」（2026-10-03 真机暴露）。
        val playedMs = ((event.dbl("playTime") ?: 0.0) * 1000.0).toLong()
        val totalMs = (song.interval ?: 0L) * 1000L
        val effective = event.bool("isEffective") ?: isEffectivePlay(playedMs, totalMs)
        return ParsedEvent(song, playedAt, playedMs, totalMs, effective)
    }

    private fun List<JsonElement>.toSongs(): List<Song> =
        mapNotNull { songOf(it as? JsonObject ?: return@mapNotNull null) }

    private fun songOf(info: JsonObject): Song? {
        val source = info.str("source") ?: return null
        val meta = info.obj("meta")
        val nativeId = meta.str("songId")
        val id = nativeId ?: info.str("id")?.removePrefix("${source}_") ?: return null
        if (id.isBlank()) return null

        return Song(
            id = id,
            source = SourceId(source),
            name = info.str("name").orEmpty(),
            singer = info.str("singer").orEmpty(),
            albumName = meta.str("albumName"),
            albumId = meta.str("albumId"),
            picUrl = meta.str("picUrl"),
            interval = parseInterval(info.str("interval")),
            // 只保留取流必需的最小 raw：内置 B 站音源用 id 即可，其余源未装脚本也播不了
            raw = if (nativeId != null) mapOf("songId" to nativeId) else emptyMap(),
        )
    }

    /** "03:47" / "3:55" → 秒；非法返回 null */
    private fun parseInterval(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(':')
        if (parts.size != 2) return null
        val minutes = parts[0].toDoubleOrNull() ?: return null
        val seconds = parts[1].toDoubleOrNull() ?: return null
        return (minutes * 60 + seconds).toLong()
    }

    // ── 解析：本应用全量备份的行 ──

    private fun ownStatsRow(event: JsonObject): StatsEventEntity? {
        val parsed = parseOwnPlayedRow(event) ?: return null
        return StatsEventEntity(
            songId = parsed.song.id,
            source = parsed.song.source.value,
            name = parsed.song.name,
            singer = parsed.song.singer,
            albumName = parsed.song.albumName,
            coverUrl = parsed.song.picUrl,
            songJson = Json.encodeToString(Song.serializer(), parsed.song),
            playedAt = parsed.playedAt,
            playedDurationMs = parsed.playedMs,
            totalDurationMs = parsed.totalMs,
            isEffective = parsed.effective,
        )
    }

    private fun ownHistoryRow(event: JsonObject): PlayHistoryEntity? {
        val parsed = parseOwnPlayedRow(event) ?: return null
        return PlayHistoryEntity(
            songId = parsed.song.id,
            source = parsed.song.source.value,
            name = parsed.song.name,
            singer = parsed.song.singer,
            albumName = parsed.song.albumName,
            coverUrl = parsed.song.picUrl,
            songJson = Json.encodeToString(Song.serializer(), parsed.song),
            playedAt = parsed.playedAt,
            playedDurationMs = parsed.playedMs,
            totalDurationMs = parsed.totalMs,
            isEffective = parsed.effective,
        )
    }

    private fun noiseRow(row: JsonObject): NoiseEventEntity? {
        val startedAt = row.long("startedAt") ?: return null
        return NoiseEventEntity(
            startedAt = startedAt,
            endedAt = row.long("endedAt") ?: startedAt,
            durationMs = row.long("durationMs") ?: 0L,
            musicMs = row.long("musicMs") ?: 0L,
            soundIds = row.str("soundIds") ?: "[]",
            soundNames = row.str("soundNames") ?: "[]",
            sceneName = row.str("sceneName"),
        )
    }

    private fun tagRow(row: JsonObject): SongTagEntity? {
        val songId = row.str("songId") ?: return null
        val source = row.str("source") ?: return null
        val tag = row.str("tag") ?: return null
        return SongTagEntity(
            songId = songId,
            source = source,
            tag = tag,
            createdAt = row.long("createdAt") ?: System.currentTimeMillis(),
        )
    }

    private fun overrideRow(row: JsonObject): SongOverrideEntity? {
        val songId = row.str("songId") ?: return null
        val source = row.str("source") ?: return null
        val name = row.str("name")
        val singer = row.str("singer")
        val toggleSongJson = row.str("toggleSongJson")
        if (name == null && singer == null && toggleSongJson == null) return null
        return SongOverrideEntity(
            songId = songId,
            source = source,
            name = name,
            singer = singer,
            toggleSongJson = toggleSongJson,
            updatedAt = row.long("updatedAt") ?: System.currentTimeMillis(),
        )
    }

    private fun iconRow(row: JsonObject): ArtistIconOverrideEntity? {
        val artistKey = row.str("artistKey") ?: return null
        val kind = row.str("kind") ?: return null
        val value = row.str("value") ?: return null
        return ArtistIconOverrideEntity(
            artistKey = artistKey,
            kind = kind,
            value = value,
            updatedAt = row.long("updatedAt") ?: System.currentTimeMillis(),
        )
    }

    private companion object {
        const val STATS_TYPE = "lx_stats_data"
        const val ALL_DATA_TYPE = "allData_v3"
        const val DEFAULT_LIST_NAME = "默认列表"

        /** 收藏歌单的展示名（系统歌单 id 恒为 `liked`） */
        const val LIKED_NAME = "收藏"
    }
}

// ── 解析：本应用全量备份（`lxpro_all`）的字段，与 [LxBackupExporter] 对称 ──

private data class OwnPlayedRow(
    val song: Song,
    val playedAt: Long,
    val playedMs: Long,
    val totalMs: Long,
    val effective: Boolean,
)

/** 统计 / 播放历史行：嵌入的是完整 [Song] 对象，解析失败不抛 */
private fun parseOwnPlayedRow(event: JsonObject): OwnPlayedRow? {
    val song = ownSongOf(event.obj("song")) ?: return null
    val playedAt = event.long("playedAt") ?: return null
    return OwnPlayedRow(
        song = song,
        playedAt = playedAt,
        playedMs = event.long("playedDurationMs") ?: 0L,
        totalMs = event.long("totalDurationMs") ?: (song.interval ?: 0L) * 1000L,
        effective = event.bool("isEffective") ?: false,
    )
}

/** 歌单里的歌曲：导出时写的是完整 Song JSON，直接反序列化 */
private fun ownSongOf(obj: JsonObject?): Song? {
    if (obj == null) return null
    return runCatching { Json.decodeFromString(Song.serializer(), obj.toString()) }
        .getOrNull()
        ?.takeIf { it.id.isNotBlank() && it.source.value.isNotBlank() }
}

private fun List<JsonElement>.toOwnSongs(): List<Song> =
    mapNotNull { ownSongOf(it as? JsonObject ?: return@mapNotNull null) }

// ── kotlinx.serialization 的 JsonObject 取值糖（缺失/类型不符一律给 null，不抛） ──
//    接收者写成可空，这样 `meta.str("picUrl")` 这类链式取值不必到处 ?.

private fun JsonObject?.str(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

private fun JsonObject?.arr(key: String): JsonArray? = this?.get(key) as? JsonArray

private fun JsonObject?.long(key: String): Long? = (this?.get(key) as? JsonPrimitive)?.longOrNull

/** 备份里 playTime / maxTime 都是**浮点秒**，必须按 Double 读（见 parseEvent 的注释） */
private fun JsonObject?.dbl(key: String): Double? = (this?.get(key) as? JsonPrimitive)?.doubleOrNull

private fun JsonObject?.bool(key: String): Boolean? = (this?.get(key) as? JsonPrimitive)?.booleanOrNull