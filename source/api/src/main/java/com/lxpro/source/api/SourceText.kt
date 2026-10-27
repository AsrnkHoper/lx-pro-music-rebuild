package com.lxpro.source.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 内置音源（kw/tx/wy）共用的 JSON 取值小工具。
 * 各家接口字段类型不干净（数字/字符串混用），这里统一容忍解析。
 */
fun JsonElement?.jObj(): JsonObject? = this as? JsonObject

fun JsonElement?.jArray(): List<JsonElement>? = (this as? JsonArray)?.toList()

fun JsonElement?.jStr(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

fun JsonElement?.jLong(): Long? =
    (this as? JsonPrimitive)?.longOrNull ?: jStr()?.toLongOrNull()

fun JsonElement?.jInt(): Int? = jLong()?.toInt()

operator fun JsonElement?.get(key: String): JsonElement? = jObj()?.get(key)

operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

private val namedEntities = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to " ", "copy" to "©", "reg" to "®", "trade" to "™",
    "hellip" to "…", "mdash" to "—", "ndash" to "–",
    "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
    "middot" to "·", "times" to "×", "divide" to "÷", "deg" to "°",
)

private val entityExp = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")

/**
 * HTML 实体解码（对应 walnut `decodeName`）：搜索结果的歌曲名/歌手名常带
 * `&amp;` `&#39;` 这类实体，展示前必须还原。
 */
fun decodeEntities(text: String): String {
    if ('&' !in text) return text
    return entityExp.replace(text) { m ->
        val body = m.groupValues[1]
        when {
            body.startsWith("#x", ignoreCase = true) ->
                body.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            body.startsWith("#") ->
                body.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> namedEntities[body]
        } ?: m.value
    }
}
