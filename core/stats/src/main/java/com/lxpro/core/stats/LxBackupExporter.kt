package com.lxpro.core.stats

import com.lxpro.core.database.dao.ArtistIconOverrideDao
import com.lxpro.core.database.dao.NoiseEventDao
import com.lxpro.core.database.dao.PlayHistoryDao
import com.lxpro.core.database.dao.PlaylistDao
import com.lxpro.core.database.dao.SongOverrideDao
import com.lxpro.core.database.dao.SongTagDao
import com.lxpro.core.database.dao.StatsEventDao
import com.lxpro.core.datastore.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.OutputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全量备份导出：**所有用户数据装进同一个 `.lxmc` 文件**（2026-10-27 用户要求）。
 *
 * 文件 = gzip 压缩的 JSON，`type = lxpro_all`，`version = 1`。包含：
 * - `statsEvents`：听歌统计账本（「软件自贴标签」由它实时聚合，备份它即备份标签）
 * - `playHistory`：播放历史
 * - `noiseEvents`：白噪音收听账本
 * - `playlists`：收藏 + 我的歌单（含自定义封面与排序位）
 * - `songTags`：用户给歌曲贴的文字标签
 * - `songOverrides`：歌曲名 / 作者名的本地修正
 * - `artistIconOverrides`：常听歌手图标
 * - `prefs`：搜索历史、常搜频次、启用的音源
 *
 * ⚠️ 不含本地文件路径类数据（本地曲库索引、音源脚本、白噪音本地音源、EQ 曲线文件）：
 * 它们引用本机文件，换设备后不可用。
 */
@Singleton
class LxBackupExporter @Inject constructor(
    private val statsDao: StatsEventDao,
    private val historyDao: PlayHistoryDao,
    private val noiseDao: NoiseEventDao,
    private val tagDao: SongTagDao,
    private val overrideDao: SongOverrideDao,
    private val artistIconDao: ArtistIconOverrideDao,
    private val playlistDao: PlaylistDao,
    private val settings: SettingsRepository,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 汇总全量数据并以 gzip JSON 写入 [output]（调用方负责打开 SAF 输出流） */
    suspend fun exportTo(
        output: OutputStream,
        appVersion: String,
        onProgress: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        onProgress("汇总听歌统计…")
        val stats = statsDao.observeAll().first()

        onProgress("汇总播放历史…")
        val history = historyDao.getAll()

        onProgress("汇总白噪音账本…")
        val noise = noiseDao.getAll()

        onProgress("汇总歌曲标签…")
        val tags = tagDao.getAll()

        onProgress("汇总本地修正…")
        val overrides = overrideDao.getAll()
        val artistIcons = artistIconDao.getAll()

        onProgress("汇总歌单…")
        val playlists = buildJsonArray {
            playlistDao.getPlaylists().forEach { playlist ->
                val songs = buildJsonArray {
                    playlistDao.getItems(playlist.id).forEach { item ->
                        add(songObject(item.songJson))
                    }
                }
                add(
                    buildJsonObject {
                        put("id", playlist.id)
                        put("name", playlist.name)
                        put("isSystem", playlist.isSystem)
                        put("position", playlist.position)
                        put("coverSongId", playlist.coverSongId)
                        put("coverSource", playlist.coverSource)
                        put("coverImageUri", playlist.coverImageUri)
                        put("songs", songs)
                    },
                )
            }
        }

        onProgress("汇总搜索记录…")
        val searchHistory = settings.searchHistory.first()
        val searchCounts = settings.searchCountsMap.first()
        val enabledSources = settings.enabledSourceIds.first()

        onProgress("汇总设置偏好…")
        val prefSnapshot = settings.snapshotPreferences()

        onProgress("写入备份文件…")
        val root = buildJsonObject {
            put("type", LX_ALL_TYPE)
            put("version", LX_ALL_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("appVersion", appVersion)
            put(
                "data",
                buildJsonObject {
                    put("statsEvents", buildJsonArray {
                        stats.forEach { row ->
                            add(
                                buildJsonObject {
                                    put("songId", row.songId)
                                    put("source", row.source)
                                    put("name", row.name)
                                    put("singer", row.singer)
                                    put("albumName", row.albumName)
                                    put("coverUrl", row.coverUrl)
                                    put("song", songObject(row.songJson))
                                    put("playedAt", row.playedAt)
                                    put("playedDurationMs", row.playedDurationMs)
                                    put("totalDurationMs", row.totalDurationMs)
                                    put("isEffective", row.isEffective)
                                },
                            )
                        }
                    })
                    put("playHistory", buildJsonArray {
                        history.forEach { row ->
                            add(
                                buildJsonObject {
                                    put("songId", row.songId)
                                    put("source", row.source)
                                    put("name", row.name)
                                    put("singer", row.singer)
                                    put("albumName", row.albumName)
                                    put("coverUrl", row.coverUrl)
                                    put("song", songObject(row.songJson))
                                    put("playedAt", row.playedAt)
                                    put("playedDurationMs", row.playedDurationMs)
                                    put("totalDurationMs", row.totalDurationMs)
                                    put("isEffective", row.isEffective)
                                },
                            )
                        }
                    })
                    put("noiseEvents", buildJsonArray {
                        noise.forEach { row ->
                            add(
                                buildJsonObject {
                                    put("startedAt", row.startedAt)
                                    put("endedAt", row.endedAt)
                                    put("durationMs", row.durationMs)
                                    put("musicMs", row.musicMs)
                                    put("soundIds", row.soundIds)
                                    put("soundNames", row.soundNames)
                                    put("sceneName", row.sceneName)
                                },
                            )
                        }
                    })
                    put("playlists", playlists)
                    put("songTags", buildJsonArray {
                        tags.forEach { row ->
                            add(
                                buildJsonObject {
                                    put("songId", row.songId)
                                    put("source", row.source)
                                    put("tag", row.tag)
                                    put("createdAt", row.createdAt)
                                },
                            )
                        }
                    })
                    put("songOverrides", buildJsonArray {
                        overrides.forEach { row ->
                            add(
                                buildJsonObject {
                                    put("songId", row.songId)
                                    put("source", row.source)
                                    put("name", row.name)
                                    put("singer", row.singer)
                                    put("toggleSongJson", row.toggleSongJson)
                                    put("updatedAt", row.updatedAt)
                                },
                            )
                        }
                    })
                    put("artistIconOverrides", buildJsonArray {
                        artistIcons.forEach { row ->
                            add(
                                buildJsonObject {
                                    put("artistKey", row.artistKey)
                                    put("kind", row.kind)
                                    put("value", row.value)
                                    put("updatedAt", row.updatedAt)
                                },
                            )
                        }
                    })
                    put(
                        "prefs",
                        buildJsonObject {
                            put("searchHistory", buildJsonArray {
                                searchHistory.forEach { add(JsonPrimitive(it)) }
                            })
                            put("searchCounts", buildJsonObject {
                                searchCounts.forEach { (query, count) ->
                                    put(query, count)
                                }
                            })
                            put("enabledSourceIds", buildJsonArray {
                                enabledSources.forEach { add(JsonPrimitive(it)) }
                            })
                            // 全部设置偏好（原始键 + 类型 + 值）：主题、播放行为、下载、
                            // 统计口径、白噪音、EQ、歌词、桌面歌词、分享模板…整表快照
                            put("settings", buildJsonArray {
                                prefSnapshot.forEach { entry ->
                                    add(
                                        buildJsonObject {
                                            put("key", entry.key)
                                            put("type", entry.type)
                                            put("value", entry.value)
                                        },
                                    )
                                }
                            })
                        },
                    )
                },
            )
        }

        GZIPOutputStream(output).use { gz ->
            gz.write(root.toString().encodeToByteArray())
            gz.finish()
        }
    }

    /** 表里存的是 `Song` 的 JSON 字符串；解不出就写空对象，不让整份备份导出失败 */
    private fun songObject(raw: String): JsonObject =
        runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: buildJsonObject { }
}
