# 贡献指南

## 提交前必读：CLA

本项目采用 **GPL-3.0 + 贡献者许可协议（CLA）** 的双授权模式：

- 公开版本永久以 **GPL-3.0** 发布，任何人都可自由使用、修改、分发；
- 项目作者保留以**其他许可证**（含商业许可）分发本项目的能力（用于未来的增强版本）。

因此，**每一个外部贡献者都必须先签署 [CLA.md](CLA.md)**，把该贡献的版权许可授权给项目作者。
**未签署 CLA 的 PR 不会被合并。**

> 如果你不接受 CLA，请不要提交 PR —— 你仍可自由 fork 并遵循 GPL-3.0 使用本项目。

---

## 开发流程

1. 从 `main` 切出 feature 分支：`git switch -c feature/xxx`
2. 一个功能模块 = 一个闭环，**不要一口气堆多个功能**
3. 本地构建与自测：
   ```bash
   ./gradlew testDebugUnitTest :app:assembleDebug
   ```
4. 提交 PR 到 `main`，CI 必须全绿

---

## 编码约定

| 项 | 约定 |
|---|---|
| 架构 | 多模块 + Clean Architecture 简化版；`feature/* → core/*`，`feature` 之间禁止互相依赖 |
| 状态 | ViewModel + StateFlow，单向数据流（UDF） |
| 依赖注入 | Hilt（KSP）；Room 亦走 KSP |
| 颜色 / 字号 | **一律取设计 token**，禁止硬编码颜色与游离字号 |
| 性能 | `LazyColumn` 必须设 `key` 与 `contentType`；composition 中禁止 IO |
| 提交信息 | 一行说清「做了什么」，别写「更新」「修改」 |

---

## 绝对不要提交

密码、token、私钥、`local.properties`、keystore、构建产物（见 `.gitignore`）。

---

## 音源合规（硬约束）

- 只内置 Bilibili 音源；**不内置**任何商业平台音源
- App 内**不提供**任何音源脚本的分发 / 推荐入口，只提供「导入本地文件」
- 不得把音源脚本或其下载地址写进仓库