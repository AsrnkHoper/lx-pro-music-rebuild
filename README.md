# 阿玉音乐（LX Pro）

> Android 原生音乐播放器 · Kotlin + Jetpack Compose + Media3
> 双模式：**在线音源**（搜索 / 播放 / 下载）+ **本地音乐**（扫描 / 导入 / 播放）

---

## 这是什么

阿玉音乐（开发代号 LX Pro）是 **从零重写** 的 Android 原生音乐播放器，只继承经验与音源思路，不继承任何前作代码。
音源协议兼容 [lx-music](https://github.com/lyswhut/lx-music-mobile) 的 2.0 脚本协议，社区脚本可直接导入。

| 项 | 值 |
|---|---|
| 显示名 | 阿玉音乐 |
| 包名 | `com.lxpro.music` |
| 平台 | Android 原生（非跨端框架） |
| 语言 / UI | Kotlin · Jetpack Compose · Material 3 |
| 播放内核 | AndroidX Media3（ExoPlayer） |
| minSdk / targetSdk / compileSdk | 26 / 35 / 36 |
| 当前版本 | **v1.0.0**（初代完工，2026-10-27） |
| 许可证 | **GPL-3.0**（见下） |

## 功能一览

- **在线**：三内置源（kw / tx / wy）+ 酷狗 / 咪咕 / Bilibili + lx 2.0 脚本音源导入（QuickJS 沙箱），并发搜索、歌词 / 封面 / 评论、下载
- **本地**：媒体库扫描 + SAF 目录导入、内嵌封面 / 歌词、双重去重
- **播放**：双 ExoPlayer 交叉淡变（等功率曲线）、倍速（0.5x–2.0x 自定义）、播放模式五档、与其他应用同播开关、缓存秒开（稳定 cacheKey）
- **歌单**：两级结构、置顶添加、拖拽排序（视觉让位架构）、组合播放、备份导入导出
- **歌词**：平面 / 立体（3D 透视）双模式、繁简转换（opencc4j）、桌面悬浮歌词、系统媒体会话 / 锁屏控制
- **白噪音**：Noice CDN 音源、预设场景、自定义混音组合、睡眠定时
- **EQ**：142 型号参数化校正（32 段 peaking biquad）、精度筛选、收藏集合
- **统计**：六档时间维度、GitHub 式热力日历、歌手归并、听歌报告
- **个性化**：七套情绪配色 + 封面动态取色、inmyfeelings 文案系统、新手引导

---

## 合规声明

> 阿玉音乐是一款开源的本地音乐播放器。
> 本应用不提供、不分发、不推荐任何音乐内容或音源脚本。
> 用户自行导入的音源脚本由其自行承担相应责任。
> 本应用不对用户使用第三方音源产生的任何后果负责。
> 请支持正版音乐。

只内置 Bilibili 音源（视频音源，非商业曲库）；其他商业平台音源需用户自行导入 lx 2.0 兼容脚本。
合规文档：[隐私政策](PRIVACY.md) · [用户协议](TERMS.md)

---

## 构建

要求：JDK 17、Android SDK（compileSdk 36 / build-tools 36）、Gradle 由 wrapper 提供（9.3.1）。

```bash
# 调试包
./gradlew :app:assembleDebug
# 发布包（无 keystore 时自动回退 debug 签名，可正常编译）
./gradlew :app:assembleRelease
# 单元测试
./gradlew testDebugUnitTest
```

产物：`app/build/outputs/apk/{debug,release}/`

正式发布使用独立签名 keystore（`keystore.properties` 提供凭据，**不入库**）；克隆源码直接构建即可得到 debug 签名的 release 包。

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
benchmark/            Macrobenchmark（启动 / 帧率 / 内存基线）
core/
  common/             工具（随机种子、文案系统、分享总线等）
  model/              纯模型（Song / Quality / SourceId / 指纹）
  designsystem/       主题：七套情绪配色 token + 封面动态取色 + LXMotion + 基础组件
  datastore/          设置项（DataStore Preferences）
  database/           本地库（Room）
  network/            OkHttp 与请求头策略
  media/              Media3 封装（双播放器交叉淡变 / EQ 处理器 / 白噪音引擎 / 缓存）
  playlist/           歌单仓库
  stats/              听歌统计
source/
  api/                音源接口契约（SourceApi / SourceRegistry）
  kw / tx / wy        内置源（酷我 / 腾讯 / 网易）
  kg / mg / bilibili  内置源（酷狗 / 咪咕 / B 站）
  script/             lx 2.0 脚本音源沙箱（QuickJS）
feature/              home / search / player / playlist / library / download / stats / noise / eq
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