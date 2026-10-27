package com.lxpro.core.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** 对齐 LX Music 的 BatteryOptimizationUtil：
 *  判断是否忽略电池优化 + 发起白名单请求 + 跳厂商自启动设置页。 */
object BatteryOptimizationUtil {

    /** Android 6.0+ 判断当前包是否已在电池优化白名单里。 */
    fun isIgnoring(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else true
    }

    /** 弹系统白名单请求页（系统会弹一个对话框让用户选「允许」）。
     *  Android 11+ 部分厂商 ROM 可能屏蔽，返回 false 表示请求没发出去，
     *  调用方应降级到跳 ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS。 */
    fun requestIgnore(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            try {
                // 部分 ROM 直接拒绝 ACTION_REQUEST，降级到设置页让用户手动加
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** 跳厂商自启动设置页。
     *  MIUI / ColorOS / EMUI / 三星各有独立 Intent，匹配不到就跳通用应用信息页。 */
    fun openAutoStartSettings(context: Context) {
        val pkg = context.packageName
        val intent = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco" -> {
                // MIUI：自启动设置 + 省电策略
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            "huawei", "honor" -> {
                // EMUI / MagicUI：启动管理
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity",
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            "oppo", "realme", "oneplus" -> {
                // ColorOS / OxygenOS：自启动管理
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            "vivo", "iqoo" -> {
                // OriginOS / FuntouchOS：后台管理
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            else -> null
        }
        try {
            if (intent != null && intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return
            }
        } catch (_: Exception) {}
        // 降级：应用详情页 + 电池优化设置页
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$pkg")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    /** 当前厂商友好名（用于设置页提示文案）。 */
    fun vendorHint(): String = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "redmi", "poco" -> "小米 / Redmi / POCO"
        "huawei", "honor" -> "华为 / 荣耀"
        "oppo", "realme", "oneplus" -> "OPPO / Realme / 一加"
        "vivo", "iqoo" -> "vivo / iQOO"
        "samsung" -> "三星"
        else -> "当前设备"
    }
}
