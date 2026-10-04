# 听歌习惯页 · 局域同步 · 无缝转移播放设备 —— 方案

> 三件事一次说清，但它们不是三块拼图，而是**一条数据链的三种用法**：
>
> | 能力 | 用到的同一条链 |
> |---|---|
> | 听歌习惯页 | **本地事件流**（play log）→ 聚合 → 可视化 |
> | 局域同步 | 同一条事件流 + 歌单/收藏/设置 → **跨设备交换** |
> | 无缝转移 | 同一条链上的**实时会话**（当前曲 + 队列 + 进度）→ 交给对端 |
>
> 所以方案的核心是**先定一条 append-only 事件日志**（op log），三个功能都从它长出来。
> 地基已经存在一半：Ktor CIO 本地服务端、`/stream` 字节直通、令牌闸门、`/api/v1/*` 对外契约。
> 本方案**不新建服务端**，只在其上加一层「同步面」。

---

## 0. 结论摘要

| 项 | 决策 | 理由 |
|---|---|---|
| 发现机制 | **UDP 组播信标 + QR/手动兜底**（不引 mDNS 依赖） | 两端零依赖、Android 与 JVM 代码同构；JVM 侧引 jmdns 要处理多网卡，Android 侧是 NsdManager，两套 API 不对称 |
| 传输通道 | **复用现有 `LocalServer`（Ktor CIO）**，新增 `/api/v1/sync/*` 命名空间 | 端口、令牌、绑定地址、生命周期全部已有；新起服务等于再造一套闸门，必然漂移 |
| 同步协议 | **append-only op log + HLC 逻辑时钟 + 游标增量** | 幂等、可交换、天然去重；避免「谁覆盖谁」的 CRDT 复杂度 |
| 信任模型 | **一次性配对**（6 位 PIN 或 QR）→ 每对等体独立密钥 | 局域网裸奔不可接受；复用 `isTokenSatisfied` 的三分支纪律 |
| 无缝转移 | **近无缝 session handoff**：源端**继续播**到目标 `ready` 才交接 | 「零间隙」需要目标预缓冲，只在目标已登录同音源时成立；如实降级 |
| Windows 待机 | 阶段一**托盘常驻主进程**（零额外进程）；阶段二可选**极简 agent** | 只有「主应用已退出还要被唤醒」才需要 agent；JVM 做不到「非常小」，见 §4.3 |
| Android 待机 | **不复刻常驻**：靠已有 `PlaybackMediaSessionService` + 事件驱动短窗口 | 能杀你的是 Ams/Doze 的**调度策略**，与进程用什么语言写**无关**；改写成 JNI 原生进程一点忙都帮不上（见 §4.3.1） |
| 习惯页日历墙 | **宽屏 53×7 年视图 / 窄屏月历视图**，共享同一份 `DailyAgg` | GitHub 贡献墙的语义（一天一格、颜色 = 强度）直接适配听歌时长；但 53 列在 360dp 手机上放不下，必须换布局而不是硬塞 |

---

## 1. 现状盘点：能复用什么，缺什么

### 1.1 已有的地基（**不要重造**）

| 能力 | 位置 | 对本方案的作用 |
|---|---|---|
| 本地 HTTP 服务（Ktor CIO，Android/Desktop 共用） | `core/.../control/LocalServer.kt` + `jvmMain/control/LocalServer.jvm.kt` | 直接作为同步与转移的传输层 |
| 字节直通流 `/stream?mediaId=…` | 同上 | **转移时目标拉流**，凭据留在源端，绝不下发 |
| 令牌三分支闸门 | `control/LocalServerConfig.isTokenSatisfied` | 同步面必须走同一个函数，不另写一份 |
| 对外契约与路由表 `/api/v1/*` | `core/.../integration/IntegrationRoutes.kt`、`IntegrationDto.kt` | 新增 `/api/v1/sync/*` 与它同级，共用错误码体系 |
| 描述符文件（地址+令牌落盘） | `integration/IntegrationDescriptor.kt` | 配对后写对等体信息可沿用同一形态 |
| 出向推送 | `control/ExternalPusher.kt`（`/api/health`、`/api/v1/play-url`…） | 转移时的 `offer/commit` 可参考其重试与回退策略 |
| 播放状态快照 | `playback/PlaybackUiState`、`PlaybackController` | 转移要搬运的全部字段都在这里 |
| 最近播放记录 | `AppModel.startHistoryRecorder()` / `recentTracksFlow` | 习惯页的**起点**，但它只记「曲目变更」，不记时长 |
| QR 生成 | `ui/screen/QrCodeGen.kt`（qrose） | 配对二维码零新增依赖 |
| 键值持久化 | `core/.../util/SettingsStorage.kt` | 设置项与游标 |
| 文件读写 | `core/.../util/PlatformSupport.kt`（`readTextFile`/`writeTextFile`） | op log 与统计落盘 |
| Android 前台服务 | `app-android/.../AndroidManifest.xml` → `PlaybackMediaSessionService`（`stopWithTask=false`） | 播放中保活，Android 侧待机的现成载体 |
| 「我的」页 Bento 仪表盘 | `ui/screen/LibraryScreen.kt`（`BentoStatCard("聆听统计")`、`BentoActionCard("最近播放")`） | 习惯页的入口与版式语言 |

### 1.2 缺口（本方案要补的）

1. **没有时长采集**：`recordRecentTrack` 只在 `track.id != lastRecordedId` 时记一条，`playedMs` 恒为 0 —— 无法回答「今天听了多久」。
2. **没有时间序列存储**：`SettingsStorage` 是 properties 键值，塞进按天聚合的数据会撑爆（全量回写）。
3. **没有任何设备发现代码**（全仓 `mdns|Multicast|NsdManager|DatagramSocket` 零命中）。
4. **没有同步协议**：`/api/v1/*` 只有「拉别人」的只读用例，没有「两端对等交换」。
5. **`IntegrationService` 边界纪律**：只能依赖 `UnifiedMusicSource`/`PlaybackUiState`/`LocalServerConfig`，同步层的 DTO 必须同样与音源实现解耦（有 `IntegrationBoundaryTest` 钉住）。
6. **Windows 没有托盘/后台常驻**（`app/src/desktopMain` 无 `Tray` 命中），关窗即退。

---

## 2. Part A — 听歌习惯页

### 2.1 信息架构：融合「我的」与「最近播放」

现状是**三个彼此不相干的入口**：我的页的「聆听统计」卡只是个数字行；「最近播放」是独立页面；两者都在桌面侧栏各占一项。

方案：新增一个 **`InsightsScreen`（听歌报告）**，把它做成「我的」的**延伸页**而非新 tab —— 理由：`MAIN_TABS` 是会话级单例（改 tab 列表会牵动 `MainScreen` 的三处返回链判据），而习惯页是「低频深看」，更符合 push 进内容层栈。

```
「我的」(LibraryScreen)
├─ 仪表盘
│   ├─ 聆听统计卡  ──[点]──▶ InsightsScreen（默认落在「概览」）   ← 原来是死数字，现在可点
│   └─ 最近播放卡  ──[点]──▶ InsightsScreen（落在「最近」tab）    ← 原来 push RecentPlaysScreen
└─ 曲库（歌单 / 下载）
```

`InsightsScreen` 内部分段：

| tab | 内容 | 数据来源 |
|---|---|---|
| **概览** | 累计/今日/本周时长、连续打卡、总计曲数 | 日聚合表 |
| **习惯** | 24h×7d 热力图、音源分布、完播率、跳过率、新歌发现数、Top 歌手/歌曲/专辑 | 日聚合 + Top-N 物化 |
| **最近** | 现有 `RecentPlaysScreen` 正文（直接复用其 composable） | `AppModel.recentTracksFlow` |

**融合关键点**：`RecentPlaysScreen` 已经支持 `embedded` 参数（`HomeScreen.kt:2404`）。把它的正文抽成一个 `RecentPlaysContent()`（不画标题栏、不做 `DesktopRouteTitle`），「最近」tab 与桌面 `DesktopPane.RecentPlays` 都指向它 —— 一份正文两条入口，不会漂。

桌面侧栏：把「最近播放」一项改为「听歌报告」，`DesktopPane.RecentPlays` 改名 `DesktopPane.Insights`（含 `initialTab` 参数），返回链三处判据（`Main.kt` Esc / `App.kt` / `MainScreen` 消费）同步更新。

### 2.2 数据采集：`ListeningStatsRecorder`

现在的 `startHistoryRecorder()` 语义是「曲目变更」；新增一个**独立的**采集器，不动它（改它会把「最近播放」的语义一起带偏）。

```
订阅 PlaybackUiState →
  维护一个「播放会话」: (mediaId, provider, startWallClock, accumMs, lastTickWallClock, lastPositionMs)
  每次状态到达:
    · isPlaying == true  → accumMs += clamp(wallClock - lastTick, 0, MAX_TICK)
    · isPlaying == false → 关闭会话并落盘
    · mediaId 变化        → 关闭旧会话，开新会话
  关闭会话时判定:
    completed = accumMs >= 0.9 * durationMs 或 positionMs 触达尾部
    skipped   = 关闭原因 == 曲目变更 且 accumMs < 0.3 * durationMs
```

要点（都是踩过的坑的形状）：

- **用墙钟增量而不是 position 增量**：`seekTo` 会让 position 跳变，用 position 差会把一次快进算成几小时收听。同时 `clamp` 单次 tick 上限（如 5s），进程被挂起后恢复不会一次灌进一大段。
- **`accumMs` 只在 `isPlaying` 时累加**：缓冲 / 暂停 / 熄屏暂停都不算收听时长，与主流平台口径一致。
- **关会话要幂等**：`mediaId` 变化、暂停、进程退出三条路径都可能关同一个会话，用 `closed` 标记防重复落盘。
- **`isLocalizing` 不算异常**：边播边落盘时仍在出声，正常累计。

### 2.3 存储：分月 JSONL + 日聚合快照

```
<dataDir>/insights/
  plays-2026-10.jsonl      # 追加写，每行一条 PlayRecord（原始事件，永久保留）
  daily.json               # 日聚合（{date → DailyAgg}），读时校验 mtime + 行数，落后则重算
  top.json                 # Top-N 物化（周/月滚动），可丢弃重算
```

```kotlin
@Serializable data class PlayRecord(
    val id: String,            // uuid，去重用（同步时也是主键）
    val deviceId: String,      // 哪台设备播的
    val mediaId: String,       // provider://song/xxx
    val provider: String,
    val startedAt: Long,       // epoch ms
    val playedMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val skipped: Boolean,
    val sourceId: String? = null,   // 歌单 / 日推 / 搜索 / 手动（若有）
)

@Serializable data class DailyAgg(
    val date: String,              // yyyy-MM-dd（按本地时区切日）
    val playedMs: Long,
    val playCount: Int,
    val uniqueTracks: Int,
    val newTracks: Int,            // 当日首次出现的曲目
    val hourBuckets: List<Long>,   // 24 个桶
    val weekday: Int,
)
```

- **按本地时区切日**：切日函数必须走 `cp.player.core.util.localDateTimeOf(ms)`（`kotlinx.datetime` 运行时解析到 0.7.x 会 `NoClassDefFoundError`，包在 `runCatching` 里会静默退化成空 —— 这条已有前车之鉴）。
- **JSONL 追加**：`PlatformSupport.readTextFile/writeTextFile` 是全量读写，所以要用「按月分片 + 只重写当月」的方式，避免整文件回写越写越慢。写入失败**不能**打断播放（`runCatching` 包住，但要像 `HealthScreen` 那样**记录**，不能静默）。
- **保留策略**：原始 JSONL 永久保留（体积小，一年按 2 万首 × 200B ≈ 4MB）；`top.json` 可随时重算。

### 2.4 聚合与指标口径（写死在文档里，避免各处自己算）

| 指标 | 口径 |
|---|---|
| 累计时长 | Σ `playedMs`，全部设备（同步后含其他设备） |
| 今日/本周 | 按 `startedAt` 的**本地日期**归集；周一起算 |
| 连续打卡 | 从今天往前，`playedMs >= 5min` 记为「有听」的连续天数 |
| 最长连续 | 历史最长连续段（与「当前连续」分开两个数，别混） |
| 日历墙分档 | 分位阈值取**最近 90 天非零日**的 P25/P50/P75/P90，共 5 档（0 单独一档）；阈值**只算一次**并缓存，不要每次重排 |
| 完播率 | `completed` 条数 / 总条数 |
| 跳过率 | `skipped` 条数 / 总条数 |
| 新歌发现 | 该 `mediaId` 在**整个历史**中首次出现的当日计 1 |
| 作息热力图 | `hourBuckets[7][24]`，单元格 = 该时段的 `playedMs` |
| Top 歌手/歌曲/专辑 | 按 `playedMs` 排序（不是次数 —— 次数会让 30 秒跳过的小曲刷榜） |

### 2.5 主页版式：听歌日历墙（对齐 GitHub 贡献图）

「概览」tab 的视觉主体就是一面**听歌日历墙** —— 一天一格、颜色深浅 = 当天听歌时长，
与 GitHub 贡献图同一个信息语法。它比环形图更适合这件事：**它天然表达「连续」和「习惯」**，
而那正是习惯页想回答的问题（「我最近是不是断了」「我周末听得多还是工作日多」）。

#### 2.5.1 数据映射

| GitHub 贡献图 | 听歌日历墙 |
|---|---|
| 一格 = 一天 | 一列 = 一天（同） |
| 颜色 = 提交次数 | 颜色 = **当天累计 `playedMs`** |
| 行 = 周几（默认周日起） | 行 = 周几，**默认周一起**（中文习惯；`weekStart` 做成参数，两端一致） |
| 列 = 一年 53 周 | 列 = 近期 53 周（跨年连续，不按自然年截断） |
| tooltip「N contributions on …」 | 点/悬停 → 「10月3日 · 3 小时 12 分 · 42 首」+ 当日 Top 3 歌手 |

**颜色分档（关键：不要硬编码 GitHub 绿）**

GitHub 的绿是它的品牌色，本项目主题是跟随封面取色的 Material You。所以：

- **默认**用 **`primary` 的 5 档**（0 / 1-4 档），由 `MaterialTheme.colorScheme` 派生，
  用户换封面配色时日历墙跟着变 —— 否则它会成为全应用唯一「不跟着变」的地方（设置页图标早就因此改过一轮）。
- 分档阈值**按分位数而不是绝对值**：取最近 90 天非零日的 P25 / P50 / P75 作为 1/2/3 档边界，
  4 档 = 超过 P90。理由：每天听 20 分钟的人和每天听 6 小时的人，用固定的绝对阈值会一个全是最深色、一个全是浅色，日历墙就废了。
- 提供「GitHub 绿」作为**可选配色**（设置项 `insights_heatmap_palette`：跟随主题 / 经典绿），
  满足「我就要那个绿」的用户，但不作为默认。

**零值格必须可见**：`0 分钟` 的格子不是「透明」而是**最浅的一档容器色**
（浅色主题下是 `surfaceContainerHighest` 这类浅灰）。留白会让日历墙在大屏上散成一片，
也会让「这天没听」和「这天不存在」分不清。

#### 2.5.2 响应式：宽屏 53 列，窄屏换月历

这是最容易做砸的地方 —— **53 列 × 7 行放不进手机**：

```
53 列 × (10dp 格 + 3dp 缝) ≈ 690dp   >  360dp 手机屏宽
```

硬塞只有两个结果：格子小到 < 6dp（点不中、看不清），或横向滚动（用户永远只看到局部，失去「一眼看全年」的价值）。

方案：**两种布局，一份数据**。

| 形态 | 断点 | 布局 |
|---|---|---|
| **年视图（GitHub 墙）** | 宽屏 ≥ `CpBreakpoints.medium` | 53 列 × 7 行，整年一屏；右侧留 Top 歌手/连续天数小卡 |
| **月视图（月历热力图）** | 手机 | 一个月一屏：7 列 × 5–6 行的日历网格，可左右翻月；语义完全一致，只是切分粒度不同 |

- 两者共用同一个 `DailyAgg` 序列与同一套配色分档，**不许各算一套**（否则同一个数字在两端颜色不同）。
- 手机上月视图的**默认落点 = 当月**；顶部给「本月 / 连续 N 天」两个小指标。
- 窄屏**不要**退化成「列表式的最近 N 天」—— 那就丢掉了「习惯」这件事，只剩流水账。

#### 2.5.3 交互

- **悬停（桌面）**：格子放大一档 + 浮出 tooltip（用延迟 300ms 的 hover，避免鼠标扫过时闪一片）。
- **点击**：进入「那一天的记录」——直接复用「最近」tab 的列表组件，按 `startedAt` 过滤当天。
  这让日历墙不是装饰，而是**导航入口**：看到某个深色格子可以直接点进去看那天听了什么。
- **键盘可达**：日历墙必须能 Tab 进入、方向键移动选择、Enter 确认（`Modifier.focusable` + 语义属性），
  否则桌面端它就成了一个只能用鼠标的孤岛。
- 无障碍：每个格子要有 `contentDescription`（「10月3日，3小时12分」），不要只靠颜色传达信息。

#### 2.5.4 其他版式纪律

- 外壳一律 `CpRouteScaffold` + `CpBackButton`；双栏（宽屏「墙 + 侧栏指标」）用 `CpTwoPane`。
- 宽度 `widthIn(max = CpSpacing.pageMaxWidth)` **放在 `fillMaxWidth()` 之前**；间距只取 `CpSpacing`；动效取 `CpMotion`。
- 图表**不引库**（依赖表里没有图表库），在 `ui/component/InsightsCharts.kt` 里用 Compose `Canvas` 自绘：
  `ListeningCalendar`（日历墙，年/月两种布局）、`StatRing`（环形进度）、`HourHeatmap`（24h 作息热力）、`RankBar`（排行条）。
- **深色/浅色都要重测**：浅色主题下低值档很容易和背景糊在一起，深色主题下高值档容易过曝。两套主题各出一张离屏图核对。
- 空态：无任何记录时给 `ContentState`（「还没有收听记录」），**不要画一个全 0 的假日历墙**。
- 改版式必须**离屏渲染出图**核对（编译 + 单测量不到对齐，更量不到配色对比度）。

### 2.6 落地清单（Part A）

- [ ] `core/.../insights/ListeningStats.kt`：`PlayRecord` / `DailyAgg` / 聚合函数（纯函数，可单测）
- [ ] `core/.../insights/InsightsStore.kt`：JSONL 追加 + 日聚合缓存（expect/actual 只到文件层，其余 commonMain）
- [ ] `app/.../AppModel.kt`：`startListeningRecorder()`（新，幂等，与 `startHistoryRecorder` 并列在 `App.kt` 启动块）
- [ ] `app/.../ui/model/InsightsScreenModel.kt`
- [ ] `app/.../ui/screen/InsightsScreen.kt`（三 tab）
- [ ] `app/.../ui/component/InsightsCharts.kt`：优先做 `ListeningCalendar`（年视图 / 月视图两种布局，共享配色分档）
- [ ] 配色分档器 `HeatmapScale`：分位阈值（P25/P50/P75/P90）计算 + 缓存；`insights_heatmap_palette` 设置项（跟随主题 / 经典绿）
- [ ] 日历墙的可达性：`focusable` + 方向键 + `contentDescription`（桌面端 Tab/方向键/Enter 全通）
- [ ] 离屏出图核对：**浅色 / 深色两套** × **年视图 / 月视图两种布局**，共 4 张
- [ ] `RecentPlaysScreen` 正文抽 `RecentPlaysContent()`（「最近」tab 与日历墙点击后复用同一份）
- [ ] `LibraryScreen` 两张卡改为可点跳转；`SettingsRegistry` 不动（不是设置项）
- [ ] `MainScreen` 侧栏与 `DesktopPane` 改名；返回链三处判据同步

---

## 3. Part B — 局域网自动发现 + 数据同步

### 3.1 设备身份

```kotlin
@Serializable data class DeviceIdentity(
    val deviceId: String,     // uuid，首次启动生成并落盘，永不改
    val name: String,         // 主机名 / 用户可改的昵称
    val platform: String,     // "windows" | "android" | "macos" | "linux"
    val appVersion: String,
    val protocolVersion: Int, // 同步协议版本，用于协商
)
```

落盘在 `SettingsStorage`（`device_id` / `device_name`）。**绝不**把账号 uid 或音源 cookie 放进身份 —— 身份要能在未登录时广播。

### 3.2 发现层：UDP 组播信标（推荐）+ QR/手动（兜底）

三者对比：

| 方案 | 跨平台对称性 | 依赖 | 多网卡 | 判定 |
|---|---|---|---|---|
| **UDP 组播信标**（自实现） | 高（同一份 Kotlin，`DatagramSocket`/`MulticastSocket`） | 零 | 需自己枚举网卡逐一发送 | ✅ 采用 |
| mDNS / NSD | 低（JVM jmdns vs Android NsdManager） | 引入依赖 | 由库处理 | ❌ |
| 手动 IP / QR | 高 | 零 | 无（单播） | ✅ 作为兜底与配对载体 |

**信标设计**：

```
组播地址 239.255.72.80 : 38085（CP = 0x43 0x50）
每 3s 广播一次（可用 jitter，避免同时唤醒）:
{ "app":"CPPlayer", "v":1, "deviceId":"…", "name":"…", "platform":"windows",
  "port":8080, "face":"stream+sync", "paired":true, "fingerprint":"ab12…" }
```

- 信标里**不带令牌**，只带 `fingerprint`（配对密钥的短哈希前 8 位），用于对端判断「这个设备我配过、且密钥没换」。
- 收到信标 → 在「设备列表」里注册/刷新 `lastSeen`；超过 15s 未收到 → 标为离线。
- **Windows 防火墙**：首次监听会弹授权框，设置页要给出「如果看不到设备，检查防火墙」的显式引导（这是最容易让用户以为功能坏了的地方）。
- **Android 组播锁**：接收组播需要 `WifiManager.MulticastLock`（否则 Wi-Fi 省电模式丢弃组播包），需在 `androidMain` 申请并持有；这是 Android 侧最容易漏的一步。
- **多网卡**：桌面常有虚拟网卡（WSL / VMware / VPN），必须枚举所有 `isUp && !isLoopback` 的网卡逐个发送，否则「明明同一 Wi-Fi 却搜不到」。
- **兜底**：设备列表提供「手动添加」（输入 `ip:port`）与「扫码配对」（一端显示 QR，内容含 `ip:port` + 一次性配对码）。

### 3.3 配对与信任

```
① A 生成一次性配对码（6 位数字 + 60s 有效期），显示 QR / 数字
② B 输入码 → B 向 A 发 POST /api/v1/pair {code, deviceId, pubkey}
③ A 校验码 → 记录 B 的 deviceId 与 pubkey，返回 A 的 deviceId 与 pubkey
④ 双方各自派生:  peerToken = HKDF(a 或 b, 双方 pubkey 排序, deviceId 对)
⑤ 落盘 pairedPeers[{deviceId, name, lastAddress, peerToken, pairedAt}]
```

- **之后的所有同步请求**都用 `Authorization: Bearer <peerToken>`，走 `decideDataApiGate` 的同一条路径 —— 但判据从「全局 accessToken」改为「**已配对** + peerToken 匹配」。这一点必须**只改一处**：同步面的闸门函数要复用 `isTokenSatisfied` 的三分支精神（配了令牌必须逐字匹配；没配对一律拒绝）。
- **未配对设备的请求**：返回 401，且响应体不透露任何信息（不区分「没配对」和「配对过期」）。
- 设置页给「已配对设备」列表 + 逐个「解除配对」（破坏性操作，走 `CpConfirmHost`）。

### 3.4 传输层：复用 `LocalServer`，新增 `/api/v1/sync/*`

新增路由（与 `IntegrationRoutes` 同级，契约一并写进 `docs/dev/INTEGRATION_API.md`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/v1/sync/handshake` | 交换 `DeviceIdentity` + 能力 + 各日志流的最新游标 |
| `GET` | `/api/v1/sync/ops?since=<cursor>&limit=` | 拉取对端自 `cursor` 之后的操作 |
| `POST` | `/api/v1/sync/ops` | 把自己的操作推给对端 |
| `GET` | `/api/v1/sync/session` | 读对端当前播放会话（转移用，见 Part C） |
| `POST` | `/api/v1/sync/handoff` | 发起设备转移（见 Part C） |

闸门：新增 `LocalServerConfig.exposeSyncApi`（**fail-closed 默认 false**，与 `exposeDataApi` 同性质）。**不复用** `exposeDataApi` —— 数据面是「把歌单搜索借给别人」，同步面是「和对等设备交换私人历史」，暴露面不同，开关就该分开（与 `exposeStream` / `exposeDataApi` 当初拆开的理由一致）。

### 3.5 同步协议：append-only op log

```kotlin
@Serializable data class Op(
    val opId: String,        // uuid，幂等去重的主键
    val deviceId: String,    // 谁产生的
    val hlc: Hlc,            // 混合逻辑时钟：用于 last-write-wins
    val kind: String,        // "track.played" | "favorite.add" | "favorite.remove" | "session.snapshot" | "setting.set"
    val payload: JsonObject,
)
@Serializable data class Hlc(val wallMs: Long, val counter: Int, val deviceId: String)
```

- **每个设备一份本地日志**（追加写 `ops.jsonl`），每条带单调递增 `seq`。交换 = 「给我 seq > N 的」。
- **合并规则**：
  - `track.played` / `favorite.*`：**幂等并集**（按 `opId` 去重，天然可交换，与顺序无关）。
  - `session.snapshot` / `setting.set`：**LWW**，按 `(hlc.wallMs, hlc.counter, deviceId)` 字典序取大；`deviceId` 做平局裁决，保证两端收敛到同一结果。
- **游标**：对每个对等体保存「我方已发的 seq」与「对端已确认的 seq」= `sync_cursor_<peerId>`。
- **时钟**：每次收发 `handshake`/`ops` 时把本机 HLC 与对端取 max（HLC 的 update 规则），保证跨设备因果序。**不信任系统时钟**做唯一依据。

**同步范围（设置项，逐项开关）**：

| 范围 | 默认 | 说明 |
|---|---|---|
| 听歌记录与统计 | **开** | 习惯页跨设备合并的主要价值 |
| 最近播放 | **开** | 与上一条同源，一起开 |
| 收藏 | 关 | 涉及账号态，开启前提示「以最后改动为准」 |
| 播放会话（继续播放） | 开 | 用于跨设备「接着听」 |
| 设置（外观/音质） | 关 | 各端可能想要不同主题 |
| 歌单增删改 | 关 | 冲突面最大，放最后 |
| **音源模块 / 账号凭据** | **永不** | 凭据只在本机，绝不出网卡；这条写进方案不做清单 |

### 3.6 安全边界（对齐既有纪律）

- 同步面**必须**走 `isTokenSatisfied` 的三分支语义；**绝不能**出现「没配对就放行」—— 那正是当初媒体面漏掉过的坑。
- 传输内容**不含**上游签名 URL / cookie / 账号 uid（沿用 `IntegrationService` 的纪律）。
- 给 `core/integration` 加同步用例时，仍只能依赖领域模型，扩展 `IntegrationBoundaryTest` 扫描范围到 `sync/`。
- 明文 HTTP 是接受的（局域网 + 令牌），但**配对阶段**必须校验一次性码，避免「同网段任何人一点就配上」。

### 3.7 落地清单（Part B）

- [ ] `core/.../sync/DeviceIdentity.kt`、`PairingStore.kt`
- [ ] `core/.../sync/Op.kt`、`Hlc.kt`、`OpLogStore.kt`、`SyncEngine.kt`（纯 commonMain，可单测）
- [ ] `core/.../sync/Discovery.kt`（expect）+ `jvmMain`（MulticastSocket）+ `androidMain`（MulticastLock）
- [ ] `core/.../integration/SyncRoutes.kt`（挂进现有 Ktor 引擎）
- [ ] `LocalServerConfig` 增 `exposeSyncApi`（含 `read` / `write` / `capabilities` 三处同步，漏一处会「开关不保存」）
- [ ] `app/.../ui/screen/SyncSettingsScreen.kt` + `SettingsRegistry` 新增条目（`CONNECTIVITY` 组）
- [ ] `SettingsEntry` 关键字：`同步`、`配对`、`设备`、`局域网`、`sync`、`pair`

---

## 4. Part C — 无缝转移播放设备

### 4.1 「无缝」的诚实定义

真正零间隙要求目标端**已预缓冲**。因此分两档：

| 档位 | 条件 | 体验 |
|---|---|---|
| **A 档（近乎无缝）** | 目标端**同音源且已登录** ⇒ 目标自己解析 `mediaId` | 目标后台 ready 后，源端再停；间隙 ≈ 100–300ms 淡出 |
| **B 档（有短暂静音）** | 目标端无该音源 ⇒ 从源端 `/stream` 拉字节 | 需目标先缓冲几百 ms，间隙 ≈ 0.5–2s，期间源端继续播 |

**降级必须自动且可见**：UI 提示「正在把播放交给 PC…」，而不是静默卡一下。

### 4.2 转移时序

```
源端                                        目标端
 │  (发现已在线的目标)                        │
 │── POST /api/v1/sync/handoff {offer} ─────▶│
 │     offer = { mediaId, positionMs,        │  ① 校验 offer（同音源？已配对我？）
 │               queue[], index, repeat,     │  ② 决定 A 档 / B 档
 │               shuffle, quality, volume,   │  ③ prepare：
 │               sourceStreamBase, cursor }  │     A: 自己取 URL + seek(position)
 │                                           │     B: GET 源端 /stream?mediaId=… + seek
 │◀── 202 Accepted {handoffId, stage} ───────│
 │◀── GET /api/v1/sync/session (轮询 state) ─│  ④ 每 250ms 轮询直到 stage=READY
 │                                           │
 │  ⑤ 收到 READY：                           │
 │     淡出 200ms → pause()                  │
 │     （若 B 档：保持 /stream 在线）         │
 │◀── 200 {stage=PLAYING, positionMs} ───────│  ⑥ 目标在该 position 起播
 │                                           │
 │  ⑦ 每 5s 对账 position，偏差 > 1s 则纠正   │
 │  ⑧ 源端进入「已移交」态：显示「正在 PC 上播放」+ [收回]
```

- **源端在 `READY` 之前绝不停播**：这是「不断音」的唯一保证。相反的顺序（先停再让目标起）会有无法掩盖的空窗。
- **收回（ta ke back）** 是同一条协议的反向：目标把当前 position 发回来，源端从该 position 恢复。
- **失败回退**：任何一步超时（如 `READY` 8s 未到）⇒ 源端**恢复播放**，提示「转移失败，已在本机继续」，绝不把用户卡在「两边都不响」。

### 4.3 待机：让对端「找得到」

#### Windows

| 阶段 | 形态 | 说明 |
|---|---|---|
| **阶段一（推荐先做）** | **主进程托盘常驻**：关闭窗口 = 收进托盘，不退出 | 零额外进程、零额外内存。已有播放引擎与 HTTP 服务原地保留，转移即达 |
| **阶段二（可选）** | **极简 agent 进程** `cpplayer-agent` | 仅当「主应用确实退出了，仍希望被唤醒」时才需要 |

关于「占用非常小的进程」的**诚实评估**：

- JVM 的物理下限约 **30–50MB RSS**（`-Xmx24m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1`，单类、无 Compose、无音频、无 Skiko）。这已经是 JVM 能做到的最小待机体量，**不是**「几 MB」。
- 若真要做到 < 10MB，只有**原生实现**（Go/Rust 单二进制）一条路 —— 但它要独立维护一套 UDP 发现 + HTTP 最小实现，且与主应用的协议必须手工保持一致。
- **建议**：阶段一先行（覆盖 90% 场景，用户「关窗口」但程序还在）。阶段二做成**可选安装项**，并如实标注内存占用；agent 的职责**只有三件**：① 收发发现信标；② 持有 `/api/v1/sync/*` 的最小转发；③ 需要真正播放时**拉起主应用**并把 handoff 负载通过命令行/临时文件传入。**agent 不解码音频**。

#### Android

##### 4.3.1 先回答「能不能写个原生 JNI 轻量常驻」

**不能，而且这条路没有收益。** 这里有个常被混为一谈的前提要先拆开：

| | 是什么 | 谁决定它活不活 |
|---|---|---|
| **JNI（`.so`）** | 一段 native 代码，**加载进 app 进程**，与 Java 侧共享同一个 `oomAdj` | `ActivityManagerService` + LMK。**杀进程时它跟着一起死** |
| **独立 native 可执行文件** | `Runtime.exec()` 从 app 数据目录拉起 | 仍是 app 的**子进程**，`oomAdj` 继承自父进程；且 Android 10+ 的沙箱/SELinux 对 app 目录执行另有约束 |

结论：**Android 的后台限制是「调度与进程管理」问题，不是「语言或体积」问题。**
一个 2MB 的 native 守护进程和一个 200MB 的 JVM，在 LMK 眼里是同一种东西 —— 都是「后台 app 进程」，
按同一套 `oomAdj` 优先级被杀。**把待机进程改写成 JNI，保活能力一点不变，只是白写一遍**（还要多维护一套 C/C++ 构建链）。

native 在 Android 上真正的价值只有两条，**都不解决保活**：

1. **省电** —— 把 UDP 发现/心跳放进 native 线程，减少 JVM 唤醒与 GC 抖动（边际收益，不值得为首版做）；
2. **省内存** —— 仅当进程**已经活着**时才有意义。

> 反过来，native 在 **Windows 侧**才有决定性价值：那里「进程要小」是真实痛点，
> 且没有 Ams 按 `oomAdj` 在后面杀你（见上方 Windows 小节）。

##### 4.3.2 保活方案全家福（诚实分级）

| 方案 | 合规 | 可靠性 | 评估 |
|---|---|---|---|
| **FGS `mediaPlayback`** | ✅ | 高 | **已在用**（`PlaybackMediaSessionService`，`stopWithTask=false`）。播放中天然可达 |
| **FGS `dataSync`** | ✅ | 中 | Android 14 起有**每日时长上限**（15 起更严，约 6h/天）；必须挂常驻通知。适合「临时开一段」，不适合 7×24 |
| **电池优化白名单**（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`） | ✅ | 中 | **已有设置项**。能减少 Doze 掐断，但只是「放宽」不是「豁免」，国产 ROM 另有自己一套 |
| **精确闹钟** `setExactAndAllowWhileIdle` | ✅ | 中 | 用于**事件驱动窗口**：每 N 分钟醒一次查设备表，醒完即睡。这是「不常驻也能被找到」的正解 |
| **`WorkManager` 周期任务** | ✅ | 低 | 最小 15 分钟且会被批量延迟 —— 只能兜底，做不了实时转移 |
| **厂商推送**（FCM / 华为 / 小米…） | ✅ | 高 | 真正合规的「随时叫醒」，但要接各家 SDK + 账号体系。**本方案不做**（见 §7） |
| 双进程互拉（native 守护 + `fork` 拉起） | ❌ | — | **违反 Google Play 政策**，国产 ROM 针对性封杀。明确不做 |
| 1px 透明 Activity / 后台放无声音频 | ❌ | — | **违反政策，会下架**。明确不做 |

##### 4.3.3 所以正解是换方向，不是找更强的保活

- **Android 定位成「发起方」**，不是「待机接收方」。用户拿起手机 → App 在前台 → 发现 PC 在线 →
  主动把播放推给 PC。**这条路径完全不需要保活**，因为发起时 App 本就是前台活跃状态。
- **播放中**已经是可达接收方（`mediaPlayback` FGS 在跑），PC 推过来没问题 —— 覆盖了「手机上正听着，换到电脑」这个主场景。
- **短窗口兜底**：退到后台后的 1–2 分钟内继续保持信标监听（此时还没进 Doze，代价接近零），
  覆盖「刚锁屏就想转移」这个次高频场景。
- **PC → Android 的冷推送**（App 完全退出、锁屏很久）首版**不做**。若用户坚决要，
  唯一诚实的做法是显式开启 `dataSync` FGS，并在设置项里**写明代价**（常驻通知 + 耗电 + 每日时长上限），
  而不是偷偷常驻。

### 4.4 交互入口

- 播放页 / 小播放器：一个「播放设备」按钮 → 底部弹层列出在线设备（含本机），点击即转移。
- 桌面标题栏 / Android 顶栏：一个设备图标，在线设备数 > 1 时淡显。
- 可选自动规则（默认关）：`同 Wi-Fi 且本机暂停 ≥ 30s 且有其他设备在线` → 提示「是否在 PC 上继续？」（**提示而非自动**，避免误转移）。

### 4.5 落地清单（Part C）

- [ ] `core/.../sync/Hlc.kt` 上的 `SessionSnapshot` DTO 与合并规则
- [ ] `/api/v1/sync/session` + `/api/v1/sync/handoff` 路由与用例
- [ ] 目标端 prepare 策略（A/B 档自动判定）
- [ ] 源端「移交态」状态机 + `READY` 超时回退
- [ ] `ui/component/DevicePickerSheet.kt`
- [ ] 桌面托盘常驻（`app/desktopMain`：`Tray` + 单实例锁 + 关闭改为隐藏）
- [ ] Android「退后台 1–2 分钟短窗口监听」（不常驻、不申请新权限，代价接近零）
- [ ] Android 冷推送（`dataSync` FGS）**列为独立可选开关**，设置项内如实写明「常驻通知 + 耗电 + 每日时长上限」
- [ ] （可选）`cpplayer-agent` 独立 launcher + jpackage 配置（**Windows 侧才考虑**；Android 侧不做 native 守护进程）

---

## 5. 分期与验收

| 期 | 内容 | 验收标准 |
|---|---|---|
| **P0** | op log + 本地采集 + 习惯页（无同步、无转移） | 播放 3 首（含 1 首跳过、1 首完整）后，概览/习惯两页数字与手算一致；离屏出图核对版式；`skipped="0"` 的单测覆盖聚合口径 |
| **P1** | 发现 + 配对 + 同步（记录/最近/收藏） | 两端配对后，A 播的曲目在 B 的习惯页出现；重复同步幂等（连跑两次记录数不变）；未配对请求 401 |
| **P2** | 无缝转移（A 档：同音源） | 手机 → PC 转移，间隙 < 300ms；转移失败自动回到本机播放 |
| **P3** | B 档转移（源端拉流）+ 桌面托盘常驻 + 自动规则 | PC 未登录音源时也能接；关窗口后仍可被转移；8s 超时回退可复现 |

---

## 6. 风险与已知坑（写进动手前的必读）

1. **`commonMain` 整源集编译** —— 新增文件报错时看**第一个**报错的文件，别「谁报错谁错」。
2. **给 `core` 接口加成员必须带默认实现**，且默认值**不能**写成 `get() = MutableSharedFlow()`（每次读都新建，订阅方永远收不到）；用模块级单例。
3. **`kotlinx.datetime` 运行时是 0.7.x** —— 切日/时段计算一律走 `cp.player.core.util.localDateTimeOf(ms)`。
4. **Android 无 `com.sun.net.httpserver`** —— 服务端继续用 Ktor CIO。
5. **令牌规则只能有一处** —— 同步面复用 `isTokenSatisfied`；出现第二份实现必然漂移成「局域网裸奔」。
6. **`LocalServerConfigStore.write` 漏写新键** = 「设置不保存」的经典症状；`exposeSyncApi` 要在 `read`/`write`/`capabilities` 三处同时加。
7. **`IntegrationBoundaryTest`** 要扩到 `sync/` 目录，否则契约靠约定守不住。
8. **构建竞态**：有应用在 `desktopRun` 时不要对 app/core 跑标准编译（运行中的 JVM 会 `NoClassDefFoundError`）；用会话专属 `--project-cache-dir` + Groovy init script 重定向 `buildDirectory`。
9. **提交纪律**：一次性 `git commit --only -F msg.txt -- <路径>`，不用 `add` 再 `commit`（并行会话多）。
10. **防火墙 / 组播锁 / 多网卡** 是「搜不到设备」的三个真凶，设置页要给出可操作的排查引导，而不是只说「请检查网络」。

---

## 7. 明确不做（边界）

- ❌ **不同步账号凭据 / 音源 cookie** —— 凭据不出本机网卡。
- ❌ **不做云端中继**（跨网络场景）—— 本方案只覆盖同一局域网；跨网需要另行设计（引入服务器与端到端加密，是另一个量级的工程）。
- ❌ **不做账号体系下的多用户同步** —— 以「设备」为单位，不以「账号」为单位。
- ❌ **不引 mDNS / 图表 / sync 框架依赖** —— 保持依赖表干净，全部自实现且可单测。
- ❌ **不用任何违反 Play 政策的保活手段** —— 双进程互拉、1px 透明 Activity、后台放无声音频一律不做。
- ❌ **不在 Android 侧写 native 守护进程** —— JNI 与 app 共享 `oomAdj`，对保活零收益（§4.3.1）。
- ❌ **首版不做 PC → Android 冷推送通道** —— 需要 FCM 级通道与账号体系；要做也只作为用户显式开启、代价写明的可选项。
- ❌ **Android 不默认常驻后台** —— 与省电目标冲突，只作为用户显式开启的可选项。

---

## 8. 实施状态（第一轮落地）

### 8.1 已完成并验证

| 部分 | 文件 | 验证 |
|---|---|---|
| 领域模型 + 纯聚合 | `core/.../insights/ListeningStats.kt` | 28 项单测（`tests="28" skipped="0" failures="0"`） |
| 追加型 JSONL 存储 | `core/.../insights/InsightsStore.kt` | 同上（含坏行跳过、跨月分片、清空） |
| `PlatformSupport` 补两个原语 | `appendTextFile` / `listChildFiles`（expect + jvm actual） | 编译 |
| 采集器 + 派生状态 | `AppModel.startListeningRecorder()` 等 | 编译 |
| 日历墙 + 月视图 + 图例 | `ui/component/ListeningCalendar.kt` | 离屏出图（浅/深 × 年/月 + 经典绿，共 6 张） |
| 习惯页（概览/习惯/最近） | `ui/screen/InsightsScreen.kt` | 同上 |
| 入口接线 | `LibraryScreen` 两张卡可点跳转 | 编译 |
| **激进保活设置项** | `ui/screen/StandbySettingsScreen.kt` + `PlatformActions` 三条 + Manifest 两条权限 | 编译 |

**要补的测试缺口**：`ListeningStatsRecorder` 的状态机（换曲 / 暂停 / 退出三条关会话路径）
目前只有 `Insights.classify` 的纯函数测试，**没有覆盖采集器本身** ——
它依赖 `PlaybackController.state`，需要一个可控的假的 state 源才能测。
这是下一轮第一件事。

### 8.2 日历墙落地时发现并修掉的两个真实缺陷

这两个都**只有离屏出图能发现**（编译与单测都量不到）：

1. **指标卡文案被硬裁**。三列并排时窄屏每张约 110dp，「2 小时 41 分」被裁成「2 小时」——
   裁掉的恰好是分钟，看起来像统计漏了。修法：新增 `formatDurationCompact()`
   （超 1 小时只给 0.1 小时精度），并给 `Text` 补 `overflow = Ellipsis` 兜底。
   **教训：同一个时长在「明细行」和「指标卡」需要两套格式**，一套走天下必然在窄屏失守。
2. **图例第 0 档格子在浅色主题下几乎不可见**。它取 `surfaceContainerHighest`，
   贴在页面背景上时与背景同色 —— 图例读起来像「少」和第一个色块之间断了一格。
   修法：图例色块统一描 `outlineVariant` 细边（墙上的格子成片出现，不受影响）。

### 8.3 一个跨平台的真 bug（被存储层单测抓到）

`InsightsStore` 原本用 `substringAfterLast('/')` 从绝对路径里取文件名。
**Windows 上 `listChildFiles` 返回的是反斜杠路径** ⇒ 一次都匹配不到 ⇒
`startsWith("plays-")` 恒为 false ⇒ **所有分片被静默过滤，历史记录永远读出来是空**。
桌面端的主目标正是 Windows，这个坑一漏就是「习惯页永远是空的」。
修法：`fileNameOf()` 同时处理 `/` 与 `\`。**判据由测试钉住**，不是靠人记得。

### 8.4 激进保活的落地形态

设置项落在「设置 → 连接与集成 → 激进保活」（`androidOnly = true`，
`SettingsEntry` 为此新增了 `androidOnly` 字段 —— 原来只有 `desktopOnly`，
而这一项恰好是**只对 Android 有意义**）。

开启后 Android 侧持 **Wi-Fi 高性能锁 + 组播锁**：

- 不是为了「不被杀」（那做不到，见 §4.3.1），而是为了**熄屏后 Wi-Fi 不进入省电模式** ——
  否则组播发现包会被系统直接丢弃，症状是「手机就在旁边却搜不到」；
- 锁档位用 `WIFI_MODE_FULL_LOW_LATENCY`（API 29+，为实时流媒体保留的档位），
  低版本回退 `WIFI_MODE_FULL`；
- 锁对象是**模块级单例**且 `setReferenceCounted(false)`：`applyAggressiveStandby`
  会在「启动恢复」和「用户切换开关」两处被调用，每次新建锁会让上一把永久泄漏；
- 设置页显示的是**实际持锁状态**（`isAggressiveStandbyActive()`）而不是回显开关值 ——
  持锁可能因权限被拒而失败，只回显开关会让用户以为保护已生效。

Manifest 新增两条 **normal 权限**（自动授予、不弹窗）：
`ACCESS_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE`。

**桌面端是空操作且恒返回 false**，设置页因此如实显示「本平台不适用」而不是假装已生效。

### 8.5 已知取舍与下一步

| 项 | 现状 | 下一步 |
|---|---|---|
| 宽屏日历墙两侧留白 | 53 列封顶 16dp ⇒ 墙宽约 1004dp，在 1360dp 内容区里居中，两侧各留 ~180dp | 按 §2.5.2 把右侧让给「常听歌手」小卡（`CpTwoPane`） |
| 桌面侧栏仍是「最近播放」 | 未改名，它仍打开旧的 `DesktopPane.RecentPlays` | 改名「听歌报告」并指向本页（要同步 `MainScreen` 三处返回链判据） |
| 采集器状态机无测试 | 只有 `classify` 的纯函数测试 | 用可控假 state 源补测三条关会话路径 |
| 未提交 | 见下方「提交受阻」 | 待并发会话落定 |

### 8.6 ⚠️ 提交受阻（不是代码问题）

第一轮落地时工作区里**同时有 3 个会话在改**：

- 本会话：习惯页 + 日历墙 + 保活设置项；
- 另一会话：`core/.../listentogether/`（untracked，期间一直编译不过）；
- 第三会话：快捷键功能（`AppModel.kt` 的 `matchShortcut` / `MainScreen.kt` / `DesktopShell.kt`）。

`AppModel.kt` 是**共享文件**，工作区里同时含两方的改动。按仓库纪律
（「提交一律显式列路径，禁用 `git add -A`」）不能把整个文件提交 ——
那会连带带走对方尚未完成的工作。因此本轮**未提交**，
改动全部留在工作区，并在仓库外做了双份备份（patch + 新文件 tgz）。
待那些会话各自提交后，补一条 `git commit --only -F msg.txt -- <路径>` 即可。

---

## 附：一句话记住三个功能的连接点

> **播放时写一条 `track.played` 到 op log** ——
> 习惯页读它本地聚合、同步面把它复制到别的设备、转移则把「当前会话快照」也塞进同一条日志。
> 一条日志，三种用法。
