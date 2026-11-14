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
> 2026-11-10 更新：补齐 JNI 导出、重建 DLL、接入 HTTP 直连与通用能力；
> 同日修复进度轮询缺失（无总时长 / 拖动无效的根因，见 §4.5）、
> DLL 搜索顺序遮蔽（UnsatisfiedLinkError，见 §4.7）、
> `Ended` 状态被吞导致播完不切歌（见 §4.6）。

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
| PEQ | `audio_set_equalizer`（30 段、双缓冲无锁） | `nativeSetEqualizer` | ✅ `FlickPeqMapper` | 可用，Q 由频点间距推导 |
| 声道平衡 / 立体声宽度 | `audio_set_fx` | `nativeSetFx` | ✅ | 可用（见 §4.2 混响陷阱） |
| **响度均衡 / 防爆音** | `audio_set_compressor` + `audio_set_limiter` | `nativeSetCompressor/Limiter` | ✅ `dynamicsPlan` | 可用，`supportsLeveling` 随 apiLevel 动态 |
| **播放速度** | `audio_set_playback_speed` | `nativeSetPlaybackSpeed/Get...` | ✅ 全链路 + 设置页 | 可用（0.25~4.0，设置页给 0.5~2.0） |
| **无缝队列** | `audio_queue_next(_url)` / `audio_skip_to_next` | `nativeQueueNext/QueueNextUrl/SkipToNext` | ✅ `queueNext()` 桥已通 | **API 可用，宿主自动切换未启用**（见 §5） |
| **真交叉淡化（双源重叠）** | `audio_set_crossfade(_curve)` | `nativeSetCrossfade/Curve` | ✅ `setNativeCrossfade`，与 FadeRunner 互斥 | 可用 |
| DSD 输出模式 | `audio_set_dsd_output_mode` | `nativeSetDsdOutputMode` | 桌面未接 UI | 已导出，暂无调用方 |
| 硬件独占 | 读 `CP_WASAPI_EXCLUSIVE` | `nativeSetExclusiveMode` | `setExclusiveMode` | 已导出；但仍以 env 为准（见 §4.3） |
| `get_state` / `get_progress` | ✅ | `nativeGetState/GetProgress` | `DesktopHiResEngine.getProgress()` + 100 ms 进度轮询 | **进度的唯一来源**（引擎不推送 Progress 事件，见 §4.5） |

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
（`FlickPeqMapper.CLEAN_FX_*`），否则调左右平衡会听到混响尾巴。

### 4.3 独占模式：设置 → 环境变量 → cpal，缺省一律共享

独占的**真正决策点**在 vendored cpal
（`vendor/cpal/src/host/wasapi/device.rs::build_output_stream_raw_inner`）：
它读 `CP_WASAPI_EXCLUSIVE` 决定 `AUDCLNT_SHAREMODE_EXCLUSIVE / SHARED`，
并打那行 `SUCCESS: Initialized in EXCLUSIVE (Bit-Perfect) hardware mode!`。
链路是「设置页 → Kotlin → 进程环境变量 → cpal」，任何一环断掉都会静默回缺省：

| 层 | 位置 | 缺省（env 缺失时） |
|---|---|---|
| 设置 | `PlaybackSessionSettings.DEFAULT_WASAPI_EXCLUSIVE` | `false` = 共享 |
| 引擎管理 | `DesktopEngineManager.resolveDesiredEngine` | 共享（VIP+开关才独占） |
| 签名 | `engine.rs::desired_output_signature` | `unwrap_or(false)` = shared |
| **实际开流** | **cpal `device.rs`** | `unwrap_or(false)` = shared（2026-11-10 前曾是 `true`） |

2026-11-10 事故：设置明明是「共享」，冷启动照样独占。两个叠加缺陷：

1. **`DesktopHiResEngine.setProcessEnv` 的 FFM 写入整条是坏的**：
   `setEnvHandle.invokeExact(k, v)` 被 Kotlin 编译成调用点描述符
   `(MemorySegment, MemorySegment)Object`，而 downcall handle 类型是
   `(MemorySegment, MemorySegment)int` —— `invokeExact` 要求**完全一致**，
   返回类型差一个就抛 `WrongMethodTypeException`，被 `catch (t: Throwable)`
   静默吞掉（只打 FINE）⇒ `CP_WASAPI_EXCLUSIVE` **从未写成**。
   另外 `Arena.allocateFrom(String, Charset)` 是 JDK 22+ API，生产运行时
   JBR 21 上先抛 `NoSuchMethodError`。已改：`invokeWithArguments`（asType 适配）+
   `allocWideCString` 手工铺 UTF-16LE + NUL。
2. **`nativeSetExclusiveMode` 曾被 `if (isInitialized)` 门控**：冷启动时
   `setExclusiveMode` 先于 `initEngine()` 执行，可靠通道（Rust
   `std::env::set_var`，`audio_set_exclusive_mode` 不要求引擎已创建）被跳过。
   已改：无条件 `runCatching { nativeSetExclusiveMode(enabled) }`。

两条教训：

- **`invokeExact` 的返回类型必须也精确匹配** —— Kotlin 不做 signature-polymorphic
  返回类型推断，丢弃返回值就编译成 `Object` 描述符 ⇒ 运行期必炸且被 catch 吞。
  Kotlin 里调 `MethodHandle` 一律用 `invokeWithArguments`。
- **「默认值」要找真正做决定的那一层**。`engine.rs::desired_output_signature`
  只是个缓存键字符串，真正选 sharing mode 的是 vendored cpal；
  当天只改了 engine.rs 的 `unwrap_or`、漏了 cpal，签名说 shared、声卡照旧独占。
  验证共享/独占的真实判据是 cpal 的日志行
  （`Initialized in SHARED mode.` / `SUCCESS: Initialized in EXCLUSIVE...`），
  不是设置页、不是 `isExclusiveMode` 标志、不是 `DesktopPlayerScreen` 徽标。

`nativeSetExclusiveMode` / `audio_set_exclusive_mode` 做的是
`set_var` + 切 bit-perfect 标志 —— 输出流要**下次创建**才重建，
别当成「运行期立即切换」。

### 4.4 两套淡入淡出必须互斥

`PlaybackFadeController` 的 `FadeRunner` 是「曲末音量淡出 → 下一曲淡入」，中间无重叠；
引擎 `audio_set_crossfade` 是双源真交叉。`supportsNativeCrossfade = true` 时
FadeController 的 `startFadeIn` / `maybeStartFadeOut` 全部 no-op，
**配置整体转交引擎**，音量始终等于 `userVolume`。不互斥会「淡出两次」。

### 4.5 Progress 事件从不存在 —— 进度必须由宿主轮询

**引擎永远不推送 Progress**：`AudioEvent::Progress` 这个变体在 Rust 全源码里
**从未被构造**（`try_send(AudioEvent::…)` 的发送点只有 StateChanged / TrackEnded /
FormatChanged / Error / CrossfadeStarted / NextTrackReady）。`nativePollEvent` 的
事件流里不可能出现 Progress，`parseAndEmitEvent` 里的 `"Progress"` 分支是死路。

进度的唯一来源是 `audio_get_progress`（`nativeGetProgress`）**拉取**：
宿主按节拍轮询，桥接层把结果合成为 `DesktopAudioEvent.Progress` 喂进 `audioEvents`。
这与 `reference/cp-player-legacy` 的 `FlickPlayer.startPositionPolling`（50 ms 一次）
是同一机制 —— KMP 移植时把这层轮询漏掉了。

漏掉时的表现（2026-11-10 真实事故）：
1. **无总时长** —— `handleProgress` 从未跑过，`durationMs` 只剩曲目元信息（本地文件通常没有）；
2. **进度条不动** —— `positionMs` 冻在起点；
3. **拖动进度条无效** —— 时长为 0 时 `CpSeekBar` 把拖动目标 `coerceIn(0, 0)` 成 0，
   seek 永远落在开头（引擎侧 seek 本身是好的，`handle_seek` 会重建解码器定位）。

判据（下次怀疑进度链路时先跑这两条）：
- `grep -rn "try_send(AudioEvent::Progress" native/flick-player-engine/src` ⇒ 0 命中是**预期**，别当 bug 修；
- `DesktopHiResEngine.getProgress()` / `startProgressPolling()` 必须存在，
  由 `DesktopProgressPollTest` 的源码接线断言钉住。

**如果哪天引擎真的开始推送 Progress 事件**：两条路径会合并（同值重复无害），
届时可以撤掉轮询，但先删测试里的源码断言，别静默改契约。

### 4.6 播完不切歌：`Ended` 状态被 `StateChanged(Stopped)` 吞掉

引擎播完（无 gapless 队列）时 `command_processing_loop`（engine.rs finished_rx
处理）**先发 `TrackEnded` 紧跟 `StateChanged(Stopped)`** —— 意图是「没曲可接了，
标 Stopped 让宿主兜底」。

宿主侧 `handleTrackEnded()` 写 `Ended` → `handleStateChanged("stopped")` 立刻
覆写成 `Idle`。StateFlow 合并快速连续赋值，`PlaybackControllerImpl.observePlatform`
只看到 `Idle` ⇒ `onTrackEnded()` **永远不触发** ⇒ 播完不自动切下一首。

这与 `PlatformPlayer.android.kt:308-312` 的 ExoPlayer `onIsPlayingChanged` 吞 `Ended`
是**同构事故**（那次是 `Paused` 吞，这次是 `Idle` 吞）。

修法一致：`handleStateChanged` 里 **`Ended` 只能被 `Playing`/`Buffering`（新曲开始）
覆写**，`Idle`/`Paused` 一律不许吞（`stop()`/`release()` 是显式赋值，不走这条路径）。
`DesktopHiResPlatformPlayer` 与 `FlickPlatformPlayer`（Android）两处都加了保护，
由 `DesktopProgressPollTest` 的源码断言钉住。

### 4.7 DLL 搜索顺序：`~/.cpplayer/native/` 曾遮蔽新 DLL

`tryLoadLibrary()` 的搜索候选里 `~/.cpplayer/native/` 曾排在 `app/native/` **前面**。
旧版本安装/运行时会往 `~/.cpplayer/native/` 写 DLL，升级后旧 DLL 残留 ⇒
抢先加载 ⇒ 旧 DLL 缺 `nativeGetProgress` 等新导出 ⇒ `UnsatisfiedLinkError`。

修法：持久化目录挪到搜索候选**最后**，工作区/安装目录的 DLL 优先。
`getProgress()` 同时对 `UnsatisfiedLinkError` 优雅降级（停轮询、只打一条日志）。

### 4.8 HTTP 源 seek 走 `probe_file` 必败 —— 必须走 `probe_http`

`handle_seek` 的旧实现调 `spawn_decoder(path)` → `probe_file(path)` → `File::open(path)`。
对 HTTP 流，`SourceInfo.path` 是 **URL 字符串** ⇒ `File::open(url)` 必败 ⇒
seek 后引擎进 `Idle` + 无源 ⇒ 暂停/播放空转，`PendingSeekTracker` 宽限期后弹回原点。

**为什么「有时能 seek」**：缓存命中（`DesktopStreamLocalizer.cachedPath`）时走
`ResolvedSource.Local`（本地文件），`probe_file` 能打开。崩溃/重启后缓存丢失 ⇒
退回 `ResolvedSource.Stream`（直连 HTTP）⇒ seek 坏掉，**唯独这首歌不能 seek**。

修法：`SourceInfo` 新增 `http_meta: Option<RemoteMeta>`（`probe_http` 填入），
`handle_seek` 检测到 `http_meta` 就走 `probe_http` 重建解码器（带 headers）。
本地文件仍走 `probe_file`，两条路各自正确。

---

## 5. gapless / crossfade 的自动切歌契约

`queueNext` / `queueNextUrl` 已贯通 Rust、JNI、桌面与 Android Kotlin。
`PlaybackMediaLoader.triggerPreloadNext` 在 `platform.supportsGaplessQueue` 时把下一首的
**mediaId、URL（或缓存路径）和鉴权头**一起交给引擎；这是预取，不提前改 UI 队列。

引擎开始交叉淡化时发 `CrossfadeStarted(toPath)`，未开交叉淡化时发 `NextTrackReady(path)`。
平台按预取登记的路径找回 mediaId，通过 `nativeTrackTransitions`（StateFlow）报告
「引擎已经换源」。控制器只提交队列索引、刷新元数据 / 歌词 / 听歌统计并预取再下一首，
**绝不再次 `load()`**。旧音源随后发出的 `TrackEnded` 由平台按「它属于已预取的旧源」
直接消费，不会 double advance；预取曲最终自然结束时仍正常上报 `Ended`。
事件在 `CrossfadeStarted` 时更新 UI 是有意的：此时旧曲只剩淡出尾巴，
进度与歌词已经属于新曲。

这里刻意用 **StateFlow 而不是无 replay 的 SharedFlow**：切换与控制器订阅同帧发生时，
后者会丢事件，表现为元数据停在前一首。路径只用于原生事件与预取登记之间的匹配，
不作为长期身份；真正的队列身份是 mediaId。收到手动切歌、队列重排或单曲循环等事件时，
导航世代守卫会丢弃旧的原生切换通知。

自动预取受「预加载下一首」开关控制。关闭预取或原生排队失败时维持原有的
`Ended → playCurrent()` 兜底；仍可以正常自动续播，只是没有双源交叉淡化。

### ⚠️ queue-next 必须复用当前输出流

`audio_queue_next_url()` **不能**走 `prepare_stream()`。后者会按下一首的采样率
调用 `ensure_audio_engine()`；排队发生在**当前曲正在出声**的时候，一旦设备默认
采样率（如 96 kHz 共享模式）与下一首（44.1 kHz）不同，`EngineManager` 会判定
输出签名变化，**关掉正在播放的引擎再建一个空引擎**。

症状：播放约 1~2 秒后突然静音；UI 状态仍是 `playing`；`nativeGetProgress()`
返回 `null`，进度条冻结。

正确做法：queue-next 只做 `probe_http()`，当前引擎已初始化时**沿用它的
采样率与声道数**，把下一首解码 / 重采样到当前输出格式；只有当前没有已初始化
引擎时，才允许按下一首的采样率初始化输出。

回归测试：`DesktopQueueNextUrlRegressionTest`（真实本地 HTTP 流 + 真实引擎，
播放 1.2 秒后排队，断言 `getProgress()` 仍可用且进度继续前进）。

---

## 6. 验证方式

| 改动在哪 | 怎么验证 |
|---|---|
| `FlickPeqMapper`（PEQ / FX / 动态处理，纯函数） | `:core:desktopTest --tests "*FlickPeqMapperTest"` |
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
| `:core:desktopTest --tests "*FlickPeqMapperTest"` | **BUILD SUCCESSFUL，23/23 通过** |
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

### Android 流媒体与 USB 独占（2026-11-10）

- `ureq`（`tls` feature）是全平台依赖；FlickEngine 的 Android 与桌面共用
  `HttpMediaSource`、鉴权头透传和 HTTP seek 重建解码器逻辑。Media3 仍走 ExoPlayer 自己的网络栈。
- Android 直连失败时回退本地缓存下载；已有缓存仍优先本地播放。时长未知时 seek 只夹下界，不再被钉回 0。
- `RustEngine` 新增 `nativePlayUrl`、倍速和原生交叉淡化 JNI 桥；动态引擎切换时重放这些配置。
  本地 / HTTP 预取队列也已接通，自动换源契约见 §5。
- `AndroidAudioLifecycleManager` 扫描并筛选 USB Audio Class 设备，申请运行时权限、
  claim/release 音频接口以解除内核驱动占用，并把 fd 交给 Rust UAC2。拔出或释放时先清 native
  会话并等待停止，再关闭 Java 连接，避免 native 崩溃与 fd 泄漏。
- Manifest 增加 USB 附加入口与 Audio Class 过滤。实际采样率协商、USB 权限弹窗和拔插仍需真机验证。
- `native/flick-player-engine/build_android.sh` 通过 `cargo ndk -o` 把 ARM64 `.so` 输出到
  `app-android/src/main/jniLibs/arm64-v8a/`。不要只跑 `cargo ndk build`：那不会自动进入 APK。
  已生成 8,754,552 字节的 release `.so`，并核实 `mergeDebugNativeLibs` 的打包输入包含同一文件；
  导出符号包含 `RustEngine_nativePlayUrl` / `nativeQueueNext` / `nativeSetCrossfade`。
  `assembleDebug` 仍被既有 `jvmMain` `MethodHandle.invoke/invokeExact` 的 minSdk 24 D8 错误阻断
  （要求 API 26；`VirtualThreadDispatcher` / `DesktopScopedContext` / `DesktopScopedValue`），
  与 FlickEngine 改动无关，不能顺手提高全应用 minSdk 来掩盖。

| 定向回归 | 结果 |
|---|---|
| `PlaybackControllerNavigationTest` | 33/33，含原生换源不重复 load、旧源结束去重、过期事件不连跳 |
| `PlaybackControllerPreloadTest` | 4/4，含下一首 URL 与鉴权头进入原生队列 |
| `FlickPeqMapperTest` | 23/23 |
| `DesktopProgressPollTest` | 15/15 |
| `cargo ndk -t arm64-v8a check` | 通过 |
| `cargo ndk -t arm64-v8a build --release` | 通过，已输出到实际 `jniLibs` |

### 依赖边界

`audio/http_source.rs` 与 `audio_api.rs` 的 URL 分支不再做 Android 排除。

### 混淆

`app/desktop-proguard-rules.pro` 必须保留 `cp.player.core.playback.DesktopHiResEngine`
的类名与 native 方法名 —— Rust 侧符号名是写死的，
一混淆就 `UnsatisfiedLinkError`，而且**只在装出来的包里复现**（`desktopRun` 不做混淆）。
