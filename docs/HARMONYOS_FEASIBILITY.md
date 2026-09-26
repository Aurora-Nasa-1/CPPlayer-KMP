# CPPlayer 适配鸿蒙（HarmonyOS NEXT）可行性分析

> 分析时间：2026-09-26 ｜ 分析对象：本仓库当前工作区（含在途改动）
> 结论一句话：**技术上可行，且这份代码的分层比一般 KMP 项目更适合移植；但没有任何一条路线是"改个 target 就行"，
> 且当前 Compose / Kotlin 版本领先于鸿蒙侧所有可用工具链 —— 版本回退是最大的战略风险。**

---

## 0. 结论速览

| 维度 | 判断 |
|------|------|
| `core` 后端能否复用 | ✅ **能，约 9.8k 行 `commonMain` 逻辑基本原样可用**（仅 1 处硬阻塞） |
| `app` 前端能否复用 | ⚠️ **取决于 UI 路线**。走 Compose fork 可复用但有版本风险；走 ArkTS 则 16.4k 行需重写 |
| 官方支持 | ❌ **无**。JetBrains 至今没有官方 `ohosArm64` 目标，也没有官方 CMP 鸿蒙支持 |
| 生产可用性 | ⚠️ 全部依赖腾讯（Kuikly / ovCompose / KuiklyBase）与社区（CPF-KMP-CMP）的 fork |
| 最大技术阻塞 | `core/commonMain` 里的 `AudioPlayerImpl` 依赖只有 JVM/Android 产物的三方库 |
| 最大环境阻塞 | 现有 KMP-OHOS 工具链**以 macOS aarch64 为宿主**，本机是 Windows |
| 建议 | 先做「可移植性手术」（Phase 0，零行为变化且**与是否上鸿蒙无关都是收益**），再决定 UI 路线 |

---

## 1. 可移植性基线：这份代码的先天条件

实测各源集规模（Kotlin，不含测试）：

| 源集 | 文件 | 行数 | 鸿蒙处置 |
|------|-----:|-----:|---------|
| `core/src/commonMain` | 73 | 9,808 | ✅ 直接复用 |
| `app/src/commonMain` | 78 | 16,355 | ⚠️ 取决于 UI 路线 |
| `core/src/jvmMain` | 13 | 1,892 | 🔧 **必须拆**（详见 §3.2） |
| `core/src/androidMain` | 9 | 721 | 保留（Android 端不变） |
| `core/src/desktopMain` | 8 | 187 | 保留（桌面端不变） |
| `app/src/androidMain` | 9 | 465 | 保留 |
| `app/src/desktopMain` | 15 | 1,730 | 保留 |
| `app-android/src/main` | 4 | 236 | 保留 |
| **合计** | **209** | **31,394** | |

**关键结论：83% 的代码在 `commonMain`，只有 17% 是平台专属。**

这在 KMP 项目里属于**分层相当干净**的一档，原因有三：

1. `commonMain` 里 **零** `java.*` / `javax.*` / `android.*` 直接 import（已全量 grep 核实）。
2. 平台能力收口在 **47 个 `expect` 声明**里（core 19 + app 28），没有"到处 `if (isAndroid)`"的散落判断。
3. `MusicBackend` 是明确的单一后端门面，`docs/ARCHITECTURE.md` §4 已经写死了源集分层规则 —— 移植时不需要重新发现边界。

> ⚠️ 但有一个**例外**，见 §3.1：`commonMain` 里存在一处 JVM-only 三方库的类型泄漏，
> 它使 `:core` 今天**无法编译到任何非 JVM 目标**。

---

## 2. 鸿蒙侧的生态现状（2026-09）

### 2.1 没有官方 Kotlin/Native 鸿蒙目标

JetBrains 主线 Kotlin 至今**没有** `ohosArm64` / `harmonyOSArm64` 目标。鸿蒙上的 Kotlin/Native 全部来自 fork：

| 提供方 | 形态 | 版本基线 | 状态 |
|--------|------|---------|------|
| [`zxystd/Kotlin-OHOS`](https://github.com/zxystd/Kotlin-OHOS) | 编译器 + konan + gradle 插件 fork | Kotlin `2.1.255-SNAPSHOT`，HarmonyOS Beta1 SDK | ⚠️ 最后提交 **2024-09**，个人项目，**仅支持 macOS aarch64 宿主** |
| [`Tencent-TDS/KuiklyBase-platform`](https://github.com/Tencent-TDS/KuiklyBase-platform) | 基础组件鸿蒙化（含 CMP、skiko、coroutines、serialization、datetime、okio） | CMP `1.6.1`、coroutines `1.8.0`、serialization `1.7.1-KBA-003`、datetime `0.6.0-RC.2-KBA-002` | ✅ 腾讯在维护，最后提交 **2026-02** |
| [`Tencent-TDS/ovCompose-multiplatform-core`](https://github.com/Tencent-TDS/ovCompose-multiplatform-core) | 基于 CMP 的鸿蒙/iOS 适配（腾讯视频团队） | 分支 `ov/compose-1.6.1` | ✅ 开源，Apache 2.0 |
| CPF-KMP-CMP（社区） | Kotlin 编译器加 `ohosArm64` + CMP 对接 OHRender | Kotlin `2.2.21-0.3.0`、CMP `1.9.2-0.3.0`、Ktor `3.3.3-0.3.0` | ⚠️ 社区项目，Maven 仓库为 `maven.eazytec-cloud.com` |
| `Tencent-TDS/KuiklyUI` | 不走 Compose 的 KMP UI 框架，渲染到 **ArkUI Native / C-API** | 独立 DSL | ✅ 腾讯**生产环境在用** |

### 2.2 版本对照 —— 这是最大的风险

| 组件 | **本项目** | 鸿蒙侧最新已知 | 差距 |
|------|-----------|---------------|------|
| Kotlin | `2.4.10` | `2.2.21-0.3.0` | ⬇️ 落后 2 个 minor |
| Compose Multiplatform | `1.11.1` | `1.9.2-0.3.0`（社区）/ `1.6.1`（腾讯） | ⬇️ **落后 2–5 个 minor** |
| Ktor | `3.0.3` | `3.3.3-0.3.0` | ⬆️ 可升 |
| kotlinx-serialization | `1.7.3` | `1.9.1-0.3.0` | ⬆️ 可升 |
| kotlinx-datetime | `0.6.1` | `0.6.0-RC.2-KBA-002` | ➡️ 基本持平 |
| kotlinx-coroutines | `1.9.0` | `1.8.0` | ⬇️ 略落后 |

> 本项目已经踩过一次"Compose 版本必须锁死"的坑（见 `libs.versions.toml` 里 materialkolor 锁 `5.0.0` 的注释：
> 5.0.1 会顶到 compose 1.12.0 造成版本错配）。**把 Compose 从 1.11.1 降到 1.9.2 意味着
> 要把这类版本对齐工作重做一遍，而且要重新验证 material3 的 API 差异。**

### 2.3 好消息

- **Rust 有官方鸿蒙目标**：`aarch64-unknown-linux-ohos`（Tier 2），工具链与交叉编译流程 2026 年已成熟。
  这意味着 `reference/netease-module-rust` 的 Rust 核心**可以原样交叉编译到鸿蒙**。
- **Ktor 官方支持 Kotlin/Native 服务端**：仅 CIO 引擎、无 HTTPS（需反代）。
  本项目的 `LocalServer` 正好是**纯 HTTP + CIO**，理论上形态吻合。
- **qrose 已有鸿蒙 fork**（社区版 `1.1.2`），本项目用的正是 qrose。

---

## 3. 逐项适配清单（按阻塞等级）

### 3.1 🔴 P0-1：`commonMain` 里的 JVM-only 三方库泄漏

**位置**：`core/src/commonMain/kotlin/cp/player/core/playback/PlatformPlayer.kt:78-79`

```kotlin
class AudioPlayerImpl : PlatformPlayer {
    private val player = AudioPlayer()   // io.github.kdroidfilter:composemediaplayer-audio
```

实测该库在 Gradle 缓存中**只有两个产物**：

```
io.github.kdroidfilter/composemediaplayer-audio-jvm
io.github.kdroidfilter/composemediaplayer-audio-android
```

**没有任何 native 产物。** 而 `AudioPlayerImpl` 定义在 `commonMain`，
所以 `:core` 今天**编译不到 `ohosArm64`**，也编译不到 iOS/macOS —— 这是第一块必须搬走的石头。

**为什么它现在没炸**：Android 侧也有这个库的 `-android` 产物，所以两端都能编过。
`PlatformPlayer.android.kt:312` 甚至把它当兜底：

```kotlin
actual fun createPlatformPlayer(context: PlatformContext): PlatformPlayer =
    context.androidContext()?.let(::Media3PlatformPlayer) ?: AudioPlayerImpl()
```

**修法**：整类下沉到 `jvmMain`（`androidMain` / `desktopMain` 都已 `dependsOn(jvmMain)`，**行为零变化**），
再由 `ohosMain` 提供一个鸿蒙实现。

---

### 3.2 🔴 P0-2：`jvmMain` 作为中间层，native 目标无法消费

当前源集图：

```
commonMain ──▶ jvmMain ──▶ { androidMain, desktopMain }
```

`jvmMain` 里那 1,892 行是 **Android 与桌面共享的 JVM 实现**，Kotlin/Native 一个都用不了：

| 文件 | 用到的 JVM-only 能力 | 鸿蒙处置 |
|------|---------------------|---------|
| `control/LocalServer.jvm.kt` | Ktor CIO 服务端 + `java.net.HttpURLConnection` | 需 ohos 版（Ktor native server，仅 CIO/无 HTTPS） |
| `control/ExternalPusher.jvm.kt` | JVM 网络 | 需重写或裁剪 |
| `download/JvmDownloadExecutor.kt` | Ktor OkHttp 引擎 + `java.io.File` | 换 curl 引擎 + 沙箱路径 |
| `provider/JniProvider.kt` | `System.load` + `external fun` + JNI 符号 | **必须改 NAPI**（见 §3.4） |
| `provider/BinaryProvider.kt` | `java.io.File` | 换 okio / 沙箱路径 |
| `local/DesktopLocalMediaSource.kt`、`LocalMediaIndexJvm.kt` | `java.io.File`、路径 | 需 ohos 版（媒体库扫描 API） |
| `playback/DesktopStreamLocalizer.kt` | 本地文件缓存 | 需 ohos 版 |
| `util/DesktopDataDir.kt` | `~/.cpplayer` 运行时目录 | 鸿蒙沙箱内路径 |
| `util/PlatformSupport.kt`、`HttpClientFactory.kt` | `System.currentTimeMillis`、OkHttp | 换 curl 引擎 |

**目标源集图**：

```
commonMain ──┬─▶ jvmMain ──▶ { androidMain, desktopMain }
             └─▶ ohosMain
```

⚠️ 这一步会**连带影响**：
- `docs/ARCHITECTURE.md` §4 的源集规则表要改；
- `IntegrationBoundaryTest` 目前**扫四个源集**钉住 `integration/` 包边界（见 ARCHITECTURE §1），
  加了 `ohosMain` 必须同步扩到五个 —— 否则会变成"看起来在守着其实没扫到"。

---

### 3.3 🟠 P1-1：HTTP 客户端引擎

| 平台 | 现在 | 鸿蒙 |
|------|------|------|
| Android | `ktor-client-okhttp` | — |
| Desktop | `ktor-client-okhttp` | — |
| HarmonyOS | — | **`ktor-client-curl`** |

⚠️ 已确认的坑（来自 CPF-KMP-CMP 的实战记录）：
- `ktor-client-cio` 在 Native 上**不支持 TLS**，会直接抛 `TLS sessions are not supported on Native platform.`，
  所以 HTTPS 请求必须走 curl 引擎。
- 鸿蒙应用**默认没有网络权限**，不加 `ohos.permission.INTERNET` 会得到 `POSIX error 13: Permission denied`。

---

### 3.4 🟠 P1-2：音源模块（Provider）加载方式必须改

这是本项目的**核心资产**，也是最需要单独规划的一块。

现状（`core/src/jvmMain/.../JniProvider.kt`）：

```kotlin
external fun startNativeServer(host: String, port: Int)
external fun nativeCallApi(method: String, paramsJson: String): String
external fun analyzeAudioFile(path: String): String
```

`external fun` 走 JNI，native 侧必须导出 `Java_cp_player_core_provider_JniProvider_*` 符号
（`docs/ARCHITECTURE.md` §5 已记录这条硬绑定及其"load 成功但一调用就崩"的伪装症状）。

**鸿蒙上的等价物是 NAPI**，不是 JNI。所以：

- ✅ **Rust 业务逻辑可原样复用** —— 加 `aarch64-unknown-linux-ohos` target 交叉编译即可；
- 🔧 **导出符号层必须重写**（`jni.rs` → `napi.rs`），`build_module.sh` 要加 ohos 目标；
- ⚠️ **"音源模块二进制三端通用"的假设被打破**：鸿蒙版模块必须**单独编译、单独分发**，
  模块仓库要多一套产物与发布流程；
- ⚠️ 相应地，`ProviderManager` / `ModuleManager` 里"下载 .so 并 load"的路径要区分平台，
  且鸿蒙沙箱**不允许**像桌面那样从任意路径 `dlopen`（需放入应用自己的 native lib 目录）。

---

### 3.5 🟠 P1-3：六个 Compose 生态三方库需要鸿蒙产物

`app/commonMain` 的 Compose 依赖里，除 `compose.*` 本体外还有：

| 库 | 用途 | 鸿蒙侧现状 |
|----|------|-----------|
| `cafe.adriel.voyager`（navigator / screenmodel / transitions） | 导航 + ScreenModel | ❌ 未见鸿蒙产物，需 fork 或改用 Kuikly 路由 |
| `io.github.alexzhirkevich:qrose` | 二维码 | ✅ 已有社区鸿蒙 fork（`1.1.2`） |
| `io.coil-kt.coil3:coil-compose` | 图片加载 | ❌ 需 fork；且 `coil-network-okhttp` 要换 ktor 引擎 |
| `com.materialkolor:material-kolor` + `material-color-utilities` | Material You 取色 | ❌ 需 fork（纯 Kotlin，工作量相对小） |
| `com.mocharealm.accompanist:lyrics-ui` / `lyrics-core` | 歌词渲染 | ❌ 需 fork |
| `composemediaplayer-audio` | 桌面音频 | 🔴 见 §3.1，鸿蒙必须整体替换 |

**结论**：走 Compose 路线，等于要**长期维护 4–6 个三方库的鸿蒙 fork**。
这是选路线时最容易被低估的成本。

---

### 3.6 🟡 P2：需要重写或应当裁剪的平台能力

**必须重写（有明确鸿蒙对应 API）**

| 现有实现 | 鸿蒙对应 |
|---------|---------|
| `PlatformPlayer.android.kt`（Media3 ExoPlayer）/ `AudioPlayerImpl`（rodio） | AVPlayer（ArkTS）或 AVPlayer/AudioRenderer 的 **C-API**（cinterop，可全留在 Kotlin/Native） |
| `PlatformMediaControls.android.kt`（Media3 MediaSession）/ `JmtcMediaControls.desktop.kt`（SMTC） | **AVSession** |
| `AndroidSettingsStorage` / `DesktopSettingsStorage` | 应用沙箱 + preferences（⚠️ `defaultSettingsStorage` 全量回写的坑不变） |
| `PlatformContext`（Android `Context` / 桌面空实现） | UIAbility Context（经 NAPI 取） |
| `openTargetApp` / `isPackageInstalled`（Android 包模型） | `bundleManager` + `Want` |
| `saveQrCodeToGallery` | `photoAccessHelper` |
| `downloadUpdate` | 需适配鸿蒙的 HAP 安装流程 |
| `requestMediaScanPermission` / 媒体库扫描 | 鸿蒙媒体库 API（`photoAccessHelper` / `MediaLibrary`） |

**建议直接裁剪（鸿蒙上无意义或不可行）**

- **对外集成层**（`integration/`：`/api/v1/...` + `~/.cpplayer/integration.json` 自动发现）
  —— 设计前提是"同一台桌面上还有别的软件来发现并拉流"。鸿蒙应用是沙箱，**没有这个生态位**。
  连同 `LocalServer` 的流输出面、`ExternalPusher`（外部推送）一起，属于桌面专属能力。
  > 这一条能省掉相当一部分工作量 —— 但它同时意味着 `IntegrationBoundaryTest`、
  > `INTEGRATION_API.md`、`capabilities` 的同步纪律在鸿蒙端全部不适用，需要明确标注平台差异。
- **桌面渲染调优 / VRR / 安全模式回退**（`DesktopRenderTuning.kt`、`PlatformRenderTuning.desktop.kt`）
  —— 鸿蒙 PC 上部分有意义，移动端可整体 no-op。
- **桌面滚动条 / 鼠标滚轮 / Pager 鼠标控制**（`DesktopScrollbars`、`HorizontalWheelScroll`、`PagerMouseControl`）
  —— 鸿蒙 PC 保留，移动端 no-op（`actual` 给空实现即可，成本很低）。
- **动态取色**：鸿蒙没有 Monet。`supportsPlatformDynamicScheme()` 返回 `false` 即可 ——
  桌面已经是这条路径（回落到 materialkolor 从封面取色），**复用成本低**。

**顺手清掉**

- `gradle/libs.versions.toml` 里的 `mp3spi` / `jflac-codec` / `jlayer` **全仓零引用**（已 grep 核实），
  是死条目，与鸿蒙无关但也该删。

---

### 3.7 🔴 环境与工具链成本（本项目特有的障碍）

| 项 | 现状 | 影响 |
|----|------|------|
| **开发宿主** | 本项目在 **Windows** 上开发 | ⚠️ `Kotlin-OHOS` 明确只支持 **macOS aarch64** 宿主；ovCompose 需要 `Ninja` + DevEco 的 `hvigor` 打包 `compose.har`（文档示例是 `/Applications/DevEco-Studio.app/...`）⇒ **需要一台 macOS 构建机或 CI runner** |
| 构建产物 | — | 鸿蒙 KMP 的 `libkn.so` **debug 就有 70–80 MB**，接入 curl 后还会涨 |
| 必需工具 | — | DevEco Studio + HarmonyOS SDK；`ohpm` / `hvigor` / `hdc` |
| 宿主 JDK | — | cinterop 在部分工具链下要求 aarch64 的 Zulu JDK8 |
| 构建验证 | 本仓库 `.gradle` 锁常被并行会话占用 | 加一个 native 目标后构建时间与缓存竞争会明显恶化 |

> 非技术风险（一句话）：本应用依赖**第三方音源模块**分发。鸿蒙应用市场的审核口径与 Google Play 不同，
> 上架可行性需要单独评估 —— 这不属于技术适配范畴，但会影响"做不做"的决策。

---

## 4. 四条路线对比

| | **A. Compose fork**<br>(CPF-KMP-CMP / ovCompose) | **B. Kuikly** | **C. ArkTS UI + core 编 .so** | **D. ArkTS 全重写** |
|---|---|---|---|---|
| `core` 复用 | ✅ 高（需 Phase 0） | ✅ 高 | ✅ 高 | ❌ 归零 |
| `app` UI 复用 | ✅ 高（**但要降 Compose 版本**） | ⚠️ 需用 Kuikly DSL 重写 | ❌ ArkTS 重写 | ❌ ArkTS 重写 |
| 鸿蒙成熟度 | ⚠️ 社区/腾讯 fork，版本落后 | ✅ **腾讯生产验证**，渲染走 ArkUI C-API | ✅ 官方原生路线，无 fork 依赖 | ✅ 官方 |
| 三方库风险 | 🔴 要维护 4–6 个 fork | 🟡 Kuikly 自带组件库，但生态小 | 🟢 无 | 🟢 无 |
| 主要代价 | 版本回退 + 长期跟不上下游 | UI 重写（Kotlin，非 Compose） | UI 重写（ArkTS）+ NAPI 桥 | 全部重写 |
| 相对工作量 | 中 | 中高 | 高 | 最高 |

**推荐路径**

1. **无论选哪条路线，先做 Phase 0（§5）** —— 它零行为变化，且本身就是分层改善。
2. **若目标是"尽快在鸿蒙上有可用版本"** → **B（Kuikly）**。UI 虽要重写，但仍在 Kotlin 里，
   `core` 原样复用，且是腾讯自己生产环境验证过的鸿蒙路径。
3. **若目标是"一套 Compose 代码吃三端"** → **A**，但必须接受：
   Compose 从 1.11.1 降到 1.9.2、长期维护三方库 fork、以及"上游一升级就跟不上"的结构性风险。
4. **C** 适合把鸿蒙当作长期一等公民、愿意一次性投入重写 UI 的场景；它没有 fork 依赖，最抗时间。

**不推荐 D**：16.4k 行 UI + 9.8k 行后端逻辑全部重写，等于放弃这个仓库最值钱的部分。

---

## 5. Phase 0：可移植性手术（**与是否上鸿蒙无关，都该做**）

这是唯一一步"即使最后决定不做鸿蒙也不亏"的工作：

| # | 动作 | 验收标准 |
|---|------|---------|
| 1 | 把 `AudioPlayerImpl` 从 `core/commonMain` 下沉到 `core/jvmMain` | `:core:compileKotlinDesktop` / Android 编译与测试**全绿**，行为零变化 |
| 2 | 把 `jvmMain` 里"逻辑可移植"与"JVM 专属"分开：`LocalServer` / `DownloadExecutor` / `BinaryProvider` / `LocalServerConfigStore` 的**接口与纯逻辑**上提 `commonMain`，实现留在 `jvmMain` | 同上；`commonMain` 仍保持零 `java.*` import |
| 3 | 删除 `libs.versions.toml` 里 `mp3spi` / `jflac` / `jlayer` 三个死条目 | 构建通过 |
| 4 | 在 `ARCHITECTURE.md` §4 补一句"新增 native 目标时 `jvmMain` 不可作为中间层"，并给 `IntegrationBoundaryTest` 的源集列表加注释说明"加源集必须同步" | 文档与测试注释就位 |

做完 Phase 0 后，`core` 就具备了"加一个 native 目标"的形状。

---

## 6. Phase 1：最小可验证切片（建议）

不要一上来就移植 UI。先证明**最难的那三个未知数**：

```
ohosArm64 目标 ──▶ 编译 core ──▶ 产出 libcp_core.so ──▶ hdc 推真机
                                                        │
        ┌───────────────────────────────────────────────┤
        ▼                       ▼                       ▼
  ① 网络：一次 API 调用    ② 音频：AVPlayer 出声    ③ 模块：NAPI 调 Rust
     (ktor-client-curl)      (ohosMain 实现)          (netease 模块重编)
```

这三件事任一不通，路线就要重新评估；三件都通，剩下的都只是工作量。

---

## 7. 附：需要新建的 `ohosMain` 清单

| 模块 | 需要新增的 `actual` | 参考现有实现 |
|------|-------------------|-------------|
| `core` | `PlatformPlayer` | `PlatformPlayer.android.kt`（Media3 那版结构最接近） |
| | `PlatformContext` / `PlatformInfo` / `PlatformSupport` | `androidMain` 对应文件 |
| | `ProviderFactory`（NAPI 版）、`createJniProvider` | `jvmMain/provider/` |
| | `HttpClientFactory`（curl 引擎） | `jvmMain/util/HttpClientFactory.kt` |
| | `SettingsStorage` | `AndroidSettingsStorage.kt` |
| | `createLocalMediaSource` / `localMediaReadText` / `localMediaWriteText` | `androidMain/local/` |
| | `createLocalServer` / `createExternalPusher` / `createIntegrationDescriptorWriter` | **建议 no-op**（桌面专属能力） |
| | `defaultDownloadExecutor` / `createStreamLocalizer` | `jvmMain` 对应文件 |
| `app` | `PlatformActions`（13 个函数） | `PlatformActions.android.kt` |
| | `PlatformFilePicker` / `PlatformShare` / `PlatformMediaControlsEffect` | androidMain 对应文件 |
| | `CoverBitmap` / `PlatformSeed` | `PlatformSeed.android.kt`（鸿蒙无 Monet → 返回 false 即可） |
| | `DesktopScrollbars` / `HorizontalWheelScroll` / `PagerMouseControl` | **no-op 实现**（移动端）/ 保留（鸿蒙 PC） |

**规模估计**：`ohosMain` 约需 **1,500–2,500 行**新代码（不含 UI），
即 `core` 侧 5,231 行平台代码中的一部分需要再写一遍，其余保留给 Android/Desktop。

---

## 参考来源

- Ktor 官方文档 · Native server（CIO 引擎、无 HTTPS、需新内存管理器）：<https://ktor.io/docs/server-native.html>
- 腾讯 KuiklyBase-platform（CMP 1.6.1 / skiko / coroutines / serialization / datetime / okio 的鸿蒙适配）：<https://github.com/Tencent-TDS/KuiklyBase-platform>
- 腾讯 ovCompose（CMP 鸿蒙 + iOS 适配，Apache 2.0）：<https://github.com/Tencent-TDS/ovCompose-multiplatform-core>
- Kuikly 在 HarmonyOS NEXT 上的架构与接入思路（ArkUI Native / C-API 渲染，`ohosMain` 分层）：<https://cloud.tencent.com/developer/article/2746098>
- Kotlin-OHOS（社区 Kotlin/Native 鸿蒙目标，仅 macOS aarch64）：<https://github.com/zxystd/Kotlin-OHOS>
- CPF-KMP-CMP 实战（Kotlin 2.2.21-0.3.0 / CMP 1.9.2-0.3.0 / Ktor 3.3.3-0.3.0，curl 引擎、INTERNET 权限、libkn.so 体积）
- 鸿蒙侧 Rust 交叉编译（`aarch64-unknown-linux-ohos` Tier 2）
