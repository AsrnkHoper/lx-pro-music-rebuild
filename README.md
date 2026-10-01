# LX Pro

> Android 原生音乐播放器 · Kotlin + Jetpack Compose + Media3
> 双模式：**在线音源**（搜索 / 播放 / 下载）+ **本地音乐**（扫描 / 导入 / 播放）

---

## 这是什么

LX Pro 是 **从零重写** 的 Android 原生音乐播放器，只继承经验与音源思路，不继承任何前作代码。
音源协议兼容 [lx-music](https://github.com/lyswhut/lx-music-mobile) 的 2.0 脚本协议，社区脚本可直接导入。

| 项 | 值 |
|---|---|
| 显示名 | LX Pro |
| 包名 | `com.lxpro.music` |
| 平台 | Android 原生（非跨端框架） |
| 语言 / UI | Kotlin · Jetpack Compose · Material 3 |
| 播放内核 | AndroidX Media3（ExoPlayer） |
| minSdk / targetSdk / compileSdk | 26 / 35 / 36 |
| 许可证 | **GPL-3.0**（见下） |

---

## 合规声明

> LX Pro 是一款开源的本地音乐播放器。
> 本应用不提供、不分发、不推荐任何音乐内容或音源脚本。
> 用户自行导入的音源脚本由其自行承担相应责任。
> 本应用不对用户使用第三方音源产生的任何后果负责。
> 请支持正版音乐。

只内置 Bilibili 音源（视频音源，非商业曲库）；其他商业平台音源需用户自行导入 lx 2.0 兼容脚本。

---

## 构建

要求：JDK 17、Android SDK（compileSdk 36 / build-tools 36）、Gradle 由 wrapper 提供（9.3.1）。

```bash
# 调试包
./gradlew :app:assembleDebug
# 单元测试
./gradlew testDebugUnitTest
```

产物：`app/build/outputs/apk/debug/app-debug.apk`
安装到真机（校园网设备隔离，**必须 USB 线**）：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### ⚠️ 已知工具链约束

`compileSdk 37` 尚未发布，导致以下依赖存在**版本天花板**（详见 `gradle/libs.versions.toml` 注释）：

| 依赖 | 当前锁定 | 天花板原因 |
|---|---|---|
| navigation | 2.9.8 | 2.10.x 要求 compileSdk 37 |
| hilt-navigation-compose | 1.3.0 | 1.4.x 要求 compileSdk 37 |
| okhttp | 5.4.0 | 5.5.x 要求 compileSdk 37 |
| core-ktx | 1.16.0 | 1.19.x 要求 compileSdk 37 |

另：**AGP 9.x 内置 Kotlin**，模块**不得**再单独 apply `org.jetbrains.kotlin.android`。

---

## 仓库结构

```
app/                  单 Activity + Compose Navigation + Hilt 装配
build-logic/          约定插件（lxpro.android.application / .library / .compose / .hilt / .room）
core/
  common/             工具（搜索关键词归一化等）
  model/              纯模型（Song / Quality / SourceId / 指纹）
  designsystem/       主题：七套情绪配色 token + LXType + LXMotion + 氛围层 + 基础组件
  datastore/          设置项（DataStore Preferences）
  database/           本地库（Room）
  network/            OkHttp 与请求头策略
  media/              Media3 封装（含 per-request 请求头注入）
source/api/           音源接口契约（SourceApi / SourceRegistry）
feature/              功能模块（随里程碑逐个增设）
```

依赖规则：`feature/* → core/*`、`feature/A ↛ feature/B`、`core/* ↛ feature/*`。

---

## 贡献

本项目采用 **GPL-3.0 + 贡献者许可协议（CLA）** 的双授权模式，提交代码前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 与 [CLA.md](CLA.md)。

---

## 许可证

[GPL-3.0](LICENSE)。

本项目与 [LX Music](https://github.com/lyswhut/lx-music-mobile) **无代码关系**，仅兼容其音源脚本协议。
感谢 LX Music 作者 lyswhut 及社区的开源贡献。