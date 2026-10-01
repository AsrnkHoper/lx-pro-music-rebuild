package com.lxpro.music

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** 应用入口。⛔ 这里禁止做 IO（见 03 §8.3 启动优化清单）。 */
@HiltAndroidApp
class LXProApp : Application()