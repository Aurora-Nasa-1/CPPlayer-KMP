# FlickEngine 与本体的接入对照（能力矩阵 / 契约 / 验证）

> 这份文档解决一类**只靠读代码发现不了**的问题：能力在引擎里实现了、宿主里没接；
> 或者接了但接错了参数；或者 UI 声称支持、实际什么也没发生。
>
> 四条链路必须同时对齐：
> `native/flick-player-engine/src/**`（Rust 实现）
> → `src/jni_api.rs`（JNI 导出符号）
> → `DesktopHiResEngine`（Kotlin JNI 声明）
> → `DesktopHiResPlatformPlayer` / `PlaybackController` / 设置页
>
> 2026-11-10 更新：补齐 JNI 导出、重建 DLL、接入 HTTP 直连与通用能力。

---

## 1. 桌面端只有一条播放链路

`PlatformPlayer.desktop.kt` 的 `createPlatformPlayer` 固定调 `DesktopEngineManager.createPlayer()`，
而 `createUnderlyingPlayer` **只有** `DesktopHiResPlatformPlayer` 一个实现
（标准/独占只是 `isExclusive` 参数不同）。由此有两条硬结论：

1. **旧的 rodio 链路（`AudioPlayerImpl`）在桌面已是死代码**，只在测试/未覆写路径里存在。
   任何「rodio 可以做的事」都不能再拿来当兜底。
2. **原生库是硬依赖**：DLL 缺失时 `createUnderlyingPlayer` 返回
   `NoopDesktopPlatformPlayer`，桌面端**任何**音频都放不出来（state = Error）。

### DLL 版本握手（`apiLevel`）

新符号是 2026-11-10 才加进 `jni_api.rs` 的，**旧 DLL 里没有**。
宿主用 `DesktopHiResEngine.apiLevel` 做一次无副作用握手：

| apiLevel | 含义 | 宿主行为 |
|---|---|---|
| 1 | 旧库（`UnsatisfiedLinkError` → 1） | 关掉全部新能力；流仍走「整首落盘再播」；`supportsLeveling=false` |
| 2 | 新库（`nativeGetEngineApiLevel()` 返回 2） | 直连流 + 变速 + gapless + 真交叉淡化 + 动态处理 |

新增的 native 包装方法全部带 `try/catch`，所以**老 DLL 上是静默降级而不是崩**。
不要靠「调一下看抛不抛异常」逐个猜能力——那会把探测本身变成一次真实的播放/配置下发。

核对 DLL 里到底有哪些符号（不需要 dumpbin）：

```bash
grep -ao "Java_cp_player_[A-Za-z_]*" app/native/rust_lib_flick_player.dll | sort -u
```

---

## 2. 能力矩阵（apiLevel 2）

| 能力 | Rust 实现 | JNI 导出 | Kotlin 接线 | 结论 |
|---|---|---|---|---|
| 播本地文件 | `audio_play` | `nativePlay` | ✅ | 可用 |
| **HTTP(S) 直连 + Range seek** | `audio/http_source.rs` → `probe_http` → `audio_play_url` | `nativePlayUrl` | ✅ `ResolvedSource.Stream` | **边下边播，零等待** |
| 暂停 / 继续 / 停止 / 音量 | `audio_pause/resume/stop/set_volume` | ✅ | ✅ | 可用 |
| seek | `audio_seek(f64)` | `nativeSeek` | ✅ + `PendingSeekTracker` | 可用（见 §4.1） |
| PEQ | `audio_set_equalizer`（30 段、双缓冲无锁） | `nativeSetEqualizer` | ✅ `DesktopPeqMapper` | 可用，Q 由频点间距推导 |
| 声道平衡 / 立体声宽度 | `audio_set_fx` | `nativeSetFx` | ✅ | 可用（见 §4.2 混响陷阱） |
| **响度均衡 / 防爆音** | `audio_set_compressor` + `audio_set_limiter` | `nativeSetCompressor/Limiter` | ✅ `dynamicsPlan` | 可用，`supportsLeveling` 随 apiLevel 动态 |
| **播放速度** | `audio_set_playback_speed` | `nativeSetPlaybackSpeed/Get...` | ✅ 全链路 + 设置页 | 可用（0.25~4.0，设置页给 0.5~2.0） |
| **无缝队列** | `audio_queue_next(_url)` / `audio_skip_to_next` | `nativeQueueNext/QueueNextUrl/SkipToNext` | ✅ `queueNext()` 桥已通 | **API 可用，宿主自动切换未启用**（见 §5） |
| **真交叉淡化（双源重叠）** | `audio_set_crossfade(_curve)` | `nativeSetCrossfade/Curve` | ✅ `setNativeCrossfade`，与 FadeRunner 互斥 | 可用 |
| DSD 输出模式 | `audio_set_dsd_output_mode` | `nativeSetDsdOutputMode` | 桌面未接 UI | 已导出，暂无调用方 |
| 硬件独占 | 读 `CP_WASAPI_EXCLUSIVE` | `nativeSetExclusiveMode` | `setExclusiveMode` | 已导出；但仍以 env 为准（见 §4.3） |
| `get_state` / `get_progress` | ✅ | `nativeGetState/GetProgress` | 已导出，宿主仍用事件流 | 备用 |

---

## 3. HTTP 流媒体怎么接的

`audio_play(path)` 至今只吃本地路径（`PathBuf` + `probe_file`）。新增的是**平行的**一条：

```
DesktopHiResPlatformPlayer.load(url, headers)
  ├─ 缓存命中 → ResolvedSource.Local → nativePlay(本地路径)
  ├─ apiLevel 2 → ResolvedSource.Stream → nativePlayUrl(url, headers)
  │     └─ 失败 → 退回整首落盘 → nativePlay(本地路径)   ← 新旧 DLL 共用同一份宿主代码
  └─ apiLevel 1 → 整首落盘 → nativePlay(本地路径)
```

Rust 侧 `HttpMediaSource` 实现 Symphonia 的 `MediaSource`（`Read + Seek + Send + Sync`）：

1. `probe_remote` 用 `Range: bytes=0-0` 拿 `Content-Range: bytes 0-0/TOTAL`（或 `Content-Length`）；
   用 GET 一个字节而不是 HEAD —— 不少 CDN 的 HEAD 与 GET 行为不一致。
2. `read` 按 256 KiB 开窗口，耗尽时发 `Range: bytes=<pos>-<pos+WINDOW-1>`；
3. `seek` 作废窗口、按新位置重发 Range。服务端**忽略 Range 返回 200** 时，
   `fetch_chunk` 会从头丢弃前缀再读，慢但语义正确，不会退化成「整首不能拖」；
4. `probe_with_factory` 把「怎么打开一个源」抽成闭包，本地文件与 HTTP 共用
   整段 Symphonia 探测逻辑（含「无扩展名提示重试」）。

headers 从 Kotlin 以**扁平 `Array<String>`**（`[k1,v1,k2,v2,...]`）传入，
不解析 JSON —— 请求头值里带引号也不会被坑。

scheme 判定两边必须一致：Kotlin 用 `startsWith("http://", ignoreCase = true)`，
Rust 侧因此用 `http_source::is_http_url()` 做同样的小写归一。否则 `HTTPS://…`
会被宿主当成流送进 `nativePlayUrl`，Rust 却把它当本地路径丢给 `probe_file`。
`is_https_url()` 同时决定代理环境变量取 `https_proxy` 还是 `http_proxy`。

两个配套优化（在 `PlaybackMediaLoader`）：
- 引擎能定位流时，**拖动进度条不再切到本地副本**（旧引擎 FLAC×HTTP 时代的兜底，
  切一次就重新 load、一声咔哒）；
- 直连时**不再后台偷偷把整首歌下到磁盘**，省下无损单曲几十 MB 流量与磁盘。

---

## 4. 四个已经踩过的坑

### 4.1 seek：时长未知时不要夹到 `[0, duration]`

`duration` 在「曲目元信息没有时长」或「引擎还没发第一条 Progress」时是 0，
`coerceIn(0, 0)` 恒等于 0 ⇒ 一拖就回开头，还会让 `PendingSeekTracker` 报一次假失败。
正确写法：`duration > 0` 才夹。

### 4.2 `set_fx(enabled = true)` 会顺带打开一条 240 ms 混响

`SpatialFx` 的 `enabled` **同时**控制延迟线混响与立体声加宽，真正决定混响量的是
`mix`（默认 0.25）+ `feedback`（默认 0.35）+ 固定 240 ms 延迟。
只用平衡/宽度时必须把 `mix`、`feedback`、`damp` 显式写 0
（`DesktopPeqMapper.CLEAN_FX_*`），否则调左右平衡会听到混响尾巴。

### 4.3 独占模式的开关仍以**环境变量**为准

引擎 `desired_output_signature()` 读进程环境变量 `CP_WASAPI_EXCLUSIVE`（缺省 1 = 独占）。
`nativeSetExclusiveMode` 现在虽然导出了，但 `audio_set_exclusive_mode` 做的也只是
`set_var` + 切换 bit-perfect 标志 —— 输出流要**下次创建**才重建。
所以别把它当成「运行期立即切换」。

### 4.4 两套淡入淡出必须互斥

`PlaybackFadeController` 的 `FadeRunner` 是「曲末音量淡出 → 下一曲淡入」，中间无重叠；
引擎 `audio_set_crossfade` 是双源真交叉。`supportsNativeCrossfade = true` 时
FadeController 的 `startFadeIn` / `maybeStartFadeOut` 全部 no-op，
**配置整体转交引擎**，音量始终等于 `userVolume`。不互斥会「淡出两次」。

---

## 5. gapless 为什么只接了桥、没接自动切换

`queueNext` / `queueNextUrl` / `skipToNext` 在 Rust、JNI、Kotlin 三层都通了，
但 `PlaybackMediaLoader.triggerPreloadNext` **没有**改成自动 `platform.queueNext(...)`。

原因：引擎排队后会在当前曲播完**自己**切到下一首，而宿主的
`PlaybackControllerImpl.onTrackEnded()` 也会 `skipNext()` → `playCurrent()` → 重新 load。
两处都会推进队列 ⇒ double advance（跳两首 / 打断刚接上的源）。
要接自动 gapless，必须先给 `PlatformPlayer` 加一条「引擎已接管本次切歌」的事件，
并让 `onTrackEnded` 在该事件到达时只更新队列索引、不再 load。

这不是「忘了接」，是**在事件契约补齐之前接了会静默播错歌**。

---

## 6. 验证方式

| 改动在哪 | 怎么验证 |
|---|---|
| `DesktopPeqMapper`（PEQ / FX / 动态处理，纯函数） | `:core:desktopTest --tests "*DesktopPeqMapperTest"` |
| `HttpMediaSource` | `cargo test --lib http_source`（自带本地 HTTP/Range server，**server 与客户端在同一进程**，不会随父 shell 被杀） |
| Kotlin 适配层 / 控制器 / 设置页 | `:core:compileKotlinDesktop` + `:app:compileKotlinDesktop` |
| JNI 符号是否真的进了 DLL | 见 §1 的 `grep -ao` |
| 真机听感（Q 值、混响、交叉淡化、倍速） | 编译测不到，必须放音核对 |

### 2026-11-10 实测结果

| 命令 | 结果 |
|---|---|
| `cargo test --offline --lib http_source` | **3/3 通过**（本地 HTTP/Range server：跨窗口读、回退 seek、Content-Range 解析、scheme 大小写） |
| `cargo test --offline --lib jni_api::tests` | **4/4 通过**（扁平 header 成对、奇数长度报错、空数组、`apiLevel=2` 握手） |
| `cargo test --offline --lib`（全量 159 例） | **154 通过 / 5 失败**，5 个都是本次未改动的模块里的既有失败，见下方「已知既有失败」 |
| `:core:desktopTest --tests "*DesktopPeqMapperTest"` | **BUILD SUCCESSFUL，23/23 通过** |
| `:core:desktopTest`（全量 787 例） | **786 通过 / 1 失败**：`KmpSdkMigrationOperationsTest` 期望操作码 `[127..165]`、实际 `[127..166]`，并行会话新增 SDK 操作后测试没跟着改，与播放链路无关 |
| `:core:compileKotlinDesktop` + `:app:compileKotlinDesktop` | **BUILD SUCCESSFUL**（并行会话改坏生成文件时会短暂失败，见下方） |
| `cargo build --release --offline --lib` | 通过，DLL 已同步到 `app/native/` 与 `native/`（三处 md5 一致） |
| DLL 符号核对 | `DesktopHiResEngine_native*` 共 **27 个**，含 `nativePlayUrl` / `SetPlaybackSpeed` / `SetCompressor` / `SetCrossfade` |

> ⚠️ **源码不在 git 里**：`.gitignore:91` 把 `native/flick-player-engine/` 整个排除了（私有引擎）。
> 所以 Rust 侧的改动**只随本地磁盘存在**，提交里能看到的只有 `app/native/*.dll` 与 Kotlin 侧。
> 换机器 / CI 上要复现能力，必须把这份源码目录单独同步过去。

#### 已知既有失败（与本次改动无关）

- `library_scan::tests::*` 4 例：`src/library_scan/{two_phase,hybrid,watcher}.rs` 用
  `Path::canonicalize()`，Windows 会返回 `\\?\C:\...` 的 verbatim 前缀，而断言期待
  `C:\...`。纯 Windows 路径规范化问题，这些文件停留在 8 月版本、本次一行未动。
- `audio::device::tests::dap_without_audio_caps_native_dsd_gets_false_without_runtime_probe`：
  `classify_device` 的 `supports_native_dsd = is_dap || caps || runtime`（`device.rs:228`），
  对 DAP 恒为 true；测试却断言 false。是既有的代码/测试契约不一致，同样未改动。

这两类都不涉及 `http_source` / `jni_api` / `audio_api` / `decoder`，别把它们当成本次回归。
真要修：前者把 scanner 的 canonicalize 换成去 `\\?\` 前缀的规范化，后者要么改测试、
要么把 `is_dap` 从 native-DSD 判定里摘掉 —— 都会改变线上行为，需单独评估。

#### 并行会话的瞬态失败

仓库里有别的会话在重做 `core/music` 与 `provider-sdk-kmp` 的生成文件。
曾出现过 `MusicBackend.kt: Unresolved reference 'sdkAccountNamespace'`、
`ExtendedMusicSource.generated.kt` 类型不匹配等编译错误，以及
`KmpSdkMigrationOperationsTest` 的操作码区间断言过期 —— 都与播放链路无关，
等对方写完 / 补上测试再跑一次即可。判断方法：看报错文件是否在
`core/src/commonMain/kotlin/cp/player/core/playback/` 之外。

### 本机构建 Rust 的前置条件

VS 装在 **`E:\project\build`**（不在 `C:\Program Files`），Windows SDK 是 `10.0.26100.0`。
直接 `cargo build` 会因为 PATH 里 `/usr/bin/link.exe`（Git 自带的 coreutils 版本）而失败。
需要先把 MSVC 的 `cl.exe` / `link.exe` 放进 PATH，并设好 `LIB` / `INCLUDE`。
仓库根已有 `rust-msvc-env.sh`，`source rust-msvc-env.sh` 即可；下面是它展开后的内容。
PATH 必须是 MSYS 风格（`/e/...`）bash 才会当成可搜索目录，而 `LIB` / `INCLUDE`
是给 Windows 程序读的，必须是 `E:\...` 风格：

```bash
VCVER=14.51.36231
SDKVER=10.0.26100.0
export PATH="/e/project/build/VC/Tools/MSVC/$VCVER/bin/Hostx64/x64:/c/Program Files (x86)/Windows Kits/10/bin/$SDKVER/x64:$PATH"
export LIB="E:\\project\\build\\VC\\Tools\\MSVC\\$VCVER\\lib\\x64;C:\\Program Files (x86)\\Windows Kits\\10\\Lib\\$SDKVER\\um\\x64;C:\\Program Files (x86)\\Windows Kits\\10\\Lib\\$SDKVER\\ucrt\\x64"
WSDK='C:\Program Files (x86)\Windows Kits\10'
export INCLUDE="E:\\project\\build\\VC\\Tools\\MSVC\\$VCVER\\include;$WSDK\\Include\\$SDKVER\\ucrt;$WSDK\\Include\\$SDKVER\\shared;$WSDK\\Include\\$SDKVER\\um;$WSDK\\Include\\$SDKVER\\winrt;$WSDK\\Include\\$SDKVER\\cppwinrt"
cd native/flick-player-engine && cargo build --release --offline --lib
```

生成物 `target/release/rust_lib_flick_player.dll` 需要同步到
`app/native/`（打包用）与仓库根 `native/`（开发态查找路径用）。

### 依赖边界

`ureq`（`tls` feature）只挂在 `[target.'cfg(not(target_os = "android"))'.dependencies]`：
Android 的流走 ExoPlayer，不需要把 ureq/ring 带进移动端产物。
`audio/http_source.rs` 与 `audio_api.rs` 的 URL 分支同样用 `cfg(not(target_os = "android"))` 门控。

### 混淆

`app/desktop-proguard-rules.pro` 必须保留 `cp.player.core.playback.DesktopHiResEngine`
的类名与 native 方法名 —— Rust 侧符号名是写死的，
一混淆就 `UnsatisfiedLinkError`，而且**只在装出来的包里复现**（`desktopRun` 不做混淆）。
