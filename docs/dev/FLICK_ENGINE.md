# FlickEngine 接入对照

> 桌面播放链路的契约、能力矩阵与已验证的坑。解决「引擎实现了但宿主没接 / 接了但参数错 / UI 声称支持却毫无反应」。
> 事实源：`native/flick-player-engine/src/**` → `jni_api.rs` → `DesktopHiResEngine` → `DesktopHiResPlatformPlayer` / `PlaybackController` / 设置页。

## 1. 桌面只有一条播放链路

`PlatformPlayer.desktop.kt` 的 `createPlatformPlayer` 固定调 `DesktopEngineManager.createPlayer()`，`createUnderlyingPlayer` 只有 `DesktopHiResPlatformPlayer` 一个实现（标准 / 独占只是 `isExclusive` 参数不同）。

| 结论 | 内容 |
|---|---|
| rodio 链路 | 旧 `AudioPlayerImpl` 已移除；不能再拿「rodio 能做」当兜底 |
| 原生库 | 硬依赖：DLL 缺失时返回 `NoopDesktopPlatformPlayer`，桌面任何音频都放不出来（state = Error） |

### 1.1 DLL 版本握手

新符号是 2026-11-10 才进 `jni_api.rs` 的，旧 DLL 里没有。宿主用 `DesktopHiResEngine.apiLevel` 做一次无副作用握手。

| apiLevel | 来源 | 宿主行为 |
|---|---|---|
| 1 | 旧库（`UnsatisfiedLinkError` → 1） | 关掉全部新能力；流仍走「整首落盘再播」；`supportsLeveling = false` |
| 2 | `nativeGetEngineApiLevel()` 返回 2 | 直连流 + 变速 + gapless + 真交叉淡化 + 动态处理 |

新增的 native 包装方法全部带 `try/catch`，老 DLL 上静默降级。不要靠「调一下看抛不抛异常」逐个猜能力：那会把探测变成一次真实的播放 / 配置下发。

核对 DLL 里的符号：

```bash
grep -ao "Java_cp_player_[A-Za-z_]*" app/native/rust_lib_flick_player.dll | sort -u
```

## 2. 能力矩阵（apiLevel 2）

| 能力 | Rust | JNI | Kotlin | 结论 |
|---|---|---|---|---|
| 播本地文件 | `audio_play` | `nativePlay` | 已接 | 可用 |
| HTTP(S) 直连 + Range seek | `audio/http_source.rs` → `probe_http` → `audio_play_url` | `nativePlayUrl` | `ResolvedSource.Stream` | 边下边播，零等待 |
| 暂停 / 继续 / 停止 / 音量 | `audio_pause/resume/stop/set_volume` | 有 | 已接 | 可用 |
| seek | `audio_seek(f64)` | `nativeSeek` | + `PendingSeekTracker` | 可用，见 4.1 |
| PEQ | `audio_set_equalizer`（30 段、双缓冲无锁） | `nativeSetEqualizer` | `FlickPeqMapper` | 可用；Q 由频点间距推导 |
| 声道平衡 / 立体声宽度 | `audio_set_fx` | `nativeSetFx` | 已接 | 可用，见 4.2 |
| 响度均衡 / 防爆音 | `audio_set_compressor` + `audio_set_limiter` | `nativeSetCompressor/Limiter` | `dynamicsPlan` | 可用；`supportsLeveling` 随 apiLevel |
| 播放速度 | `audio_set_playback_speed` | `nativeSetPlaybackSpeed/Get...` | 全链路 + 设置页 | 可用；引擎 0.25~4.0，设置页给 0.5~2.0 |
| 无缝队列 | `audio_queue_next(_url)` / `audio_skip_to_next` | `nativeQueueNext/QueueNextUrl/SkipToNext` | `queueNext()` 已通 | API 可用，宿主自动切换见第 5 节 |
| 真交叉淡化（双源重叠） | `audio_set_crossfade(_curve)` | `nativeSetCrossfade/Curve` | `setNativeCrossfade` | 可用；与 `FadeRunner` 互斥 |
| DSD 输出模式 | `audio_set_dsd_output_mode` | `nativeSetDsdOutputMode` | 引擎设置页「DSD 输出模式」 | 可用；`initEngine` 成功后按快照补发 |
| 硬件独占 | 读 `CP_WASAPI_EXCLUSIVE` | `nativeSetExclusiveMode` | `setExclusiveMode` | 已导出，但仍以 env 为准，见 4.3 |
| `get_state` / `get_progress` | 有 | `nativeGetState/GetProgress` | `getProgress()` + 100 ms 轮询 | 进度的唯一来源，见 4.5 |

### 2.1 逐项开关（2026-11-26）

能力位不再只由 `apiLevel` 决定，还要过一层用户开关：`FlickEngineSettings`（磁盘契约 +
默认值）→ `FlickEngineToggles`（进程内快照）→ `DesktopHiResPlatformPlayer` 的 getter。

| 开关 | 键 | 默认 | 关掉之后 |
|---|---|---|---|
| 直连网络音频流 | `flick_engine_direct_stream` | 开 | `supportsSeekableRemoteStreams=false` ⇒ 整首落盘再播 |
| 无缝队列 | `flick_engine_gapless_queue` | 开 | 不调 `queueNext`，播完 `Ended → playCurrent()` |
| 原生交叉淡化 | `flick_engine_native_crossfade` | 开 | 交回 `PlaybackFadeController` 的音量斜坡 |
| 动态响度 | `flick_engine_dynamics` | 开 | `supportsLeveling=false`，音效页隐藏响度均衡，链上压缩器/限幅器被摘掉 |
| DSD 输出模式 | `flick_engine_dsd_mode` | 3 (Auto) | 0/1/2 分别落到 PCM 抽取 / DoP / Native |

| 约定 | 原因 |
|---|---|
| 原生交叉淡化**依赖**无缝队列 | 双源重叠要下一首先进引擎；只关 gapless 而仍声称支持原生淡化，会让两套淡化同时失效 |
| 快照默认 = 磁盘默认 | 快照没加载时等价于全开，绝不因「读不到盘」静默降级音质 |
| `DesktopEngineManager.createPlayer` 先 `applyFrom(storage)` | 冷启动直接播放时不经过设置页，能力位必须已经对齐 |
| DSD 的下发走 `registerDsdApplier` | commonMain 不能反向依赖桌面实现；引擎未 init 时只记值，`initEngine` 后补发 |

## 3. HTTP 流怎么接

`audio_play(path)` 只吃本地路径（`PathBuf` + `probe_file`）。新增的是平行的一条。

```text
DesktopHiResPlatformPlayer.load(url, headers)
  ├─ 缓存命中        → ResolvedSource.Local  → nativePlay(本地路径)
  ├─ apiLevel 2     → ResolvedSource.Stream → nativePlayUrl(url, headers)
  │                     └─ 失败 → 整首落盘 → nativePlay(本地路径)   ← 新旧 DLL 共用宿主代码
  └─ apiLevel 1     → 整首落盘 → nativePlay(本地路径)
```

Rust 侧 `HttpMediaSource` 实现 Symphonia 的 `MediaSource`（`Read + Seek + Send + Sync`）。

| 步骤 | 做法 |
|---|---|
| 探测总长 | `probe_remote` 用 `Range: bytes=0-0` 拿 `Content-Range: bytes 0-0/TOTAL`（或 `Content-Length`）；用 GET 一个字节而不是 HEAD，不少 CDN 的 HEAD 与 GET 不一致 |
| 读 | 按 256 KiB 开窗口，耗尽时发 `Range: bytes=<pos>-<pos+WINDOW-1>` |
| seek | 作废窗口，按新位置重发 Range |
| 服务端忽略 Range 返回 200 | `fetch_chunk` 从头丢弃前缀再读；慢但语义正确，不退化成「整首不能拖」 |
| `probe_with_factory` | 把「怎么打开一个源」抽成闭包，本地文件与 HTTP 共用整段探测逻辑（含「无扩展名提示重试」） |

| 约定 | 内容 |
|---|---|
| headers 传递 | Kotlin 以扁平 `Array<String>`（`[k1,v1,k2,v2,...]`）传入，不解析 JSON；请求头值带引号也不会被坑 |
| scheme 判定 | 两侧必须一致：Kotlin 用 `startsWith("http://", ignoreCase = true)`，Rust 用 `http_source::is_http_url()` 做同样的小写归一 |
| 不一致的后果 | `HTTPS://…` 会被宿主当流送进 `nativePlayUrl`，Rust 却当本地路径交给 `probe_file` |
| 代理变量 | `is_https_url()` 决定取 `https_proxy` 还是 `http_proxy` |

配套优化（`PlaybackMediaLoader`）：

| 优化 | 内容 |
|---|---|
| 拖动 | 引擎能定位流时不再切到本地副本（旧 FLAC×HTTP 时代的兜底，切一次就重新 load、一声咔哒） |
| 直连 | 不再后台偷偷把整首下到磁盘，省下无损单曲几十 MB 流量与磁盘 |

## 4. 已踩过的坑

### 4.1 seek：时长未知时不要夹到 `[0, duration]`

| 项 | 内容 |
|---|---|
| 触发 | `duration` 在曲目元信息没时长、或引擎还没发第一条 Progress 时是 0 |
| 后果 | `coerceIn(0, 0)` 恒等于 0，一拖就回开头，`PendingSeekTracker` 还会报一次假失败 |
| 做法 | `duration > 0` 才夹 |

### 4.2 `set_fx(enabled = true)` 顺带打开 240 ms 混响

`SpatialFx` 的 `enabled` 同时控制延迟线混响与立体声加宽；混响量由 `mix`（默认 0.25）+ `feedback`（默认 0.35）+ 固定 240 ms 延迟决定。只用平衡 / 宽度时必须把 `mix`、`feedback`、`damp` 显式写 0（`FlickPeqMapper.CLEAN_FX_*`），否则调左右平衡会听到混响尾巴。

### 4.3 独占模式：设置 → 环境变量 → cpal

真正决策点在 vendored cpal（`vendor/cpal/src/host/wasapi/device.rs::build_output_stream_raw_inner`）：它读 `CP_WASAPI_EXCLUSIVE` 决定 `AUDCLNT_SHAREMODE_EXCLUSIVE / SHARED`，并打 `SUCCESS: Initialized in EXCLUSIVE (Bit-Perfect) hardware mode!`。

| 层 | 位置 | env 缺失时 |
|---|---|---|
| 设置 | `PlaybackSessionSettings.DEFAULT_WASAPI_EXCLUSIVE` | `false` = 共享 |
| 引擎管理 | `DesktopEngineManager.resolveDesiredEngine` | 共享（VIP + 开关才独占） |
| 签名 | `engine.rs::desired_output_signature` | `unwrap_or(false)` = shared |
| 实际开流 | cpal `device.rs` | `unwrap_or(false)` = shared（2026-11-10 前曾是 `true`） |

2026-11-10 事故：设置是「共享」，冷启动照样独占。两个叠加缺陷。

| 缺陷 | 内容 |
|---|---|
| FFM 写入整条坏 | `setEnvHandle.invokeExact(k, v)` 被 Kotlin 编译成 `(MemorySegment, MemorySegment)Object`，而 handle 是 `(…)int`；`invokeExact` 要求完全一致，返回类型差一个就抛 `WrongMethodTypeException`，被 `catch (t: Throwable)` 静默吞掉（只打 FINE）⇒ 变量从未写成 |
| API 版本 | `Arena.allocateFrom(String, Charset)` 是 JDK 22+，生产运行时 JBR 21 先抛 `NoSuchMethodError` |
| 门控 | `nativeSetExclusiveMode` 曾被 `if (isInitialized)` 门控；冷启动时 `setExclusiveMode` 先于 `initEngine()` 执行，可靠通道（Rust `std::env::set_var`，不要求引擎已创建）被跳过 |

已改：`invokeWithArguments`（`asType` 适配）+ `allocWideCString` 手工铺 UTF-16LE + NUL；`nativeSetExclusiveMode` 无条件 `runCatching`。

| 教训 | 内容 |
|---|---|
| `invokeExact` 返回类型也要精确匹配 | Kotlin 不做 signature-polymorphic 返回类型推断，丢弃返回值就编译成 `Object` 描述符 ⇒ 运行期必炸且被 catch 吞。Kotlin 调 `MethodHandle` 一律用 `invokeWithArguments` |
| 「默认值」要找真正做决定的那层 | `desired_output_signature` 只是缓存键字符串；当天只改了 engine.rs 的 `unwrap_or`、漏了 cpal，签名说 shared、声卡照旧独占 |
| 真实判据 | cpal 的日志行（`Initialized in SHARED mode.` / `SUCCESS: Initialized in EXCLUSIVE...`），不是设置页、不是 `isExclusiveMode`、不是 `DesktopPlayerScreen` 徽标 |

`nativeSetExclusiveMode` / `audio_set_exclusive_mode` 做的是 `set_var` + 切 bit-perfect 标志；输出流要下次创建才重建，不是运行期立即切换。

### 4.4 两套淡入淡出必须互斥

| 实现 | 语义 |
|---|---|
| `PlaybackFadeController` 的 `FadeRunner` | 曲末淡出 → 下一曲淡入，中间无重叠 |
| 引擎 `audio_set_crossfade` | 双源真交叉 |

`supportsNativeCrossfade = true` 时 FadeController 的 `startFadeIn` / `maybeStartFadeOut` 全部 no-op，配置整体转交引擎，音量始终等于 `userVolume`。不互斥会「淡出两次」。

### 4.5 Progress 事件从不存在

引擎永远不推送 Progress：`AudioEvent::Progress` 在 Rust 全源码里从未被构造（`try_send` 的发送点只有 StateChanged / TrackEnded / FormatChanged / Error / CrossfadeStarted / NextTrackReady）。`nativePollEvent` 的事件流里不可能出现 Progress，`parseAndEmitEvent` 的 `"Progress"` 分支是死路。

进度唯一来源是 `audio_get_progress`（`nativeGetProgress`）拉取：宿主按节拍轮询，桥接层把结果合成为 `DesktopAudioEvent.Progress` 喂进 `audioEvents`。这与 legacy 的 `FlickPlayer.startPositionPolling`（50 ms 一次）是同一机制，KMP 移植时漏掉了这层轮询。

| 漏掉的症状 | 原因 |
|---|---|
| 无总时长 | `handleProgress` 从未跑过，`durationMs` 只剩曲目元信息（本地文件通常没有） |
| 进度条不动 | `positionMs` 冻在起点 |
| 拖动无效 | 时长为 0 时 `CpSeekBar` 把目标 `coerceIn(0, 0)` 成 0；引擎侧 seek 本身是好的 |

| 判据 | 期望 |
|---|---|
| `grep -rn "try_send(AudioEvent::Progress" native/flick-player-engine/src` | 0 命中是预期，别当 bug 修 |
| `DesktopHiResEngine.getProgress()` / `startProgressPolling()` | 必须存在，由 `DesktopProgressPollTest` 的源码接线断言钉住 |

如果哪天引擎真的开始推送 Progress：两条路径会合并（同值重复无害），届时可撤掉轮询，但要先删测试里的源码断言，别静默改契约。

### 4.6 播完不切歌：`Ended` 被 `StateChanged(Stopped)` 吞掉

引擎播完（无 gapless 队列）时 `command_processing_loop` 先发 `TrackEnded` 紧跟 `StateChanged(Stopped)`，意图是「没曲可接，标 Stopped 让宿主兜底」。

宿主侧 `handleTrackEnded()` 写 `Ended`，`handleStateChanged("stopped")` 立刻覆写成 `Idle`；StateFlow 合并快速连续赋值，`PlaybackControllerImpl.observePlatform` 只看到 `Idle` ⇒ `onTrackEnded()` 永不触发 ⇒ 播完不切下一首。

这与 `PlatformPlayer.android.kt` 的 ExoPlayer `onIsPlayingChanged` 吞 `Ended` 是同构事故（那次是 `Paused` 吞，这次是 `Idle` 吞）。

| 修法 | 内容 |
|---|---|
| 规则 | `Ended` 只能被 `Playing` / `Buffering`（新曲开始）覆写；`Idle` / `Paused` 一律不许吞 |
| 例外 | `stop()` / `release()` 是显式赋值，不走这条路径 |
| 覆盖 | `DesktopHiResPlatformPlayer` 与 `FlickPlatformPlayer`（Android）都加了保护，由 `DesktopProgressPollTest` 源码断言钉住 |

### 4.7 DLL 搜索顺序曾遮蔽新库

`tryLoadLibrary()` 的候选里 `~/.cpplayer/native/` 曾排在 `app/native/` 前面。旧版本安装 / 运行时会往那里写 DLL，升级后残留 ⇒ 抢先加载 ⇒ 缺 `nativeGetProgress` 等新导出 ⇒ `UnsatisfiedLinkError`。

修法：持久化目录挪到候选最后，工作区 / 安装目录优先；`getProgress()` 对 `UnsatisfiedLinkError` 优雅降级（停轮询、只打一条日志）。

### 4.8 HTTP 源 seek 走 `probe_file` 必败

`handle_seek` 旧实现调 `spawn_decoder(path)` → `probe_file(path)` → `File::open(path)`。对 HTTP 流，`SourceInfo.path` 是 URL 字符串 ⇒ `File::open(url)` 必败 ⇒ 引擎进 `Idle` + 无源 ⇒ 暂停 / 播放空转，`PendingSeekTracker` 宽限期后弹回原点。

| 现象 | 原因 |
|---|---|
| 「有时能 seek」 | 缓存命中时走 `ResolvedSource.Local`（本地文件），`probe_file` 能打开 |
| 崩溃 / 重启后坏掉 | 缓存丢失 ⇒ 退回 `ResolvedSource.Stream` ⇒ 唯独这首歌不能 seek |
| 修法 | `SourceInfo` 新增 `http_meta: Option<RemoteMeta>`（`probe_http` 填入）；`handle_seek` 检测到 `http_meta` 就走 `probe_http` 重建解码器（带 headers）；本地文件仍走 `probe_file` |

## 5. gapless / crossfade 自动切歌契约

`queueNext` / `queueNextUrl` 已贯通 Rust、JNI、桌面与 Android Kotlin。`PlaybackMediaLoader.triggerPreloadNext` 在 `platform.supportsGaplessQueue` 时把下一首的 mediaId、URL（或缓存路径）和鉴权头一起交给引擎；这是预取，不提前改 UI 队列。

| 阶段 | 行为 |
|---|---|
| 引擎准备换源 | 开交叉淡化发 `CrossfadeStarted(toPath)`，未开时发 `NextTrackReady(path)` |
| 平台 | 按预取登记的路径找回 mediaId，通过 `nativeTrackTransitions`（StateFlow）报告「引擎已换源」 |
| 控制器 | 只提交队列索引、刷新元数据 / 歌词 / 听歌统计、预取再下一首；绝不再次 `load()` |
| 旧源的 `TrackEnded` | 按「属于已预取的旧源」直接消费，不 double advance；预取曲自然结束时仍正常上报 `Ended` |
| UI 更新时机 | `CrossfadeStarted` 时就更新：此时旧曲只剩淡出尾巴，进度与歌词已属新曲 |

| 约定 | 原因 |
|---|---|
| 用 StateFlow 而不是无 replay 的 SharedFlow | 切换与控制器订阅同帧发生时后者会丢事件，表现为元数据停在前一首 |
| 路径只用于事件与预取登记匹配 | 真正的队列身份是 mediaId |
| 手动切歌 / 队列重排 / 单曲循环 | 导航世代守卫丢弃旧的原生切换通知 |
| 预取开关 | 受「预加载下一首」控制；关闭或原生排队失败时维持 `Ended → playCurrent()` 兜底，仍可续播，只是没有双源交叉淡化 |

### 5.1 queue-next 必须复用当前输出流

`audio_queue_next_url()` 不能走 `prepare_stream()`：后者按下一首采样率调 `ensure_audio_engine()`，而排队发生在当前曲正在出声时。设备默认采样率（如 96 kHz 共享）与下一首（44.1 kHz）不同，`EngineManager` 判定输出签名变化，关掉正在播放的引擎再建一个空引擎。

| 症状 | 表现 |
|---|---|
| 静音 | 播放约 1~2 秒后突然无声 |
| UI | 状态仍是 `playing` |
| 进度 | `nativeGetProgress()` 返回 `null`，进度条冻结 |

正确做法：queue-next 只做 `probe_http()`；当前引擎已初始化时沿用它的采样率与声道数，把下一首解码 / 重采样到当前输出格式；只有当前没有已初始化引擎时，才按下一首采样率初始化输出。

回归：`DesktopQueueNextUrlRegressionTest`（真实本地 HTTP 流 + 真实引擎，播放 1.2 秒后排队，断言 `getProgress()` 仍可用且进度继续前进）。

## 6. 验证

| 改动在哪 | 怎么验证 |
|---|---|
| `FlickPeqMapper`（PEQ / FX / 动态处理，纯函数） | `:core:desktopTest --tests "*FlickPeqMapperTest"` |
| `HttpMediaSource` | `cargo test --lib http_source`（自带本地 HTTP / Range server，与客户端同进程，不随父 shell 被杀） |
| Kotlin 适配层 / 控制器 / 设置页 | `:core:compileKotlinDesktop` + `:app:compileKotlinDesktop` |
| JNI 符号是否进 DLL | 第 1.1 节的 `grep -ao` |
| 听感（Q 值、混响、交叉淡化、倍速） | 编译测不到，必须放音核对 |

2026-11-10 实测：

| 命令 | 结果 |
|---|---|
| `cargo test --offline --lib http_source` | 3/3 通过（跨窗口读、回退 seek、`Content-Range` 解析、scheme 大小写） |
| `cargo test --offline --lib jni_api::tests` | 4/4 通过（扁平 header 成对、奇数长度报错、空数组、`apiLevel=2` 握手） |
| `cargo test --offline --lib`（全量 159） | 154 通过 / 5 失败，均为既有失败，见下 |
| `:core:desktopTest --tests "*FlickPeqMapperTest"` | 23/23 通过 |
| `:core:desktopTest`（全量 787） | 786 通过 / 1 失败：`KmpSdkMigrationOperationsTest` 期望操作码 `[127..165]`、实际 `[127..166]`，与播放链路无关 |
| `:core:compileKotlinDesktop` + `:app:compileKotlinDesktop` | 通过 |
| `cargo build --release --offline --lib` | 通过；DLL 同步到 `app/native/` 与 `native/`（三处 md5 一致） |
| DLL 符号核对 | `DesktopHiResEngine_native*` 共 27 个，含 `nativePlayUrl` / `SetPlaybackSpeed` / `SetCompressor` / `SetCrossfade` |

既有失败（与播放链路无关）：

| 用例 | 原因 |
|---|---|
| `library_scan::tests::*` 4 例 | `Path::canonicalize()` 在 Windows 返回 `\\?\C:\...`，断言期待 `C:\...`；这些文件停在 8 月版本 |
| `audio::device::tests::dap_without_audio_caps_native_dsd_gets_false_without_runtime_probe` | `classify_device` 的 `supports_native_dsd = is_dap \|\| caps \|\| runtime`，对 DAP 恒为 true，测试却断言 false |

真要修：前者把 scanner 的 canonicalize 换成去 `\\?\` 前缀的规范化；后者改测试或把 `is_dap` 从 native-DSD 判定里摘掉。都会改变线上行为，需单独评估。

并行会话的瞬态失败：仓库里有别的会话在重做 `core/music` 与 `provider-sdk-kmp` 的生成文件，曾出现 `MusicBackend.kt: Unresolved reference 'sdkAccountNamespace'`、`ExtendedMusicSource.generated.kt` 类型不匹配等编译错误。判断方法：看报错文件是否在 `core/src/commonMain/kotlin/cp/player/core/playback/` 之外。

## 7. 构建与平台

### 7.1 源码不在 git 里

`native/flick-player-engine/` 由闭源引擎仓库维护。主仓库保留 Kotlin 集成代码与 `app/native/` 下用于本地开发的正式版 DLL。Windows 发布任务从私有 Release 下载指定版本的 DLL，校验后用于打包。测试构建的 DLL 请留在本地，提交正式版 DLL 前请核对版本。

### 7.2 本机构建 Rust

| 项 | 值 |
|---|---|
| VS 位置 | `E:\project\build`（不在 `C:\Program Files`） |
| Windows SDK | `10.0.26100.0` |
| 直接 `cargo build` 失败原因 | PATH 里的 `/usr/bin/link.exe`（Git 自带 coreutils） |
| 做法 | 先放 MSVC 的 `cl.exe` / `link.exe` 进 PATH，并设 `LIB` / `INCLUDE` |
| 便捷方式 | `source rust-msvc-env.sh` |

PATH 必须是 MSYS 风格（`/e/...`）bash 才当可搜索目录；`LIB` / `INCLUDE` 给 Windows 程序读，必须 `E:\...` 风格。

```bash
VCVER=14.51.36231
SDKVER=10.0.26100.0
export PATH="/e/project/build/VC/Tools/MSVC/$VCVER/bin/Hostx64/x64:/c/Program Files (x86)/Windows Kits/10/bin/$SDKVER/x64:$PATH"
export LIB="E:\\project\\build\\VC\\Tools\\MSVC\\$VCVER\\lib\\x64;C:\\Program Files (x86)\\Windows Kits\\10\\Lib\\$SDKVER\\um\\x64;C:\\Program Files (x86)\\Windows Kits\\10\\Lib\\$SDKVER\\ucrt\\x64"
WSDK='C:\Program Files (x86)\Windows Kits\10'
export INCLUDE="E:\\project\\build\\VC\\Tools\\MSVC\\$VCVER\\include;$WSDK\\Include\\$SDKVER\\ucrt;$WSDK\\Include\\$SDKVER\\shared;$WSDK\\Include\\$SDKVER\\um;$WSDK\\Include\\$SDKVER\\winrt;$WSDK\\Include\\$SDKVER\\cppwinrt"
cd native/flick-player-engine && cargo build --release --offline --lib
```

产物 `target/release/rust_lib_flick_player.dll` 需同步到 `app/native/`（打包用）与仓库根 `native/`（开发态查找路径）。

### 7.3 Android

| 项 | 内容 |
|---|---|
| `ureq`（`tls` feature） | 全平台依赖；FlickEngine 的 Android 与桌面共用 `HttpMediaSource`、鉴权头透传、HTTP seek 重建解码器逻辑 |
| Media3 | 仍走 ExoPlayer 自己的网络栈 |
| 直连失败 | 回退本地缓存下载；已有缓存优先本地播放；时长未知时 seek 只夹下界 |
| JNI 桥 | `RustEngine` 新增 `nativePlayUrl`、倍速、原生交叉淡化；动态引擎切换时重放这些配置；本地 / HTTP 预取队列已接通 |
| USB 独占 | `AndroidAudioLifecycleManager` 扫描并筛选 USB Audio Class 设备，申请运行时权限、claim / release 音频接口解除内核驱动占用，把 fd 交给 Rust UAC2；拔出或释放时先清 native 会话并等停止，再关 Java 连接 |
| Manifest | 增加 USB 附加入口与 Audio Class 过滤 |
| 待真机验证 | 实际采样率协商、USB 权限弹窗、拔插 |
| 构建 | `native/flick-player-engine/build_android.sh` 用 `cargo ndk -o` 把 ARM64 `.so` 输出到 `app-android/src/main/jniLibs/arm64-v8a/`；只跑 `cargo ndk build` 不会自动进 APK |

已生成 8,754,552 字节 release `.so`，核实 `mergeDebugNativeLibs` 的打包输入包含同一文件；导出符号含 `RustEngine_nativePlayUrl` / `nativeQueueNext` / `nativeSetCrossfade`。`assembleDebug` 仍被既有 `jvmMain` `MethodHandle.invoke/invokeExact` 的 minSdk 24 D8 错误阻断（要求 API 26），与 FlickEngine 无关，不能顺手提高全应用 minSdk 来掩盖。

| 定向回归 | 结果 |
|---|---|
| `PlaybackControllerNavigationTest` | 33/33，含原生换源不重复 load、旧源结束去重、过期事件不连跳 |
| `PlaybackControllerPreloadTest` | 4/4，含下一首 URL 与鉴权头进入原生队列 |
| `FlickPeqMapperTest` | 23/23 |
| `DesktopProgressPollTest` | 15/15 |
| `cargo ndk -t arm64-v8a check` / `build --release` | 通过，已输出到实际 `jniLibs` |

`audio/http_source.rs` 与 `audio_api.rs` 的 URL 分支不再做 Android 排除。

### 7.4 混淆

`app/desktop-proguard-rules.pro` 必须保留 `cp.player.core.playback.DesktopHiResEngine` 的类名与 native 方法名：Rust 侧符号名写死，一混淆就 `UnsatisfiedLinkError`，而且只在装出来的包里复现（`desktopRun` 不做混淆）。

### 7.5 Linux：同一个引擎，另一份原生库

Linux 与 Windows 走**同一条**播放链路（`DesktopHiResPlatformPlayer`），差别只有三处：

| 项 | Windows | Linux |
|---|---|---|
| 原生库名 | `rust_lib_flick_player.dll` | `librust_lib_flick_player.so` |
| 输出后端 | WASAPI（cpal） | ALSA（cpal） |
| 独占直通 | WASAPI Exclusive（VIP） | **不存在**；设置页只显示共享输出 |

ALSA 与 WASAPI 的 `set_exclusive_mode` 无关，`CP_WASAPI_EXCLUSIVE` 只影响 WASAPI 分支；Linux 上 `supportsExclusiveAudio` 恒为 false，设置页据此隐藏独占项。

#### 打包怎么进包（deb 与 tar.gz 都必须有）

```
app/native/librust_lib_flick_player.so
  → prepareDesktopAppResources（Sync，按宿主过滤 .so/.dll）
  → app/build/app-resources
  → jpackage --app-content            ← nativeDistributions.appResourcesRootDir
  → <install>/lib/app/librust_lib_flick_player.so
```

`--app-content` 是 deb 唯一的入口：`packageLinuxTarGz` 的注入发生在 `createDistributable` **之后**，而 `packageDeb` 是另一次 jpackage 调用、自己生成镜像，事后注入碰不到它。缺这份库的 Linux 包能装能启动，但**一点声音都没有**（`NoopDesktopPlatformPlayer`），所以 CI 里有两条硬断言：tar.gz 清单必须含 `CPPlayer/lib/app/librust_lib_flick_player.so`，deb 必须含 `opt/CPPlayer/lib/app/librust_lib_flick_player.so`。

运行时依赖：`libasound.so.2`（发行版包名一般是 `libasound2` / `alsa-lib`）。jpackage 的 deb 不写 `Depends:`，AUR 的 `PKGBUILD` 已显式依赖 `alsa-lib`，其它分发方式需要在文档里提示用户。

#### 交叉编译（Windows 宿主 → x86_64-unknown-linux-gnu）

引擎源码在私有仓库，本机 `native/flick-player-engine` 是未入库的检出。没有 Linux 机器时可以用 zig 做交叉链接：

| 组件 | 作用 |
|---|---|
| `zig cc -target x86_64-linux-gnu` | 交叉 C 编译器 **与** 链接器（自带 glibc） |
| 解包的 `libasound2-dev` + `libasound2`（Debian） | 头文件与 `libasound.so`，cpal 的 ALSA 后端需要 |
| `pkg-config` 替身 | `alsa-sys` 的 `build.rs` 只认 `pkg-config` 输出，给它 `-L… -lasound -I…` 即可 |
| `AR` 替身（**exe**，不是 .bat） | opus 有上百个 `.o`，`ar` 命令行超过 cmd.exe 的 8191 字符上限 |

```bash
export PKG_CONFIG_ALLOW_CROSS=1 PKG_CONFIG=<sysroot>/pkg-config.bat
export CC_x86_64_unknown_linux_gnu=<sysroot>/zigcc.bat
export CXX_x86_64_unknown_linux_gnu=<sysroot>/zigcxx.bat
export AR_x86_64_unknown_linux_gnu=<sysroot>/arwrap.exe
export CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER=<sysroot>/zigld.bat
cargo build --release --target x86_64-unknown-linux-gnu \
  --no-default-features --features native_audio --lib
```

| 坑 | 现象 / 原因 |
|---|---|
| `link.exe` 被 Git 的 coreutils 抢走 | 宿主 build script 编译失败（`link: extra operand`）；先 `source rust-msvc-env.sh` |
| cc-rs 追加 `--target=<triple>` | zig 只认 `-target x86_64-linux-gnu`，包装器必须过滤掉这个参数 |
| 用 `.bat` 写过滤循环 | `for %%a in (%*)` 把 `=` 当分隔符，`--target=…` 被拆成两个 token，过滤静默失效 |
| `AR` 用 `.bat` | 命令行超 8191 字符，报「文件名或扩展名太长」；换 CreateProcess 包装的 exe |
| `uac2` feature | 代码里只对 Android 生效，桌面构建加 `--no-default-features` 可省掉 libusb |
| 产物依赖 | 只应出现 `libasound.so.2` / `libc` / `libm` / `libpthread` / `libdl`；出现 `libgcc_s.so.1` 说明链接方式不对 |

校验产物（不依赖 readelf）：

```bash
python - <<'EOF'
d = open("librust_lib_flick_player.so","rb").read()
assert d[:4] == b"\x7fELF" and d[4] == 2 and d[18:20] == b"\x3e\x00"  # 64-bit x86_64
assert b"Java_cp_player_core_playback_DesktopHiResEngine_nativePlay" in d
EOF
```

Linux 的 `.so` **只放 Release，不进 git**。CI 复用已有的 `FLICK_NATIVE_RELEASE_TAG` 下载资产 `librust_lib_flick_player-linux-x64.so`，落地时改名为 `app/native/librust_lib_flick_player.so`。资产必须换个名字：GitHub Release 资产是平铺的，Android arm64 已经占用 `librust_lib_flick_player.so`，同名无法共存（上一版同名会互相覆盖）。**不设 sha256 变量**：CI 改用结构校验（ELF magic、`e_machine=0x3e`、JNI 符号、VIP 公钥），Gradle 的 `packageLinuxTarGz` 也查 ELF 头 —— 挡的是「把 Android arm64 同名库误放进桌面目录」这类真实事故：JVM 会 dlopen 成功，但所有 JNI 调用失败，现象是静音且不报错。

### 7.6 Windows：动态输出缓冲与 MMCSS

Windows PCM 工作流是「解码 → 按需重采样 → 混音和音效 → WASAPI」。Symphonia（含 HTTP）和 WavPack 在源、设备采样率不同时，用独立的重采样线程和有界原始 PCM 队列；相同采样率直接旁路。最终输出队列固定容量，按帧计数，WASAPI 回调只消费预处理样本、补静音并更新原子计数。格式转换暂存区在流启动时分配。WASAPI 输出线程以 MMCSS `Pro Audio` 注册，退出时在原线程撤销；注册失败仍继续播放。

默认低水位、起播水位、高水位分别约为最大已观测回调请求的 2、3、6 倍（周期估算下限 10 ms）。欠载增加水位，连续 30 秒无新增欠载逐步降低。软件队列调节不会重开 WASAPI 流或改变设备周期。seek、停止和换源等待输出消费者确认清空旧软件队列后才重新填充；已经交给系统的帧仍受设备排空时间约束。短曲和曲尾可以低于起播水位排空，曲终事件在有效帧提交并等待估算的设备延迟后发出。进度同样扣除软件积压和估算的设备延迟。

调试时找 `WASAPI reservoir` 日志，对照累计欠载次数、最大回调帧数和三个水位。真机验证应覆盖 WASAPI Shared / Exclusive、本地 44.1/48 kHz、HTTP 抖动、暂停与 seek、变速、无缝切歌和设备拔插。独占路径仍沿用原有格式协商和转换；位完美与 DoP 需要单独采集输出验证。Android 的 Oboe、USB Direct 和 Native DSD 仍使用既有后端。

开发验证可以用 `JAVA_TOOL_OPTIONS` 将 `-Djava.library.path` 指向 Rust 的 `target/release` 目录，再运行 `:app:desktopRun`。启动日志应显示 DLL 从系统库搜索路径加载。这样无需覆盖 `app/native` 中的正式 DLL。


## 相关

- 架构与源集：[ARCHITECTURE](ARCHITECTURE.md)
- 构建与并行会话：[BUILD](BUILD.md)
- 音质与缓存行为：[providers/WORKFLOWS](providers/WORKFLOWS.md)
