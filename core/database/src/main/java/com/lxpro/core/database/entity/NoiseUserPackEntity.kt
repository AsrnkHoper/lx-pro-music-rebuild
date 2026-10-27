package com.lxpro.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 白噪音「远端音源包」（17 §3 第三种获取方式）。
 *
 * Noice 式声音包 = 一个 zip：`manifest.json` + 若干音频文件（可选桥接段）。
 * 下载/导入后解压到应用私有目录（[dirPath]），**离线可用**。
 *
 * 与 [NoiseUserSourceEntity]（单文件本地导入）并列：两者都与内置 CDN 音源同权。
 *
 * @param segmentsJson 段清单 JSON：[{name,file,bridge,from,to}]
 * @param minSilenceSeconds 事件型随机静音下限
 * @param maxSilenceSeconds >0 = 事件型；0 = 连续型
 */
@Entity(tableName = "noise_user_packs")
data class NoiseUserPackEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    /** 六类之一（与 NoiseCategory label 对应） */
    val category: String,
    /** 解压根目录（绝对路径） */
    val dirPath: String,
    val segmentsJson: String,
    val minSilenceSeconds: Int = 0,
    val maxSilenceSeconds: Int = 0,
    val createdAt: Long,
)
