# DRM 端到端验证手册

本文记录 **真实解密链路**（设备 CDM / 浏览器 CDM + Apple 凭据）的验收方法。
信令解析、license 交换协议、打包产物的测试不能替代这里的步骤：只有 CDM
真的解出了音频，才算链路通。

## 1. 链路与责任边界

| 平台 | 解密载体 | key system | 现状 |
|---|---|---|---|
| Android | Media3 `FrameworkMediaDrm` | Widevine | 宿主已接入（清单经本地归一化代理） |
| Windows | WebView EME 桥（Edge / Chrome / WebView2 内核） | Widevine | 宿主已接入 |
| macOS / iOS | 原生 FairPlay | FairPlay | **未接入**，需要 Apple 开发者凭据与设备 |

Provider（Rust 插件）在三条链路上完全一致：只做 `resolveDrm` 信令与
`exchangeLicense` 字节转发，不持有 CDM，也不生成 content key。

两个平台都绕不开同一处清单兼容问题：Apple 增强 HLS 用
`METHOD=ISO-23001-7`，而 hls.js 1.6 与 Media3 1.11 的解析器都只认
`METHOD=SAMPLE-AES-CTR`。因此宿主不把上游 playlist 直接交给播放器，
而是先经 `HlsPlaylistNormalizer`（commonMain）改写 method、绝对化 URI。
这也是 `resolveDrm` 返回的 URL 不能跳过宿主直接播放的原因。

## 2. 前置条件

| 项 | 要求 |
|---|---|
| Apple Music 订阅 | 有效的 `media-user-token`（个人库与加密播放需要） |
| Apple Music 地区 | 与 `storefront` 一致 |
| Android 设备 | 非模拟器、支持 Widevine L1/L3 的真机；`adb devices` 可见 |
| Windows 内核 | Edge / Chrome，或只装了 WebView2 Runtime（`EdgeCore\*` 内含 WidevineCdm） |
| 曲目 | 目录曲目 ID（加密流不覆盖免费试听） |

凭据只放会话内存：**不要**写进仓库、`.env` 或日志。推荐通过环境变量临时注入：

```powershell
$env:AM_MEDIA_USER_TOKEN = '<media-user-token>'
$env:AM_DEVELOPER_TOKEN  = '<developer-token>'   # 可留空，由插件自动发现
```

## 3. Windows（WebView EME / Widevine）

桥接代码位于 `core/src/desktopMain/kotlin/cp/player/core/playback/drm/`，播放页与
`hls.min.js`（Apache-2.0，约 550 KB）在 `core/src/desktopMain/resources/.../drm/`。
二者随桌面 jar 分发，不需要用户额外安装。

### 3.1 自动检查

```powershell
cd E:\project\CPPlayer-KMP
.\gradlew.bat :core:desktopTest --tests 'cp.player.core.playback.drm.*' --console=plain
```

| 测试 | 覆盖 |
|---|---|
| `WebViewDrmSupportTest` | 内核发现优先级、清单归一化（`ISO-23001-7` → `SAMPLE-AES-CTR`）、URI 绝对化/代理化 |
| `WebViewDrmProtocolTest` | `/player` `/hls.min.js` `/manifest` `/license` `/ws`、token 校验、challenge/license 往返 |
| `WebViewDrmPlatformPlayerTest` | 状态机映射、命令转发、非 Widevine 拒绝 |
| `WebViewDrmBrowserSmokeTest` | **真实浏览器内核**启动并完成 `com.widevine.alpha` 的 `requestMediaKeySystemAccess` |
| `DrmPlaylistNormalizerTest` | Android / 桌面共用的清单归一化（`ISO-23001-7` → `SAMPLE-AES-CTR`） |

`WebViewDrmBrowserSmokeTest` 在没有内核的机器上会打印跳过信息并返回；
见到 `[WebViewDrmBrowserSmokeTest] 使用内核: …` 才说明它真的跑了。

### 3.2 手工验收

1. 启动客户端，确认 `%TEMP%\cpplayer-drm-profile` 下出现 Chromium profile；
2. 播放一首 Apple Music 加密曲目，观察进程列表里出现 `msedge.exe --app=…`；
3. 断言以下三点，缺一不可：
   - 有实际音频输出（不是静音、不是回退到 preview）；
   - 进度条随时间前进，拖动可定位；
   - 日志里没有 CDM 错误（`keySystemNoAccess` / `keySystemNoKeys`）。
4. 播放第二首加密曲目：应复用同一浏览器进程，不重复开窗。
5. 切到明文曲目再切回加密曲目：应能正确在 Flick 引擎与 WebView 之间切换。

### 3.3 常见失败

| 症状 | 原因 |
|---|---|
| `supportedDrmKeySystems()` 为空 | 未找到内核；装 Edge 或 WebView2 Runtime |
| 播放页未就绪超时 | 内核启动失败 / 被防火墙拦截 loopback；查 `%TEMP%\cpplayer-drm-profile` |
| `keySystemNoAccess` | 该内核未随附 Widevine CDM（例如纯净 Chromium） |
| `keySystemLicenseRequestFailed` | Provider 未返回 license，或 media-user-token 失效 |
| 有声但立即停 | 清单未归一化（`METHOD=ISO-23001-7` 漏改）——检查 `/manifest` 输出 |
| 页面一直停在 buffering | 分片 URI 被解析成 `127.0.0.1` 相对地址——检查 `/manifest` 输出里分片是否已是 CDN 绝对地址 |

## 4. Android（设备 MediaDrm / Widevine）

### 4.1 设备准备

```powershell
adb devices -l
adb shell dumpsys media.drm | Select-String -Pattern 'widevine|Widevine'
```

设备必须报告 `com.widevine.alpha`；模拟器与无 DRM 的定制 ROM 不算。

### 4.2 安装与运行

```powershell
cd E:\project\CPPlayer-KMP
.\gradlew.bat :app-android:installDebug
adb logcat -c
adb logcat | Select-String -Pattern 'MediaDrm|DrmSession|am_sdk_provider|CPPlayer'
```

`loadDrm` 会在设备上起一个只绑 `127.0.0.1` 的清单代理（随机端口 + 一次性
token），Media3 实际装载的是 `http://127.0.0.1:<port>/manifest?...`。
看到播放器 URL 是 `127.0.0.1` 属正常行为；分片仍直连 Apple CDN。

### 4.3 验收断言

| 检查 | 期望 |
|---|---|
| `MediaDrm` 会话建立 | 出现 `acquireWebPlaybackLicense` 成功，无 `Provisioning` 报错 |
| 解密输出 | 有音频、非静音、无 `ERROR_CODE_*` |
| 密钥释放 | 切歌后旧会话 close，不长期持有 license |
| 回退 | Flick 模式遇到 DRM 自动切 Media3，而非直接失败 |
| 清单 | 代理返回的媒体清单含 `SAMPLE-AES-CTR`，不含 `ISO-23001-7` |

## 5. FairPlay（待接入）

FairPlay 只能在 Apple 平台完成，且需要：

1. Apple Developer 账号与 FairPlay Streaming 证书（`.cer` + 私钥）；
2. `skd://` key URI 的 license 服务（Apple 的 `acquireWebPlaybackLicense`
   支持 FairPlay 请求，但需要应用证书签名）；
3. 真机（iOS / macOS）——Windows / Android 无合法路径。

当前宿主的 `supportedDrmKeySystems()` 在 Apple 平台仍为空，因此不会请求
FairPlay 流。接入时需新增 `DrmSupport.ios/macos` 的 actual，并在
`WebViewDrmPlatformPlayer` 之外的平台播放器里实现 `AVContentKeySession`。

## 6. 验证记录模板

每次真机验收请把下面内容附在 PR 里（不要附 challenge / license 字节）：

```text
platform:        windows / android
device:          <型号 / 浏览器内核版本>
key system:      widevine
track id:        <catalog id>
quality:         drm-standard / drm-lossless
resolved:        <actualQuality>
license:         ok / <error category>
audio output:    ok (duration <s>)
seek:            ok
```

## 7. 与 Provider 的关系

Provider 侧只需要保证两件事，验证方式见 AM-CPPlayer-Plugin 仓库的 README：

1. `resolveDrm` 返回的 `keyUri` / `initData` 能让目标 CDM 生成 challenge；
2. `exchangeLicense` 把 challenge 原样发给 Apple，并把 `license` 解 base64
   后原样返回。

Provider 不需要、也不应该在日志或磁盘里保留任何一段字节。

`AM-CPPlayer-Plugin` 的 `src/bin/drm_e2e.rs` 是这条链路的自动验收工具：
它用真实 token 调 `resolveDrm`、从本机内核取真实 Widevine challenge、经
`exchangeLicense` 换成 license，并统计 `AudioContext` 里真实解出的 PCM。
判据是 `PCM frames > 0 && duration >= 1s`；任何一步失败都会以非零码退出，
不会把「拿到 license」当成「解密成功」。
