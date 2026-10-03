# 歌曲缓存机制分析 & 数据管理功能建议

> 面向实现者。所有结论均落到具体文件与行号，可直接复核。
> 涉及文件：
> - `core/src/commonMain/kotlin/cp/player/core/cache/`（`ApiCache.kt` / `CachedMusicApiService.kt` / `CacheConfig.kt` / `CacheEntry.kt` / `CacheResult.kt` / `CacheStats.kt` / `Fingerprinter.kt`）
> - `core/src/commonMain/kotlin/cp/player/core/MusicBackend.kt`
> - `core/src/commonMain/kotlin/cp/player/core/playback/StreamLocalizer.kt`、`PlaybackControllerImpl.kt`
> - `core/src/jvmMain/kotlin/cp/player/core/playback/DesktopStreamLocalizer.kt`
> - `core/src/commonMain/kotlin/cp/player/core/api/AmllTtmlClient.kt`
> - `app/src/commonMain/kotlin/cp/player/app/ui/model/StorageSettingsModel.kt`、`.../ui/screen/SettingsSubScreens.kt`

---

## 0. 结论速览

1. **「缓存」在本应用里是两件完全不同的事**，必须分开谈：
   - **元数据缓存**（JSON：歌单 / 专辑 / 歌词 / 播放地址 …）—— `CachedMusicApiService` 装饰器 + `InMemoryApiCache`，**纯进程内 LRU，重启即清零**。
   - **音频字节缓存**（无损流的真实文件）—— `DesktopStreamLocalizer` 落到 `~/.cpplayer/stream-cache/`，**跨重启保留**，**仅桌面端**（Android 是空实现）。
2. **能正确缓存**，且主路径设计是清晰的：读透（read-through）+ TTL 新鲜度 + 失败降级到旧缓存 + 多 Provider 容灾 + 写操作精确失效。键构造（provider/账号隔离、参数转义、稳定哈希）是这套机制里做得最扎实的部分。
3. **主要短板原本不在「缓存对不对」，而在「缓存看不见、管不了」** ——
   这一条已在 2026-10-03 解决：无损流缓存与接口缓存都有了查看 / 搜索 / 删除 / 清理入口
   （见 §6.3）。**仍未覆盖的只剩 AMLL 歌词缓存**。
4. **1 个功能性缺口（离线播不了已缓存的无损曲）仍未修**，见 §5-P0。

---

## 1. 缓存全景

| # | 名称 | 载体 | 存什么 | 键 | 容量 / 生命周期 | 清理入口（现状） |
|---|---|---|---|---|---|---|
| A | **元数据读透缓存** | `InMemoryApiCache`（进程内 `LinkedHashMap` LRU） | API 响应 `JsonElement` | `providerId#method#sortedParams#cookieHash` | `maxEntries=64`；**进程内**，重启清零 | 仅 `logout()`；**无 UI** |
| B | **无损流磁盘缓存** | `~/.cpplayer/stream-cache/<sanitize(key)>-<hash>.<ext>` | 无损音频完整字节 | `<mediaId>@<音质>`（哈希后为文件名） | `2 GiB` LRU；**跨重启** | **无接口、无 UI**，只能手动删目录 |
| C | **AMLL 歌词磁盘缓存** | `SettingsStorage` namespace `amll_ttml_cache` | TTML 原文 | `ttml:p:<平台>:<id>` / `ttml:f:<filename>` / `ttml:s:...` | 索引键维护，`MAX_DISK_ENTRIES=15`；跨重启 | 无 UI（`clearMemo()` 只清内存） |
| D | **图片缓存** | Coil `diskCache` / `memoryCache` | 封面等图片 | Coil 内部 | Coil 默认；跨重启 | ✅ 已有：设置 →「下载与存储」→ 清理图片缓存 |
| E | **封面取色缓存** | `CoverSeedCache`（内存） | URL → 主题种子色 | URL | 内存 | ✅ 随 D 一起清 |
| F | **下载产物** | 用户目录（可配置） | 用户主动下载的完整文件 | 文件系统 | 用户控制 | ✅ 已有：下载管理页可删 |

> 注：F 严格说不是「缓存」而是**用户资产**，但它和 B 常被用户混为一谈——两者都是「磁盘上的音频文件」，差别是 F 由用户显式下载、永久保留、计入媒体库；B 是播放副作用、可被 LRU 淘汰、随音质变化另存一份。**数据管理界面必须把这两者分开呈现**，否则用户会误以为「清缓存会删掉我下载的歌」。

---

## 2. 逐层流程

### 2.1 元数据读透缓存（A）

**装配**：`MusicBackend.init()`（`MusicBackend.kt:783`）`cache ?: InMemoryApiCache(cacheConfig.maxEntries)` → 包进 `CachedMusicApiService` → `backend.musicApi` / `cachedApi` / `unifiedSource` 交出去的都是装饰器，裸实现不外泄（`MusicApiServiceFactory.kt:48`）。默认 `freshTtlMs = 5min`、`maxEntries = 64`（`CacheConfig.kt:12`）。

**键构造**（`ApiCache.kt:109` `cacheKey`）三层防护，值得肯定：
- 参数按 key 排序 → 传参顺序不影响命中；
- `# & = %` 转义 → `{a:"1&b=2"}` 与 `{a:"1",b:"2"}` 不会撞键；
- `cookie` 参与键（FNV-1a 64 位 `stableHash64`，**刻意不用 `String.hashCode`**）→ 同机多账号隔离；空 cookie 与 null 统一为 `anon`。

**读路径**（`CachedMusicApiService.read()` `:80`）：

```
未列入 isCacheable 名单 → 直通网络（无缓存、无统计）
命中且 age ≤ freshTtlMs → hits++，直接返回，不发网络
未命中 / 超 TTL       → misses++
    ├─ 回源成功(isSuccessResponse) → store(key) 并返回
    ├─ 回源抛异常 → tryFallback（其它 Provider）
    │                 └ 无 → staleOrNull(旧缓存) → staleServed++ 并返回
    │                       └ 无旧缓存 → 原样抛异常
    └─ 回源返回非成功码 → tryFallback → 旧缓存 → 原样交出失败响应
```

要点：
- **成功判定只看业务码**（`ApiResponseCodes.isSuccess`），不看 `HealthMonitor` 字段级告警——告警属「可用性」，拿它当业务成败会让缺个可选字段的 200 响应被当成故障去打多个 Provider（`:41` 注释）。
- **唯一例外**：`song/url/v1/302` 按 RFC 7231 返回 `{cookie, level, redirectUrl}` **没有 code 字段**，因此单独走 `httpUrlOf()` 判成功（`:554`），否则这类响应永不写回缓存。
- **`isCacheable` 白名单**（`:586`）覆盖 30 个读类方法；`comment/mv` 等刻意不进；`pl/count`、写类、动作类一律直通。
- 键里**刻意剔除 `timestamp`**（`getLikeList` 底层每次现取 `now()`，带上就永远命中不了，`:214`）；`comment` 的 `type` **必须进键**（两种评论共用一个端点，不带就串数据，`:262`）。

**流式副路径**（`callApiCached()` `:390`）：先 emit `Cached`（带 `ageMs` / `isStale`），再 emit `Fresh` / `NoChange` / `Error`。`NoChange` 靠 `Fingerprinter`（code + 数组长度 + 主数组 id 列表 + 版本位）判断「内容身份未变」。
⚠️ **本地文件**：`AmllTtmlClient` 的磁盘持久缓存、`Fingerprinter` 的 `PRIMARY_KEYS` 等都在同层。

**写透失效**（`:293` `write`）：`finally` 里失效——写抛异常也失效，理由是「漏失效的代价是用户改完歌单看不到变化，比多一次回源难查得多」。失效粒度是**前缀**（`$providerId#$method`），因为键里带参数，无法逐键枚举。`logout()` 因为要换账号，直接 `clear()` 全表。

**可观测**：`CacheStats`（hits / misses / stores / staleServed / invalidated）经 `backend.cachedApi.stats` 暴露为 `StateFlow`——**已采集，但全仓无一处 UI 消费**（grep 确认）。

### 2.2 无损流磁盘缓存（B）

**为什么存在**：桌面引擎（rodio）对 **FLAC over HTTP 的 seek 是静默空操作**——`seekTo()` 返回成功、位置不动。实测矩阵（`StreamLocalizer.kt:10`）：

| 格式 | 传输 | seek |
|---|---|---|
| WAV / MP3 | Range HTTP | ✅ |
| **FLAC** | **Range HTTP** | **❌** |
| FLAC / WAV / MP3 | 本地文件 | ✅ |

只失败在 `FLAC × HTTP` 这一格。所以**只有无损档位**（`LOSSLESS_LEVELS = {lossless, hires, jymaster, sky}`）才落盘；标准/极高（MP3/AAC）本来就能定位，落盘是纯损失。Android 用 ExoPlayer，无此问题 → `NoOpStreamLocalizer`。

**播放时序**（`PlaybackControllerImpl.loadCurrent()` `:1006`）：

```
cacheKey = "<mediaId>@<qualityLevel>"
cachedPath(cacheKey) 有命中？
  ├ 有 → 直接 load 本地文件（秒开 + 立刻可拖），playingFromLocal = true
  └ 无 → 先 load 流地址立刻出声（0 等待）
          └ 同时后台 startBackgroundLocalize()：isLocalizing = true
              （UI 据此禁用进度条并显示「正在缓存无损音质，完成后可拖动…」）
              落盘完成 → 只记进 `localized`，**不立刻切源**（换源有咔哒声）
              ↓
             用户第一次拖动 → requestSeek() 发现 local 就绪 → 切到本地文件并 seek
```

设计取舍写得很明确（`StreamLocalizer.kt:23-32`）：**「秒开 + 保无损 + 可拖动」三者兼得**，代价是拖动瞬间有一次预期内的重载。这比「等整曲下完才出声」或「无损不可拖」都好。

**落盘校验**（`DesktopStreamLocalizer.localizeBlocking()` `:65`）四个防御点：
1. **先写 `.part` 再原子改名**——被杀/失败不会留下「看起来完整」的半截缓存；
2. **`Content-Length` 已知时必须核对字节数**——CDN 断流会给出长度不足的响应，不校验就把截断文件当命中；
3. **扩展名按 magic bytes 判定**（`fLaC` / `OggS` / `RIFF` / `ID3` / `ADIF` / `ftyp` / MP3 帧同步字），不信 URL、更不信 `Content-Type`（上游把 FLAC 标成 `audio/mpeg`）——引擎选解码器会看扩展名；
4. **任何失败返回 `null`，绝不抛到播放路径**——宁可「能播但不能拖」，不能因为缓存问题让人听不了歌。`CancellationException` 显式重抛（不用 `runCatching`，否则切歌取消变空操作、旧下载继续跑到底白占带宽）。

**并发与淘汰**：
- 同一 key 的并发下载由 `Mutex` 串行化（`:69`），避免两个请求互相截断同一文件；
- 命中时 `setLastModified(now)` 触碰访问时间，LRU 不会把正在听的曲子先淘汰（`:43`、`:75`）；
- 超 `2 GiB` 时按 `lastModified` 从旧到新删，**保留当前刚写入的文件**（`evictIfNeeded(keep=target)` `:157`）。
- **切歌作废**靠 `loadGeneration` 代际号（`:573`），避免旧下载结果落到新曲头上。

### 2.3 其它缓存

- **AMLL 歌词**（`AmllTtmlClient`）：按 id/filename 取到的内容官方保证永久不变 → 内存 LRU(100) + `SettingsStorage` 磁盘层；搜索结果可能变 → 只做会话内存缓存；404 进负缓存（内存，最多 500）。磁盘层因 `SettingsStorage` 是纯 KV、**没有前缀枚举能力**，用索引键 `ttml:index` 维护 LRU，`MAX_DISK_ENTRIES=15`。
- **图片**（Coil）：唯一已经有完整「查看占用 + 清理」闭环的缓存。`clearImageCache()` 同时清 `CoverSeedCache`——不清的话「清完缓存重新取色」的预期不成立（`PlatformActions.desktop.kt:45`）。历史上这里是 `= true` 的空实现，点了会谎报成功。

---

## 3. 边缘情况矩阵

| # | 场景 | 当前处理 | 评价 |
|---|---|---|---|
| 1 | **不同格式** | 只对无损档位（FLAC 类）落盘；MP3/AAC over HTTP 直接流播（可 seek）。扩展名按 magic bytes 判定，未知 → `.bin` | ✅ 合理。`.bin` 会让引擎少一个解码线索，但内容仍是合法音频，属可接受降级 |
| 2 | **下载失败 / 落盘失败** | 返回 `null` → 保持流播，`isLocalizing=false`，进度条恢复「时长未知」态；不重试、不提示错误 | ✅ 不阻塞播放。⚠️ 用户只会觉得「这歌拖不了」，没有「缓存失败」的显式反馈 |
| 3 | **网络中断（回源抛异常）** | 多 Provider 容灾 → 旧缓存（哪怕超 TTL）→ 原样抛异常 | ✅ 有 stale 兜底。⚠️ `staleServed` 无 UI，用户不知道看到的是旧数据 |
| 4 | **离线播放已缓存无损曲** | ❌ **播不了**。见 §5-P0 | ❌ 真实缺口 |
| 5 | **重复 / 并发请求** | 元数据层**无 single-flight**：并发相同请求会各自 miss、各自回源、各自 store。桌面流层有 `Mutex` 串行 | ⚠️ 首页一屏十几个并发；重入（重组）会放大。丢包可接受，但冷启动首屏存在重复流量 |
| 6 | **不同音质** | 双重隔离：API 键含 `level` 参数；流缓存键含 `@level`。切音质不会串音 | ✅ 正确 |
| 7 | **音质降级** | 播放失败 → `retryWithStandardQuality()` 用 `standard` 重试一次，且降级后 `cacheKey` 跟着变（`:1092`），不会命中另一音质的字节 | ✅ 正确。⚠️ 降级不记忆：下一首仍按用户档位重试 |
| 8 | **多 Provider 降级** | `tryFallback()` 按其它 Provider 的 `apiMap` 映射同一方法重试，成功结果**写回当前 Provider 的键** | ✅ 行为正确（后续同请求可命中）。⚠️ 数据来源与键的 provider 不一致，诊断时容易困惑 |
| 9 | **TTL 过期** | 5 分钟内命中直接返回；超期仍可用，但每次都会回源；**没有 hard expiry / max-stale 上限** | ⚠️ 旧缓存理论上可无限期被 stale 服务（受进程生命周期约束） |
| 10 | **写后失效** | `finally` 前缀失效；登出清全表 | ✅ 正确 |
| 11 | **账号隔离** | cookie 哈希进键；登出全清 | ✅ 正确，且有测试钉住（`CachedMusicApiServiceTest` `不同账号不共用缓存`） |
| 12 | **LRU 淘汰 vs 正在播放** | 淘汰可能删掉正在从本地播放的文件 → `switchToLocal()` 捕到失败后退回流播并复位状态（`:597`） | ✅ 已被显式兜住 |
| 13 | **磁盘满 / 无权限（桌面）** | 捕获异常返回 `null` → 退回流播 | ✅ |
| 14 | **`Content-Length` 缺失（chunked）** | `expected = -1` 时**不校验长度**，直接接受为完整文件 | ⚠️ 传输提前中断的 chunked 响应会被当成完整缓存（后续表现「播到一半没了」） |
| 15 | **磁盘缓存跨重启** | 流缓存保留；元数据缓存清零 → 重启后首屏必然全量回源 | ⚠️ 设计如此（`ApiCache` KDoc 写了「平台可提供持久化 actual」，但**全仓只有 `InMemoryApiCache`**） |
| 16 | **缓存键不匹配（流式路径）** | `callApiCached` 的 `cookie` 由调用方传入、默认 `null` → 落 `anon` 键；读透路径用真实 cookie | ⚠️ 调用方漏传 cookie 会让同一份数据占两个键（`:386` 注释已警告，但无运行时防护） |

---

## 4. 当前「数据管理」界面现状

唯一的存储管理入口是 **设置 → 下载与存储**（`SettingsSubScreens.kt:193`，`StorageSettingsModel`）：

- **下载区**：已下载条数 + 总字节（取媒体库登记值，**不扫盘**，避免卡页面）；下载目录（桌面可改 / 可「打开目录」）。
- **缓存区**：**只有图片缓存**——显示 Coil 磁盘占用 + 「清理图片缓存」按钮，反馈带「释放了多少」（清理前后各读一次）。

也就是说：**A / B / C 三类缓存，用户既看不到、也管不了**。而 B（无损流缓存，上限 2 GiB）恰恰是磁盘占用最大的一块，A 则是导致「数据不更新」这类观感问题的元凶。`StorageSettingsModel` 的 KDoc 自己就承认过这个问题（「初版只有改目录 + 清缓存两个动作，用户看不到任何数字——存储管理名不副实」），现在补完了下载与图片，**剩下三块还没补**。

---

## 5. 发现的缺口与风险

### P0 — 离线无法播放已缓存的无损曲（功能性）

`PlaybackControllerImpl.loadCurrent()` 的取流顺序是「**先 `getSongUrl`，后 `cachedPath`**」（`:981` → `:1008`）。离线时 `getSongUrl` 失败会**提前 return 并设置错误「无法获取播放地址」**，根本走不到本地副本那一步。

后果：`stream-cache/` 里明明躺着一条完整的无损文件，但
- 应用重启后（内存里的播放地址没了）；
- 或距上次取址超过 5 分钟 TTL；

时**离线完全播不了**，而用户对「缓存」的直觉预期恰恰是「缓存了就该能离线听」。

**建议**：把 `cachedPath(cacheKey)` 的查询提到 `getSongUrl` **之前**；命中本地完整副本时优先直接播本地，网络取址可延后（甚至跳过）。这样既修好离线，也让「缓存命中」不再依赖一次额外的网络往返——顺带缩短热路径。

### P1 — 缓存完全不可观测 / 不可管理 —— **已基本解决（2026-10-03）**

原先：`ApiCache.clear()` / `size()` 除 `logout()` 外无调用点；`CacheStats` 无 UI 消费；
`StreamLocalizer` 只有 `isLocalizing` / `cachedPath` / `localize`；`AmllTtmlClient`
只有 `clearMemo()`。

现在：无损流缓存已有完整的**查看 / 搜索 / 删除 / 清理**（存储页 + `SongCacheScreen`），
接口缓存有**条数 / 命中率 / 清理**。

**仍未覆盖**：AMLL 歌词缓存（C）依然既看不到也清不掉 —— 它是四类缓存里唯一剩下的盲区。

### P2 — 其它

- 无 single-flight（§3-5）。
- chunked 响应不校验长度（§3-14）。
- stale 服务无上限、无提示（§3-9）。
- 元数据缓存不持久化（§3-15）；`ApiCache` KDoc 承诺的平台持久化 actual 不存在。
- `isCacheable` 白名单是硬编码 `when`，无运行时开关、无可观测清单。

---

## 6. 数据管理界面：可新增的功能

### 6.1 总体思路

把「下载与存储」升级为 **「数据与存储」**，按「**是不是用户资产**」分两大组，每组卡片统一「**数字 → 动作 → 说明**」三段式（沿用现有 `SettingsSection` / `SettingsClickItem` / `SettingsButtonItem`）：

```
【我的下载】（用户资产，删除需二次确认）
  已下载音乐   ·  N 首 · 共 X GB        → 进入下载管理页
  下载目录     ·  路径 / 打开目录

【缓存】（派生数据，可安全清理）
  无损流缓存   ·  N 首 · 共 X MB / 上限 2 GB
  接口缓存     ·  N 条 · 命中率 xx% · 有 M 条已过期
  歌词缓存     ·  N 条（AMLL TTML）
  图片缓存     ·  共 X MB            ← 已有
  ─────────────────────────────
  清理全部缓存（保留我的下载）
```

顶部再放一条**总占用条**（可点击展开明细），让「磁盘去哪了」一屏可见。

### 6.2 能力清单

| 能力 | 目标对象 | 用途 | 预期效果 | 落地所需改动 |
|---|---|---|---|---|
| **查看** | 全部 | 让「磁盘去哪了」可回答 | 用户能自己判断该清什么，减少「播放器越来越占空间」的困惑 | `StreamLocalizer.stats()`；`ApiCache.size()`；`AmllTtmlClient.diskStats()` |
| **查看明细 / 搜索** | 无损流缓存 | 按歌名或 `mediaId` 找到某首歌的缓存条目 | 定位「这首怎么播得不顺」；支持按音质筛（同曲可能有多档） | `DesktopStreamLocalizer` 需落一个 **sidecar 索引**（`data/.index.json`：`key → {标题, 歌手, 音质, 字节, 最后访问}`）。文件名是哈希化的，**没有索引就无法显示歌名** |
| **查看命中率 / 趋势** | 接口缓存 | 命中率低 = TTL 太短或白名单漏配；`staleServed` 高 = 上游在故障 | 让「缓存没生效」这类隐形问题可被用户/开发者发现 | 消费已有的 `backend.cachedApi.stats`（无需新增采集） |
| **编辑：容量上限** | 无损流缓存 | 无损单曲 30–100 MB，2 GiB 只够十几首；笔记本用户可能想调到 512 MB | 让用户按磁盘预算自行取舍 | `DesktopStreamLocalizer.maxCacheBytes` 提为可变 + 持久化到 `SettingsStorage` |
| **编辑：保留时长 / 上限** | 接口缓存 | 想「更实时」→ 调小 TTL；想「更省流量」→ 调大 | 一个开关同时解决「数据不更新」与「流量/延迟」两类相反诉求 | TTL 提为可变 + 持久化（当前 `CacheConfig` 是构造期注入的 `data class`） |
| **编辑：缓存总开关** | 接口缓存 | 排查「数据不更新」时一键旁路缓存，不改变其它设置 | 把「是不是缓存的锅」从猜测变成一次点击 | 同上（`enableCache` 已是字段） |
| **编辑：无损落盘开关** | 流缓存 | 桌面用户若不在乎 seek，可彻底关掉落盘，不再写盘 | 磁盘零增量；代价是无损不可拖（有明确文案可解释） | `StreamLocalizer.isLocalizing()` 接一个设置 |
| **编辑：歌词持久化开关** | 歌词缓存 | 不想要任何非下载类落盘的用户 | 满足「最小化落盘」诉求 | `AmllTtmlClient` 的 `diskCache` 传 null |
| **删除：单条** | 流缓存 / 接口缓存 | 某首歌缓存损坏（播一半没了）→ 只删它重下 | 精准修复，不用清空整盘 | `StreamLocalizer.remove(cacheKey)`；`ApiCache.remove(key)`（已有，需暴露条目列表） |
| **删除：按前缀** | 接口缓存 | 换个音源后清掉旧音源的残留 | 释放内存、避免跨音源困惑 | `ApiCache.removeByPrefix()`（已有） |
| **清理：一键清全部缓存** | 全部 | 出问题时先清缓存排除法 | 与「清理图片缓存」一致的反馈（带释放量） | 新增 `StreamCacheAdmin` 聚合各家 `clear()` |
| **清理：清理 N 天未访问** | 流缓存 | 温和释放，不误伤最近在听的 | 比「一键清空」体感友好得多 | `lastModified` 已在维护，直接按阈值筛 |
| **清理：校验完整性** | 流缓存 | 扫描并删除截断 / 0 字节 / magic bytes 不符的文件 | 修掉「播到一半没了」类的脏缓存 | 复用 `DesktopStreamLocalizer.extensionFor()` 的嗅探逻辑 |
| **诊断：缓存键查看器** | 接口缓存（高级/诊断页） | 输入 method + params，看到它会命中哪个键 | 排查「同份数据占两个键」「改了没失效」这类只有键可见的问题 | 复用 `cacheKey()`；放在已有 `HealthScreen` 一类的诊断页更合适 |

### 6.3 落地状态（2026-10-03 更新）

**已完成 —— 第一期（只读展示 + 删除 + 清理）**

| 能力 | 落点 |
|---|---|
| 歌曲缓存占用 / 条数 / 上限 | `StorageSettingsScreen` 新增「歌曲缓存」分区 |
| 逐首查看、按歌名/歌手/音质/文件名搜索 | 新增 `SongCacheScreen`（`CpRouteScaffold` + `SettingsLazyPage`） |
| 删除单条 | 行尾删除按钮（删除失败会如实提示「文件可能正在播放」） |
| 清理 30 天未播放 | 存储页动作行 |
| 清空全部（二次确认） | 存储页 `SettingsConfirmItem` |
| 打开缓存目录 | 存储页（桌面） |
| 接口缓存条数 / 命中率 / 清理 | 存储页新增「接口缓存」分区 |
| **容量上限可调** | 存储页「容量上限」分段行（512 MB / 1 GB / 2 GB / 4 GB） |
| 上游接口 | `StreamLocalizer` 的管理面（`stats` / `entries` / `remove` / `clear` / `clearOlderThan` / `setCapacityBytes` / `cacheDirPath`，**全部带默认实现**）+ `MusicBackend.songCache` / `apiCacheStats` / `apiCacheSize()` / `clearApiCache()` |
| 元信息落盘 | 旁车索引 `stream-cache/index.json`（`StreamCacheIndex`），纯附加、不改既有哈希文件名规则 |
| 测试 | `core` `SongCacheManagementTest` 12 例；`app` `SongCacheUiStateTest` 18 例 |

**为什么容量档位做成离散分段而不是滑条**：容量是个「够用就好」的粗粒度决定
（无损单曲几十~上百 MB），滑条会让人以为要精确到 MB，还得额外解释「一首歌多大」。
`SONG_CACHE_CAPACITY_OPTIONS` 与 `songCacheCapacityIndex()` 放在 model 层（`internal`），
因为它们是**数据**且需要能被单测直接验 —— 设置页文件里的 `private` 顶层函数测不到。
退档逻辑刻意取「**不超过当前值的最大档**」而不是兜底第一档：后者会让界面谎报一个更小的上限。

**待做 —— 第二期其余**

- TTL（`CacheConfig.freshTtlMs`）与缓存总开关 `enableCache` 提为可变 + 持久化。
  需要把 `CacheConfig` 从构造期注入的 `data class` 改成可运行时读取的配置源。
- 无损落盘总开关（`StreamLocalizer.isLocalizing()` 接设置）。
- AMLL 歌词磁盘持久化的开关。**注意它当前连清理入口都没有**，见下。**待做 —— 第三期（条目级精修）**

- 缓存完整性校验（复用 `DesktopStreamLocalizer.extensionFor()` 的 magic bytes 嗅探）。
- 单条「重新下载」/「固定不淘汰（pin）」。
- **AMLL 歌词缓存的 `diskStats()` / `clearDisk()`**：这一层目前仍是完全不可见、不可管的，
  `AmllTtmlClient` 只有 `clearMemo()`（仅内存）。`SettingsStorage` 无前缀枚举能力，
  但它自己用 `ttml:index` 索引键维护 LRU，复用它即可统计与清理。
- 缓存键查看器（诊断页）。

### 6.5 两个必须坚持的呈现原则

1. **「我的下载」与「缓存」绝不混排**，一键清缓存必须显式写明「已下载的歌曲不受影响」
   （已落到每个清理动作的副标题与确认文案里）。
2. **清理动作必须给数字反馈**（释放了多少、删了几条）——沿用 `clearImageCache()` 的
   「清理前后各读一次」做法。静默成功会让用户怀疑按钮没生效。

### 6.6 一个被出图纠正过的细节

缓存为空时，两个清理动作原先写成 `enabled = entries > 0`。离屏出图后发现：
`SettingsConfirmItem` 即使在 `enabled = false` 时**仍然铺 `errorContainer`**，
于是一行「看起来能点、点了没反应」的红色按钮挂在页面中间。已改为
**没有缓存时整对不渲染**，并把分区行数从写死的 4 改成按分支算出来 ——
`index/total` 是按分段卡片的首/末段算圆角的，写死的 `total` 在分支之下必然错。
（接口缓存那一行则刻意**不**禁用：它是非破坏性动作，空缓存时点一下得到一句
「本来就是空的」比一个没反应的灰行更有交代。）

---

## 7. 附：接口现状（已实现）

```kotlin
// core/.../playback/StreamLocalizer.kt —— 管理面全部带默认实现（安卓/测试替身零成本）
data class SongCacheMeta(val title: String? = null, val artist: String? = null)
data class SongCacheEntry(id, mediaId?, qualityLevel?, title?, artist?, bytes, lastAccessMs) {
    fun matches(keyword: String): Boolean
}
data class SongCacheStats(entries, bytes, capacityBytes) {  // capacityBytes == 0 ⇒ 平台不落盘
    val supported: Boolean
    val usedRatio: Float
}
fun stats(): SongCacheStats
fun entries(): List<SongCacheEntry>
fun remove(id: String): Boolean
fun clear(): Int
fun clearOlderThan(olderThanMs: Long): Int
fun setCapacityBytes(bytes: Long)
fun cacheDirPath(): String?
suspend fun localize(url, cacheKey, headers, meta: SongCacheMeta? = null): String?   // 新增 meta 形参

// core/.../MusicBackend.kt
val songCache: StreamLocalizer
val apiCacheStats: StateFlow<CacheStats>
fun apiCacheSize(): Int
fun clearApiCache(): Int

// core/src/jvmMain/.../playback/StreamCacheIndex.kt（internal）
//   stream-cache/index.json：{ capacityBytes, entries: { fileName: {mediaId, qualityLevel, title, artist} } }
//   纯附加文件；老缓存没有记录时 title 为 null，界面显示「未知曲目」，删除不受影响
```

> `StreamLocalizer` 是 `core` 的接口，按仓库约定**新增成员必须带默认实现**，
> 且默认值不能写成 `get() = MutableSharedFlow()` 这类每次新建对象的写法。
> 这里默认值取 `capacityBytes = 0`（= 不支持），管理 UI 据此整块隐藏 ——
> 判据来自 core 而不是在界面里重写一遍平台判断，将来安卓真加了实现会自动生效。
