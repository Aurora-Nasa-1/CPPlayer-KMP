# FlickEngine 与本体的接入对照（能力矩阵 / 已知缺口）

> 这份文档解决一类**只靠读代码发现不了**的问题：能力在引擎里实现了、宿主里没接；
> 或者接了但接错了参数；或者 UI 声称支持、实际什么也没发生。
>
> 三条链路必须同时对齐：
> `native/flick-player-engine/src/**`（Rust 实现）
> → `src/jni_api.rs`（JNI 导出符号）
> → `DesktopHiResEngine`（Kotlin JNI 声明）
> → `DesktopHiResPlatformPlayer`（适配层）
> → `PlaybackController` / 设置页

---

## 1. 桌面端只有一条播放链路

`PlatformPlayer.desktop.kt` 的 `createPlatformPlayer` 固定调 `DesktopEngineManager.createPlayer()`，
而 `createUnderlyingPlayer` **只有** `DesktopHiResPlatformPlayer` 一个实现
（标准/独占只是 `isExclusive` 参数不同）。由此有两条硬结论：

1. **旧的 rodio 链路（`AudioPlayerImpl`）在桌面已是死代码**，只在测试/未覆写路径里存在。
   任何「rodio 可以做的事」都不能再拿来当兜底（例如「HTTP 流交给 rodio 播」）。
2. **原生库是硬依赖**：DLL 缺失时 `createUnderlyingPlayer` 返回
   `NoopDesktopPlatformPlayer`，桌面端**任何**音频都放不出来（state = Error）。

---

## 2. 能力矩阵

「JNI」一列指 DLL 里**真实存在**的导出符号。核对方法（不需要 dumpbin）：

```bash
grep -ao "Java_cp_player_[A-Za-z_]*" app/native/rust_lib_flick_player.dll | sort -u
```

| 能力 | Rust 实现 | JNI 导出（DLL） | Kotlin 接线 | 结论 |
|---|---|---|---|---|
| 播本地文件 | `audio_play` | `DesktopHiResEngine_nativePlay` | ✅ | 可用 |
| 暂停 / 继续 / 停止 | `audio_pause/resume/stop` | ✅ | ✅ | 可用 |
| seek | `audio_seek(secs: f64)` | ✅ | ✅ + `PendingSeekTracker` | 可用（见 §3.1） |
| 音量 | `audio_set_volume` | ✅ | ✅ | 可用（引擎侧按 −40 dB 感知曲线映射） |
| PEQ（参数均衡） | `audio_set_equalizer`（30 段、双缓冲无锁） | ✅ | ✅ | 可用；Q 由 `DesktopPeqMapper` 推导 |
| 声道平衡 / 立体声宽度 | `audio_set_fx` | ✅ | ✅ | 可用（见 §3.2 的混响陷阱） |
| 空间感（virtualizer） | 仅 `set_fx` 的 `width` | ✅ | ✅ | 只有「加宽侧声道」，不是真正的环绕 |
| **响度均衡 / 防爆音** | `audio_set_compressor` + `audio_set_limiter` | ❌ **未导出** | ❌ | **能力位必须保持 false** |
| **无缝下一首（gapless）** | `audio_queue_next` / `audio_skip_to_next` | ❌ 未导出 | ❌ | 未接 |
| **交叉淡入淡出（真重叠）** | `audio_set_crossfade` / `_curve` | ❌ 未导出 | 用音量斜坡近似 | 见 §4 |
| **播放速度** | `audio_set_playback_speed` | ❌ 未导出 | ❌ | 未接 |
| DSD 输出模式 | `audio_set_dsd_output_mode` | 仅 `RustEngine` 类有 | ❌ | 桌面端切不了 |
| 硬件独占（WASAPI Exclusive） | 读进程环境变量 `CP_WASAPI_EXCLUSIVE` | `nativeSetExclusiveMode` ❌ 不存在 | 走 `SetEnvironmentVariableW` + `PlaybackSessionSettings.KEY_WASAPI_EXCLUSIVE` | 能生效，但只在下次建输出时；**默认关 + 需 VIP**（见 §3.3） |
| **网络流直连** | ❌ 完全没有 | — | 先落盘再播 | 见 §5 |

`audio_get_state` / `audio_get_progress` 也**只有** `RustEngine` 类导出；
`DesktopHiResEngine` 靠 `nativePollEvent` 的事件流推位置，不要以为可以直接查。

---

## 3. 三个已经踩过的坑

### 3.1 seek：时长未知时不要夹到 `[0, duration]`

`seekTo` 里一度写成 `positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L))`。
时长在两种情况下是 0：曲目元信息没有 duration、引擎还没发出第一条 `Progress`。
此时 `coerceIn(0, 0)` 恒等于 0 ⇒ 用户一拖就回到开头，而且
`PendingSeekTracker` 会把它当成一次真实的用户 seek 去对账，最后再报一次**假失败**。

正确写法：`duration > 0` 才夹；否则只保证非负。

### 3.2 `set_fx(enabled = true)` 会顺带打开一条 240 ms 混响

`SpatialFx` 的 `enabled` **同时**控制「延迟线混响」与「立体声加宽」两件事，
真正决定混响量的是 `mix`（默认 0.25）+ `feedback`（默认 0.35）+ 固定 240 ms 延迟。
所以为了下发「声道平衡」而把 `enabled` 置真、其余留默认值时，
用户听到的是**一条混响尾巴**。

使用约定：只用平衡/宽度时，`mix`、`feedback`、`damp` 必须显式写 0
（见 `DesktopPeqMapper.CLEAN_FX_*`）。

### 3.3 独占模式的开关是**环境变量**，不是 JNI

引擎在创建输出时按 `desired_output_signature()` 决定 `exclusive`/`shared`，
而那个 signature 读的是进程环境变量 `CP_WASAPI_EXCLUSIVE`。
`DesktopHiResEngine.setExclusiveMode` 通过 `SetEnvironmentVariableW` 改的是**真实进程环境**，
所以能生效 —— 但只在**下一次创建输出**（换曲/重建引擎）时，不是立即。
`nativeSetExclusiveMode` 这个导出在 DLL 里根本不存在，调用被 `runCatching` 吞掉，
**不要**把它当成「运行期立即切换」的保证。

#### 缺省值：**shared**（曾经是 exclusive，已改）

- Rust 侧 `desired_output_signature` 的 `.unwrap_or(...)` 从 `true` 改成 `false`；
  Kotlin 侧 `DesktopHiResPlatformPlayer(isExclusive = false)`、
  `DesktopHiResEngine._isExclusiveMode = false`。
- **独占不是 FlickEngine 的默认属性**：它会让系统提示音、浏览器、会议软件在播放期间
  全部静音，必须由用户显式打开（`PlaybackSessionSettings.KEY_WASAPI_EXCLUSIVE`，默认 `false`），
  且非 VIP 会被 `DesktopEngineManager.resolveDesiredEngine` 一律回落共享。
- 旧实现把「选了发烧内核」当成「同意独占」：键不是 `standard` 且是 VIP ⇒ 独占。
  症状就是启动日志里那句 `SUCCESS: Initialized in EXCLUSIVE (Bit-Perfect) hardware mode!`
  —— 用户没点过任何开关，声卡已经被抢走了。
- **改这一处要同时改三份默认值**（Rust / Player / Engine），漏一份就会在
  「Kotlin 没来得及下发环境变量」的窗口里回到独占。

---

## 4. 淡入淡出：宿主做的是音量斜坡，不是引擎交叉淡化

`PlaybackFadeController` + `FadeRunner` 在 Kotlin 侧按 `FadeConfig` 调 `platform.setVolume`，
效果是「本曲末尾淡出 → 下一曲开头淡入」，中间**没有重叠**。
引擎的 `audio_set_crossfade` 是真正的双源交叉，但未导出，且 passthrough（bit-perfect）下会被拒绝。

接引擎侧 crossfade 之前要考虑：它会与宿主的 `FadeRunner` 互相打架（两边同时改音量），
必须二选一，而不是叠加。

---

## 5. 流媒体：引擎只认本地文件，桌面端必然「先下完再出声」

`audio_play(path: String)` 内部是 `PathBuf::from(path)` → `detect_file_type` → 本地 probe，
**没有任何 HTTP 支持**（crate 里也没有 HTTP 客户端依赖）。因此：

- `DesktopHiResPlatformPlayer.resolvePlayablePath` 对 http(s) 一律走
  `StreamLocalizer.localize()` 落盘，**这个 `suspend` 会一直等到整首下载完**；
- 落不了盘时**绝不能**把 URL 原样交给引擎 —— 那会得到
  「Failed to open audio track in …: https://…」，看起来像文件损坏，实际是内核不支持；
  现在改为 `ResolvedSource.Unavailable`，给出明确文案。

代价要说清楚：一首 4 分钟无损 ≈ 25–35 MB，慢网下是**十几秒无声等待**，
而 `isLocalizing`（「边播边落盘」）在桌面端**永远不会为 true** —— 那条路径是给能播流的引擎设计的。

### 补齐它需要什么（未做，缺构建环境）

给引擎加一个可 seek 的 HTTP 数据源，`symphonia` 的 `MediaSource` 要求 `Read + Seek + Send + Sync`：

1. 探测：`HEAD`（或 `Range: bytes=0-0`）拿 `Content-Length` / `Accept-Ranges`；
2. 读：维护一个有界窗口，耗尽时发 `Range: bytes=<pos>-` 续读；
3. seek：`SeekFrom::Start` 直接作废窗口改 `pos`；服务端不支持 Range 时只能前向跳过；
4. `audio_play` 按 scheme 分流；格式识别交给 symphonia 的 probe（不要靠扩展名）。
5. 宿主侧再补一个「边下边播 + 拖到未缓冲区间再落盘」的策略，与 §5 现状并存。

⚠️ **前置条件：本机当前没有 MSVC / Windows SDK**（`link.exe` 只有 Git 自带的 coreutils 版本，
`x86_64-pc-windows-gnu` 目标缺 `windows_x86_64_gnu` crate 且离线不可取），
`cargo build` 无法链接 ⇒ 原生侧改动必须在有 VS Build Tools 的机器 / CI 上编译验证，
**不要在本机「改完看着对」就当作可用**。

---

## 6. 验证方式

| 改动在哪 | 怎么验证 |
|---|---|
| `DesktopPeqMapper`（纯函数） | `:core:desktopTest`（`DesktopPeqMapperTest`，18 例） |
| 适配层 / JNI 参数 | `:core:compileKotlinDesktop` + 真机放音（参数名、数组顺序、开关语义都是静默错误） |
| Rust | `cargo test`（含本地 HTTP server + Range 的集成测试，server 与客户端必须在**同一条命令**里起，否则随父 shell 一起被杀） |
| DLL 导出是否真的存在 | 见 §2 的 `grep -ao` 方法 |

`grep -ao` 这招值得常备：`UnsatisfiedLinkError` 被 `runCatching` 吞掉之后，
「调了但没生效」和「压根没这个方法」在 Kotlin 侧长得一模一样。
