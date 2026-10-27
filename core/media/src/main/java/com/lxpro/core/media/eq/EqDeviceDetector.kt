package com.lxpro.core.media.eq

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 当前输出设备（17 §5.3「自动匹配」：检测到当前输出设备 → 一键套用对应曲线）。
 *
 * @param name 设备名（蓝牙/有线耳机通常带型号，如「WH-1000XM4」「1MORE Triple Driver」）
 * @param kind 人类可读的设备类别（蓝牙耳机 / 有线耳机 / USB 音频…）
 */
data class EqOutputDevice(
    val id: Int,
    val name: String,
    val kind: String,
)

/**
 * 输出设备探测（自动匹配用）。
 *
 * 只关心"外接耳机类"设备：蓝牙 / 有线 / USB / 助听器。设备自带扬声器与听筒不参与，
 * 因为耳机校正曲线套到外放上没有意义（17 §5.2：只作用于歌曲，且面向耳机校正）。
 */
@Singleton
class EqDeviceDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private fun audioManager(): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 一次性探测（进页面用） */
    fun currentOutput(): EqOutputDevice? = pick(outputs())

    /** 持续探测（插拔耳机、蓝牙连断时自动刷新卡片） */
    fun observeOutput(): Flow<EqOutputDevice?> = callbackFlow {
        val am = audioManager()
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                trySend(pick(outputs()))
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                trySend(pick(outputs()))
            }
        }
        trySend(pick(outputs()))
        am.registerAudioDeviceCallback(callback, null)
        awaitClose { am.unregisterAudioDeviceCallback(callback) }
    }.distinctUntilChanged()

    private fun outputs(): List<AudioDeviceInfo> =
        audioManager().getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()

    private fun pick(devices: List<AudioDeviceInfo>): EqOutputDevice? {
        val candidate = devices
            .filter { it.isSink && isHeadphoneish(it.type) }
            .minByOrNull { rank(it.type) }
            ?: return null
        return EqOutputDevice(
            id = candidate.id,
            name = candidate.productName?.toString().orEmpty(),
            kind = kindLabel(candidate.type),
        )
    }

    private fun isHeadphoneish(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_HEARING_AID,
        -> true

        else -> false
    }

    /** 排序偏好：蓝牙（在用概率最高）→ 有线 → USB */
    private fun rank(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        -> 0

        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        -> 1

        else -> 2
    }

    private fun kindLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        -> "蓝牙耳机"

        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        -> "有线耳机"

        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        -> "USB 音频"

        AudioDeviceInfo.TYPE_HEARING_AID -> "助听器"
        else -> "音频设备"
    }
}

/**
 * 设备名 → 校正曲线匹配（17 §5.3 自动匹配）。
 *
 * 启发式（够用就好，避免引入 ML 级别的复杂度）：
 * 1. 品牌命中设备名（品牌 ≥3 字符，如 "1more"/"sony"）→ +3；
 * 2. 型号词命中设备名（如 "1000xm4"/"tripledriver"）→ 每个词 +2；
 * 3. 取分最高者；同分取名称更短的那条（更"泛化"的曲线，如「1MORE」优先于「1MORE Triple Driver」）。
 *
 * 无任何命中（设备名是「Bluetooth headphones」这类泛称）返回 null，由界面提示手动搜索。
 */
fun matchEqPreset(device: EqOutputDevice, presets: List<EqPreset>): EqPreset? {
    val deviceName = normalizeName(device.name)
    if (deviceName.isBlank()) return null
    var best: EqPreset? = null
    var bestScore = 0
    presets.forEach { preset ->
        val brand = normalizeName(preset.brand)
        val full = normalizeName(preset.name)
        if (brand.isBlank()) return@forEach
        var score = 0
        if (brand.length >= 3 && deviceName.contains(brand)) score += 3
        val modelTokens = full.split(' ')
            .filter { it.isNotEmpty() && it != brand && it.length >= 2 }
        score += modelTokens.count { deviceName.contains(it) } * 2
        if (score > bestScore) {
            bestScore = score
            best = preset
        } else if (score == bestScore && score > 0) {
            val current = best
            if (current == null || preset.name.length < current.name.length) best = preset
        }
    }
    return best
}

/** 归一化：小写 + 非字母数字转空格 + 折叠空格（"1MORE Triple Driver" → "1more triple driver"） */
private fun normalizeName(raw: String): String {
    val sb = StringBuilder(raw.length)
    raw.forEach { ch -> sb.append(if (ch.isLetterOrDigit()) ch.lowercaseChar() else ' ') }
    return sb.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
}
