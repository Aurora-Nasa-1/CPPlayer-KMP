# 代码评审报告 — 导航 / 业务逻辑 / 后端

- 审查时间：2026-10-03
- 基线：`HEAD = ad263e2`（最近两天提交 2e10bb4..HEAD，153 文件 / +14091 行）+ 工作区未提交改动
- 范围：最近两天新增与修改的代码，重点为**导航**、**业务逻辑**、**后端/平台集成**
- 说明：以下每条均已回到源码逐条核对；未在源码中确认的推测已显式标注。

---

## 0. 结论速览

| 级别 | 数量 | 代表问题 |
|---|---|---|
| P1（阻断/高危） | 5 | 内嵌 Navigator 悬空引用；切音源后歌单详情播错歌；首页刷新竞态；容灾回退阻塞 UI 线程；导入失败残留模块目录；JNI 日志泄露 cookie |
| P2（明显/隐患） | 20+ | 返回键优先级、SearchScreen 无外壳、吞取消异常、屏幕模型泄漏、模块无完整性校验等 |
| P3（轻微） | 3 | 默认实现语义、未使用 import、客户端未关闭 |

未发现 P0 级缺陷（无确定性崩溃、无远程代码执行、本地服务器默认绑回环且令牌规则 fail-closed）。

---

## 1. 导航层

### N1（P1）内嵌 Navigator 引用在宽/窄分支切换后悬空

- **位置**：`app/src/commonMain/kotlin/cp/player/app/ui/screen/MainScreen.kt`
  - 声明与消费：`:169`（`var contentNavigator by remember { mutableStateOf<Navigator?>(null) }`）、`:195`（`selectTab` 内 `contentNavigator?.popUntilRoot()`）、`:253-254`（`contentNavSize`）、`:280-284`（`backRequested` 消费 `nav.pop()`）
  - 赋值：`:422-425` 内嵌 `Navigator(contentRootScreen) { nav -> SideEffect { contentNavigator = nav } }`
  - 分支：`:367` `if (expanded) { … Navigator … }`，`:621` `} else { …窄屏… }`
- **关键代码**
  ```kotlin
  Navigator(contentRootScreen) { nav ->
      // 捕获内嵌 Navigator 引用（见上）—— 供返回链 / 指令消费 /
      // pageCanGoBack 发布使用。Navigator 常驻组合，此引用全程新鲜。
      androidx.compose.runtime.SideEffect { contentNavigator = nav }
  ```
- **成因**：`contentNavigator` 只由 `SideEffect` 赋值，**没有任何 `DisposableEffect` / `onDispose` 在离开组合时置回 `null`**。内嵌 Navigator 只存在于 `expanded`（≥840dp）分支，桌面把窗口拖到 <840dp（本仓最小窗口 900 物理像素，125% 缩放即约 720dp，可达）时整支被 dispose，但引用仍指向已销毁对象。注释「Navigator 常驻组合，此引用全程新鲜」与实际不符。
- **影响**：
  1. 窄分支下点底栏切 tab → `selectTab` 对已 dispose 的 Navigator 调 `popUntilRoot()`（未包 `runCatching`）；对已销毁栈的操作至少产生**幽灵返回键**（`contentNavSize` 读到旧值 2 → `pageCanGoBack=true`，标题栏出现一个点不动的返回键），最坏情况抛异常。
  2. 窄分支点标题栏返回 → `:281` 对已销毁栈 `nav.pop()`。
- **修复**
  ```kotlin
  Navigator(contentRootScreen) { nav ->
      androidx.compose.runtime.DisposableEffect(nav) {
          contentNavigator = nav
          onDispose { if (contentNavigator === nav) contentNavigator = null }
      }
      ...
  }
  ```
  并把 `:195` / `:281` 对 `contentNavigator` 的操作统一改走 `popOrNotify()` 风格（或至少 `runCatching`），与仓库既有的「静默失败兜一层」约定一致。

### N2（P2）`DesktopBackDispatcher.hasHandlers` 被当成快照状态，注释自相矛盾

- **位置**：`app/src/desktopMain/kotlin/cp/player/app/Main.kt:225-230`（`canGoBack = navigator.size > 1 || DesktopShell.pageCanGoBack || DesktopBackDispatcher.hasHandlers`，注释写「它是快照状态，注册/注销会触发重组」）对比 `app/src/desktopMain/kotlin/cp/player/app/platform/DesktopBackDispatcher.kt:27-32`（「⚠️ 它不是 `mutableStateOf`，读它**不会**驱动重组」）。
- **成因**：`hasHandlers` 读的是普通 `ArrayDeque`，`register/unregister` 不触发任何快照失效。`titleBar` lambda 只读 `navigator.size` 与 `DesktopShell.pageCanGoBack`；仅注册一个 `BackHandler`（如展开播放页 `MainScreen.kt:318`）时这两个值都不变 → `canGoBack` 不重算。
- **影响**：桌面端**展开播放页**（根栈仍为 1、无面板）时，Esc 能收起，但窗口标题栏**不显示返回键**——正是注释声称要消除的「同一动作两条链路漂移」。
- **修复**：把「是否有处理器」做成可观察状态。例如 `DesktopBackDispatcher` 内加 `private var revision by mutableStateOf(0)`，`register/unregister` 自增，`hasHandlers` 读 `revision`；或标题栏改用 `snapshotFlow` 订阅。

### N3（P2）`SearchScreen` 未纳入路由页外壳统一，作为内容区路由页时无标题/无返回

- **位置**：`app/src/commonMain/kotlin/cp/player/app/ui/screen/SearchScreen.kt:69-96`（`class SearchScreen` 的 `Content()` 直接 `Column`，全程无 `Scaffold` / `CpRouteScaffold` / `DesktopRouteTitle`）；调用点 `HomeScreen.kt:230/263/264`（`navigator.push(SearchScreen(...))`）。
- **成因**：`f54a7d6`（10-01）把 10 余个路由页统一到 `CpRouteScaffold`，`SearchScreen` 漏网。它被 push 进内容区内嵌 Navigator（宽屏）后，`contentNavSize>1` 使壳层把顶栏 `hide` 置真（`MainScreen.kt:391`），而窗口标题回落 `DesktopShell.pageTitle`，SearchScreen 未声明标题 → 标题停在**「首页」**。宽平板（无 chrome）下则整页无顶栏、无可见返回。
- **修复**：给 `SearchScreen` 套 `CpRouteScaffold(title = "搜索")`（或至少 `DesktopRouteTitle("搜索")`），与其它路由页一致。

### N4（P2）展开播放页的返回优先级低于同屏面板/路由页

- **位置**：`MainScreen.kt:316-320`（`BackHandler(enabled = isPlayerExpanded)` 在 `Content` 顶部，先注册）；`MainScreen.kt:421-504`（内嵌 Navigator 与面板 `AnimatedContent` 在其后组合）；排序规则见 `DesktopBackDispatcher.kt:10`（**后注册优先**）。
- **成因**：播放页处理器最早注册，任何面板/内嵌路由页的处理器都排在它之后。
- **影响**：宽屏同时开着面板（如歌单详情多选）并展开小播放器时，Esc 会先退出被播放页盖住的那些层级，而不是收起最上层的播放页，与视觉层级相反。
- **修复**：把播放页的 `BackHandler` 移进播放页覆盖层自身组合，或按「当前可见层级」统一排序。

### N5（P2）宽屏平板下「消息 / 设置」入口在顶栏与侧栏重复且语义不同

- **位置**：`MainScreen.kt:708-738`（`AppTopBar.actions` 始终含消息/账号/设置，消息走 `navigator.push(MessagesScreen())` 整页）；`:989-991`（侧栏 `showMessagesEntry/showSettingsEntry = !chromeActive`）；`:1061-1072/1100-1107`（侧栏「消息」走 `onOpenMessages` → 开面板）。
- **成因**：顶栏只在 `LocalWindowChromeActive || hide` 时提前 return（`:691`）。宽屏平板 `chromeActive=false` → 顶栏渲染，而侧栏判据同为 `!chromeActive` → 两套入口并存，且同名「消息」一处整页 push、一处开面板。
- **修复**：侧栏与顶栏用同一 `chromeActive` 判据去重，并统一「消息」落点。

### N6（P2）`TabContent` 每帧测量所有已访问 tab

- **位置**：`MainScreen.kt:850-879`（`Layout(...) { measurables, constraints -> val placeables = measurables.map { it.measure(constraints) } ... }`）。
- **成因**：为保留滚动状态，全部 `visitedTabs` 保持组合（正确），但 `Layout` 对**所有** measurables 全量 `measure`，未放置页也被测。
- **影响**：3 个 tab 各带长列表时切页期间做 3 倍测量，长列表有掉帧风险。
- **修复**：只测量「选中页 + 仍在淡出的页」，其余 `Modifier.layout` 返回 0 尺寸或用 `SubcomposeLayout` 懒测量。

### N7（P2）消息面板关闭后小播放器让位复位滞后

- **位置**：`MessagesPane.kt:74-77`（`DisposableEffect(Unit) { onDispose { onChatOpenChanged(false) } }`）；`MainScreen.kt:451-456`（面板切换走 `AnimatedContent` fadeOut 160ms）；`:635`（小播放器门控 `!messagesChatOpen`）。
- **影响**：收起消息面板后，小播放器约 160ms 后才重新出现（轻微闪动）。
- **修复**：切面板的指令里同步 `messagesChatOpen = false`，或改用 `DisposableEffect(selected)`。

### N8（P2）pop 过渡期窗口标题短暂显示旧页标题

- **位置**：`app/src/commonMain/kotlin/cp/player/app/ui/component/DesktopShell.kt:105`（`routeTitleClaims.lastOrNull()?.title`）、`:129-136`（`DesktopRouteTitle` 的 `DisposableEffect` 入栈/出栈）。
- **成因**：声明栈按**组合先后**排序，pop 时上页要到退场结束才 `onDispose` 移除 → 过渡期仍取到旧标题。
- **修复**：按页面在 Navigator 中的实际层级排序，或 pop 时立即移除当前页声明。

### N9（P3）未使用 import

- **位置**：`MainScreen.kt:34`（`IconButton`，仅注释提及）、`:82`（`LinearEasing`，仅注释提及）。仅编译告警。

---

## 2. 业务逻辑层

### B1（P1）歌单详情页无音源代际失效，切源后按新 Provider 解释旧 id → 播错歌

- **位置**：`app/src/commonMain/kotlin/cp/player/app/ui/model/PlaylistDetailScreenModel.kt:259-262`（`mediaIds()` 播放时才取 `AppModel.activeProviderId()` 拼 `"$provider://song/${it.id}"`），`:84-133`（`load()` 只看 `loadedPlaylistId`，无 `sourceGeneration` 订阅）。
- **对照正确实现**：`HomeScreenModel.kt:185-190`、`LibraryScreenModel.kt:53-58` 均订阅了 `AppModel.sourceGeneration`。
- **成因**：曲目列表是「拉取那一刻的 provider」的数据，`it.id` 是裸 id；播放时却用「当时活跃 provider」拼接。整个模型不订阅代际，切源后不重载。
- **影响**：用户在歌单详情页 → 设置切音源 → 返回点播放，旧 provider 的数字 id 被当作新 provider 的 id → 可能播成无关歌曲，或全部解析失败提示「无法获取播放地址」。`PlaylistDetailScreen.kt:256/262/398/404`（加入队列 / 下一首播放）同样受影响。
- **修复**：`init` 订阅 `AppModel.sourceGeneration.drop(1)`，命中后 `loadedPlaylistId = null; autoPlayedPlaylistId = null` 并按当前 summary 重拉（或 pop 回上一级）；更彻底的做法是列表创建时记录 `providerId`，`mediaIds()` 用它而非 `activeProviderId()`。

### B2（P1）刷新无世代/并发防护，旧音源慢请求覆盖新音源结果

- **位置**：`HomeScreenModel.kt:219-239`（`refresh()` 每次新起协程、不取消旧协程，`_state.value = next.copy(...)` 无世代校验）；同类 `LibraryScreenModel.kt:67-96`。
- **成因**：`sourceGeneration` 触发的 `refresh(force=true)` 与用户下拉 / init 在途的 `refresh()` 并发，谁后到谁覆盖。
- **影响**：切源瞬间若有未完成的旧源刷新，首页/曲库被旧音源内容覆盖；叠加 `activeProviderId()` 拼旧 id（`LibraryScreenModel.kt:161`）= 旧列表 + 新 provider → 播错歌。
- **修复**：`private var refreshJob: Job?` + `private var refreshGen = 0`；进入时 `refreshJob?.cancel()`，`val gen = ++refreshGen`，写回前 `if (gen != refreshGen) return`；或改 `collectLatest`。

### B3（P2）`LibraryScreenModel.refresh` 在 IO 线程做 read-modify-write

- **位置**：`LibraryScreenModel.kt:73-91`（整个读→`copy`→写过程在 `withContext(Dispatchers.IO)` 内，最后赋值也在 IO）；同文件 `:34-35` 的 `selectPlaylist/selectTab` 在 Main 写。
- **影响**：刷新在途时点 tab/选歌单，选中态可能被刷新结果覆盖回退。
- **修复**：IO 只返回结果，回到 Main 用 `_state.update { }` 合并（照抄 `HomeScreenModel.selectPlaylistSource:267-272`）。

### B4（P2）批量取曲目详情：单个畸形 id 令整批失败 + 吞异常/取消

- **位置**：`core/src/commonMain/kotlin/cp/player/core/music/UnifiedMusicSourceImpl.kt:95`（`mediaIds.map { parseId(it) ?: return BackendResult.Error("Invalid mediaId in batch") }`）、`:134-136`（`catch (e: Exception)` 吞掉一切，含 `CancellationException`）、`:140`（全批失败仍 `return BackendResult.Success(summaries)`）。
- **影响**：队列混入一个畸形 mediaId（旧数据/本地路径/前缀变体）→ 全批拿不到任何元信息 → 队列条目停在「加载中…」且**无任何错误提示**（`PlaybackControllerImpl.resolveQueueInBackground` 走 `getOrNull() ?: emptyList()`）；取消异常被吞还会破坏结构化并发。
- **修复**：跳过畸形 id 而非整批失败；`catch (e: CancellationException) { throw e }` 前置；完全失败时返回 `Error` 而非空 `Success`。

### B5（P2）AMLL 取词吞掉 `CancellationException`，切歌不中断反而再发一次请求

- **位置**：`core/src/commonMain/kotlin/cp/player/core/api/AmllTtmlClient.kt:241-243`（`catch (_: Exception) { return null }`）；消费方 `PlaybackControllerImpl.kt:838`（`lyricsJob?.cancel()`）。
- **影响**：快速切歌时被取消的歌词协程当「网络失败」继续走搜索回退**再发一次请求**，浪费带宽、可能触发限流，取消被延迟。
- **修复**：`catch (e: CancellationException) { throw e }` 放在 `catch (_: Exception)` 之前。同类模式需排查 `MusicRepository.getArtistProfile`、`HomeScreenModel.safe`（`runCatching` 会吞取消）。

### B6（P2）TTML 单词行被误判为行级，逐词时间丢失

- **位置**：`core/src/commonMain/kotlin/cp/player/core/playback/TtmlParser.kt:128-135`（`if (lineWords.size == 1) lineWords = emptyList()`，**不比较 span 时间与行时间**）。
- **影响**：真实卡拉 OK 里只有一个词的短行/尾音行退化为整行高亮；`hasWordLevel` 与 UI 渲染对该行不自洽。现有测试只覆盖 3 词情形。
- **修复**：仅当 `span.begin == lineBegin && span.end == lineEnd`（覆盖整行）才判为行级。

### B7（P2）AMLL 搜索缓存键不含歌手，同名曲命中错误歌词；磁盘索引读改写无同步

- **位置**：`AmllTtmlClient.kt:172`（`val searchKey = "s:$platform:$songId:${name.lowercase()}"`，本地曲 `platform/songId` 为 null → 恒 `"s:null:null:<歌名>"`）、`:280-294`（`diskPut` 对 `INDEX_KEY` 读-改-写，无锁）。
- **影响**：不同歌手同名曲共用 key → 错误歌词跨会话落盘；并发 `diskPut` 互相覆盖索引 → 条目丢失或数据键泄漏（超出 `MAX_DISK_ENTRIES=15`）。
- **修复**：key 纳入 `artist`（及 `album`）；`diskPut` 用 `synchronized` 串行化（负缓存 `notFound` 的读路径同样需同步或改 `ConcurrentHashMap.newKeySet()`）。

### B8（P2）用 `remember {}` 创建 Voyager `ScreenModel` → `screenModelScope` 泄漏

- **位置**：`MainScreen.kt:974`（`remember { HomeScreenModel(loadDiscovery = false) }`）、`LibraryScreen.kt:122`、`PlayerScreen.kt:921`、`MessagesPane.kt:62-63`。
- **成因**：Voyager 的 `screenModelScope` 只在 `rememberScreenModel` 离开组合时取消；`remember{}` 的 `onDispose` 永不触发。
- **影响**：侧栏 `HomeScreenModel` 每次宽窄切换重建并新起 `sourceGeneration.collect`（`:185-190`）→ 僵尸收集器累积，每次切源触发多份 refresh/网络；其余模型离开组合后 in-flight 请求与 scope 泄漏。
- **修复**：能注册为 ScreenModel 的位置改用 `rememberScreenModel { }`；非 Screen 处（`MainScreen.kt:974`、`MessagesPane`）用 `DisposableEffect(Unit) { onDispose { model.onDispose() } }` 并在模型内取消自身 scope。

### B9（P2）`selectPlaylistSource` / `selectNewSongRegion` 结果无音源世代校验

- **位置**：`HomeScreenModel.kt:266-272`、`:289-296`（只判「仍选中该来源」，不判音源代际）。
- **影响**：切源瞬间在途的热门/精品歌单、地区新歌结果会写进新会话状态，与 `refresh` 无序竞争，旧数据可能最后落屏。
- **修复**：同 B2，引入世代快照，写回前校验。

### B10（P2）`CommentScreenModel` 加载守卫不足、点赞基于陈旧快照

- **位置**：`app/src/commonMain/kotlin/cp/player/app/ui/model/CommentScreenModel.kt:90-93`（守卫只挡「loading 且有内容」）、`:131-142`（`toggleLike` 的翻转与回滚都基于传入的 `comment`）。
- **影响**：`comments` 为空时重复调用并发加载；快速连点导致点赞计数/态回滚错误。
- **修复**：用 in-flight `Job` 去重；`toggleLike` 改为读 `_state` 中该评论的当前值。

### B11（P2）删除当前曲之前的队列项会从头重播当前曲

- **位置**：`core/src/commonMain/kotlin/cp/player/core/playback/PlaybackControllerImpl.kt:430-433`（`if (index <= _index) playCurrent()` 把 `index < _index` 也覆盖）。
- **成因**：历史遗留（blame 2026-07-19），本轮新增的队列右键/交互让删歌更频繁而更易触发。
- **影响**：删掉一首靠前的歌，正在播的歌从头重放（网络流重下、进度归零）。
- **修复**：仅 `wasCurrent` 为真时才 `playCurrent()`。

### B12（P2）`AppModel.refreshUnreadMessages` 无取消

- **位置**：`app/src/commonMain/kotlin/cp/player/app/AppModel.kt:753-757`（`modelScope.launch` 无 Job 引用/取消）。参照同文件 `refreshUserProfile:709-714` 已有 `profileRefreshJob?.cancel()`。
- **影响**：切号/登出瞬间可能显示上一个账号的未读数。

### B13（P3）`PlaybackController.addNextToQueue` 默认实现语义与文档相反

- **位置**：`core/src/commonMain/kotlin/cp/player/core/playback/PlaybackController.kt:58-60`（默认实现是 `addToQueue(mediaId)` = 追加队尾，文档却承诺「插到当前曲之后」；`Impl:364-402` 才是正确语义）。
- **影响**：未来替代实现/测试替身会静默得到相反行为。**修复**：改 `abstract` 或加 `@Deprecated` + KDoc 警告。

**已核查、无需修改的点**（防止误改）：
- 随机队列置换不变式：`orderIsValid:1183-1191` + `ensureValidOrder:1204-1215` 对 11 处入口统一兜底且幂等不重洗；`addNextToQueue:364-402` 的 `_order`/`_index` 映射与 `insertAt` 位移推演正确，未发现 off-by-one。
- 自动播放幂等（`autoPlayedPlaylistId:75/282-288`）、`repeat` 终止（`computeNext:1146-1162` / `onTrackEnded:1106-1143`）、切歌三重世代守卫（`navigationSeq` / `loadGeneration` / `queueGeneration`）均完整。

---

## 3. 后端 / 网络 / 平台集成层

### K1（P1）容灾回退路径在调用线程上 `runBlocking`，Main 线程可被冻结至 60s

- **位置**：`core/src/commonMain/kotlin/cp/player/core/cache/CachedMusicApiService.kt:492`（`val raw = provider.callApi(mapped, params)`，直连 provider，未切 IO）、`core/src/commonMain/kotlin/cp/player/core/provider/HttpProvider.kt:48`（`runBlocking`）、`core/src/commonMain/kotlin/cp/player/core/api/MusicApiServiceImpl.kt:464`（`callWithAllProviders` 同样直连）、`core/src/jvmMain/kotlin/cp/player/core/provider/BinaryProvider.kt:95`。
- **成因**：唯一会切 IO 的入口是 `ProviderManager.callApi`（`ProviderManager.kt:129` 的 `withContext(Dispatchers.IO)`）；`tryFallback` 与 `callWithAllProviders` 都绕过了它。HTTP 超时 60s。
- **影响**：`CommentScreenModel.loadComments` 等直接在 `screenModelScope`（`Dispatchers.Main.immediate`）里调 `AppModel.api`（= `CachedMusicApiService`），当请求非成功码/抛异常触发容灾时，会在 **UI 线程**同步阻塞最长 60s ⇒ ANR。
- **修复**：`tryFallback` / `callWithAllProviders` 内所有 `provider.callApi(...)` 用 `withContext(Dispatchers.IO) { }` 包裹（JNI 调用同样阻塞）；根治办法是把 `BackendProvider.callApi` 契约异步化为 `suspend`，去掉 `runBlocking`。

### K2（P1）导入失败/异常中断残留的 `temp_*` 目录会被下次启动当作真实模块加载

- **位置**：`core/src/commonMain/kotlin/cp/player/core/provider/ModuleManager.kt:72-76`（`unzipTo` 失败直接 `return false`，**未删 tempDir**）、`:98-101`（`moveDir` 失败也不清理）、`:104-107`（`catch` 分支不清理）、`:54-58`（`scanAndLoadAll` 无条件遍历所有子目录）。
- **成因**：`unzipTo` 在 zip-slip/磁盘满等情形可能已解压出 `manifest.json` 后失败；返回前未清理。`scanAndLoadAll` 只检查 `$dir/manifest.json` 是否存在。
- **影响**：① 临时目录无限累积；② **最严重**：下次 `MusicBackend.init` 把 `temp_*/manifest.json` 当模块加载，`providers[manifest.id] = provider`（`:134`）以相同 id **覆盖真实 Provider**，而 `getModuleDir(id)` 返回 `$modulesDir/$id`（`:151`）→ 删除模块删不掉 temp，产生「幽灵音源」与 id 冲突。
- **修复**：`unzipTo` 失败 / `moveDir` 失败 / `catch` 三个分支都 `PlatformSupport.deleteRecursively(tempDir)`；`scanAndLoadAll` 跳过 `temp_*` 前缀；最好把临时目录放到 `modulesDir` 之外（系统 temp），解压完再 `moveDir` 进来。

### K3（P1）`JniProvider` 把含 cookie 的完整请求 JSON 打到标准输出（Android 即 logcat）

- **位置**：`core/src/jvmMain/kotlin/cp/player/core/provider/JniProvider.kt:77`（`log("callApi -> nativeCallApi: method=$method, json=$json")`）、`:80`（`result.take(200)`）。
- **成因**：`MusicApiServiceImpl` 会把 cookie 注入 `finalParams`（`:44-48`），经 `ProviderManager.callApi` 原样传到 `JniProvider.callApi` 的 `params`，随后被完整 `println`。
- **影响**：会话 cookie（等价账号密码）落到 logcat / 桌面 stdout，可被同机日志工具、崩溃上报或 adb 读取；`result.take(200)` 还可能带出账号/私信片段。
- **修复**：对 `cookie/token/password/md5_password` 等键做掩码（复用 app 层 `CookieLogin.mask`）；`result` 只打印长度或 `code/msg` 摘要；生产构建关闭此类调试日志。

### K4（P2）`ApiFieldContract` 全局回退表把「缺字段」洗白

- **位置**：`core/src/commonMain/kotlin/cp/player/core/api/ApiFieldContract.kt:209-214`（回退判定 `json[it] != null`）、`:184-185`（`FALLBACK_FIELDS` 含 `data/result/playlist/songs/...`）。
- **成因**：kotlinx.serialization 的 `JsonNull` 是**非空对象**，`json["data"] != null` 对 `"data": null` 也为真 → `{"code":200,"data":null}` 能满足 `EXPECTED_FIELDS` 里的**每一个**端点。
- **影响**：诊断页「缺字段」告警被系统性掩盖。以 `pl/count` 为例：契约声明 `msg`，`{"code":200,"data":null}` 被放行；消费端 `SocialRepository.getUnreadCount()` 只认 `data` 为对象或根层 `msg` → 监控判 OK、UI 角标静默显示 0，二者错位。
- **说明**：`login/status` 未登录时 `account/profile` 为 JSON null 属**合法响应**（`ApiFieldContractTest.kt:64-66` 明确钉住），所以「null 算命中」是有意为之；本条是针对**全局回退表**的加固建议，不是回归。
- **修复**：回退判定改为「键存在且值非 `JsonNull`」，且对 `data` 这类容器要求值为对象/数组；`pl/count` 的 `data` 包裹形状收敛到 `ALIASES` 而非全局表；补 `{"code":200,"data":null}` 的用例。

### K5（P2）模块导入无完整性（hash/签名）校验

- **位置**：`ModuleManager.kt:69-109`（全程只做 `manifest.id` 一致性校验 `:85-89`）、`ModuleManifest.kt:5-43`（**无** `sha256`/`signature` 字段）。
- **影响**：损坏或被篡改的模块包（含 jni/binary 类型，会执行原生代码）可被直接导入加载，缺少最起码的完整性门槛。
- **修复**：`ModuleManifest` 增 `sha256`；`importZip` 解压后校验，不匹配即拒绝并清理临时目录；`updateModule` 走 `updateUrl` 时校验服务端 hash。

### K6（P2）解压无体积/条目上限，`moveDir` 非原子

- **位置**：`core/src/jvmMain/kotlin/cp/player/core/util/PlatformSupport.kt:59-83`（`zis.copyTo(fos)` 无界，zip bomb 风险）、`:212-217`（`moveDir` 先删旧再 `renameTo`，Windows 上 rename 失败会丢失已安装模块）。
- **修复**：按 `entry.size` 与累计字节设上限、限条目数；`moveDir` 改为同卷 `Files.move(..., ATOMIC_MOVE)` 或「先改名 `.bak` → 成功再删」，失败可回滚。

### K7（P2）`BinaryProvider` 不消费子进程 stdout，可能写满管道导致子进程阻塞

- **位置**：`core/src/jvmMain/kotlin/cp/player/core/provider/BinaryProvider.kt:70-82`（`redirectErrorStream(true)` 合并到 stdout 但从不读取）。
- **影响**：子进程输出超过 OS 管道缓冲即阻塞在 write → Provider 服务「假死」，`callApi` 超时。
- **修复**：起守护线程持续读取并丢弃（或转发到受限日志），或重定向到文件。

### K8（P2）桌面 SMTC 生命周期竞态 + 封面缓存无上限

- **位置**：`app/src/desktopMain/kotlin/cp/player/app/platform/JmtcMediaControls.desktop.kt:87-118/202-227`（`start()` 在子线程才置 `started`，`stop()` 立即读 → 竞态下走早退分支，既不 `shutdown` executor 也不禁用 JMTC，形成「已 stop 却仍启用」的泄漏）、`app/src/desktopMain/kotlin/cp/player/app/platform/LocalArtwork.desktop.kt:33-49`（`cacheDir.listFiles{ startsWith }` 每推一首全目录扫描，会话内只增不减；`writeBytes` 非原子）。
- **修复**：用 `AtomicBoolean` 表达停止请求，`stop()` 无条件 `shutdown` 并 CAS 复位；封面缓存加 LRU 上限，写入改 `.part` + `ATOMIC_MOVE`（复用 `JmtcMediaControls.downloadCover` 的既有模式）。

### K9（P2）`validateElfHeader` 对「非 ELF / 校验异常」一律放行

- **位置**：`PlatformSupport.kt:172-204`（`:184` 魔数不匹配 `return null`，`:201-203` 异常也 `return null` = 通过）。
- **影响**：坏文件被放行到 `System.load` / `ProcessBuilder`，失败点后移到更难排查处。
- **修复**：非 ELF 且平台要求 ELF 时返回错误描述；异常路径显式提示。

### K10（P2）Media3 会话与 `SharedMedia3Player` 的归属依赖实现细节

- **位置**：`app-android/src/main/kotlin/cp/player/app/PlaybackMediaSessionService.kt:80-99`（`onDestroy` 里 `mediaSession?.release()`）、`:25`（`ControllerForwardingPlayer(SharedMedia3Player.get(this))`）；`core/src/androidMain/kotlin/cp/player/core/playback/PlatformPlayer.android.kt:282-289`（`PlatformPlayer.release()` → `SharedMedia3Player.release()`）；`core/src/commonMain/kotlin/cp/player/core/MusicBackend.kt:735`（`reset()` → `playbackController.release()`）。
- **核实结论**：`MediaSessionImpl.release()` 在 media3 1.4.1/1.10.1 均**不**释放 `Player`（只 `closed=true`、移除 listener、释放 stub），故 `onDestroy` 当前行为安全。**但** `MusicBackend.reset():735` → `PlatformPlayer.release()` → `SharedMedia3Player.release()` 会直接释放会话正握着的单例 ExoPlayer（会话脱钩、通知恒 IDLE）—— 该隐患仍成立（目前只在测试路径触发），与既有记忆一致。
- **修复**：给 `ControllerForwardingPlayer` 重写 `release()` 为 no-op，把「谁释放播放器」显式化；`onDestroy` 中 release 后 `removeSession`（如版本支持）并注释版本依据；`MusicBackend.reset()` 不要在会话存活时释放单例播放器。

### K11（P3）HTTP 客户端从不关闭

- **位置**：`AmllTtmlClient.kt:108`、`HttpProvider.kt:36`、`BinaryProvider.kt:40`（均 `by lazy { createHttpClient() }` 或直接构造，无 `close()`）。
- **影响**：重复创建（测试、Provider 重载）会泄漏连接池/线程。
- **修复**：为持有方提供 `close()` 并接入 `MusicBackend.reset()`，或改进程级共享单例。

---

## 4. 可复用 / 值得推广的模式

1. **会话级 tab 单例 + saveable 列表 + 离屏回归测试**：`MAIN_TABS`（`MainScreen.kt:135-139`）、`visitedTabs` 的 `listSaver`（`:157-162`）配合 `desktopTest/.../navigation/*.kt` 的 5 个逐帧回归，是本次导航改造最扎实的一块。
2. **`popOrNotify` / `pushOrNotify`**（`ui/util/Navigation.kt:45-94`）：用「`size<=1` 预判 + `runCatching`」把静默失败与崩溃收敛为一条明确契约，适合作为全仓唯一返回/前进入口。
3. **三重世代守卫**（`PlaybackControllerImpl.kt:116/175/181` 的 `navigationSeq` / `loadGeneration` / `queueGeneration`）：处理「切歌期间旧回调打新曲」的通用范式。
4. **单点不变式校验**：`ensureValidOrder()`（`:1204-1215`）把 11 处手工维护的随机序收敛为幂等校验，注释解释「绝不重洗」的理由——可复用到任何「多处维护 + 一份派生索引」场景。
5. **契约表与测试成对**：`ApiFieldContract` + `ApiFieldContractTest`（含「ALIASES 键必须在主表」「FALLBACK 不放业务字段」的自洽断言），把「编译期看不出、运行期静默」的字段形状差异钉死。
6. **取消语义正确传播**：`CachedMusicApiService` 在 `:109-110` / `:503-504` 显式先 rethrow `CancellationException`——全仓最规范的一处，可作为其余处（B5/K7 所属文件）的模板。
7. **安全单点**：`LocalServerConfig.isTokenSatisfied`（`LocalServerConfig.kt:254-263`，未配令牌 + 绑 0.0.0.0 = 拒绝且不可关闭）、`PlatformSupport.kt:66-69` 的 zip-slip `canonicalPath` 拦截、`zipDirTo` 失败清理半成品。
8. **平台集成的可诊断性**：`JniProvider.kt:112-128` 用运行时类名推导期望符号；`WindowsSmtcIdentity.ensure()`（`:88-100`）全程 `runCatching` 保证身份注册失败不中断后台线程；`JmtcMediaControls` 单线程 MTA executor + 分档节流；`downloadCover`（`:291-315`）的 `MAX_COVER_BYTES` + `.part` + `ATOMIC_MOVE`。
9. **回归测试质量**：`PlaybackControllerNavigationTest` 用 `ManualDispatcher` 精确复现交错，并用「完整遍历一轮必须覆盖每首恰好一次」验证置换，比肉眼观察随机性可靠得多。

---

## 5. 建议修复顺序

1. **P1 安全/稳定**：K3（cookie 日志）→ K2（幽灵模块）→ K1（ANR）→ N1（悬空 Navigator）→ B1/B2（切源播错歌）。
2. **P2 契约与体验**：B4/B5（吞异常/取消，修复成本低、面广）→ N2/N3（注释声称已修未生效）→ B3/B8/B9（并发与泄漏）→ 其余。
3. **P3 清理**：N9、B13、K11。

同时对既有记忆做两处更正/补充：
- `DesktopBackDispatcher.hasHandlers` 的行为以**实现**为准（非快照状态，不驱动重组），`Main.kt:230` 的注释需修正。
- Media3 会话隐患的准确表述：`MediaSession.release()` 不释放 Player（1.4.1/1.10.1），但 `MusicBackend.reset()` 经 `PlatformPlayer.release()` → `SharedMedia3Player.release()` 仍会释放会话持有的单例。

---

## 6. 修复进展（2026-10-03 09:00）

已按上文顺序落地第一批修复，**17 个文件，+400/-67 行**。验证：
`:core:compileKotlinDesktop` ✅、`:app:compileKotlinDesktop` ✅、
`:core:desktopTest`（311 用例，`skipped=0`，`failures=0`）✅、`:app:compileTestKotlinDesktop` ✅。

| 编号 | 状态 | 落点 |
|---|---|---|
| K1（容灾阻塞 UI 线程） | ✅ 已修 | `CachedMusicApiService.tryFallback` 包 `withContext(IO)` |
| K2（幽灵模块目录） | ✅ 已修 | `ModuleManager` 三处失败分支清理 + 扫描跳过 `temp_*` |
| K3（cookie 进日志） | ✅ 已修 | `JniProvider` 参数脱敏、响应只记长度 |
| K6（zip bomb / 原子替换） | ⚠️ 部分 | `unzipTo` 加双上限；`moveDir` 原子化**未做** |
| K7（子进程 stdout 堵塞） | ✅ 已修 | `BinaryProvider` 守护线程排空 |
| K8（SMTC 竞态 / 封面缓存） | ✅ 已修 | `JmtcMediaControls.stop()` 无条件 shutdown + `stopRequested`；`LocalArtwork` 原子写 + LRU |
| K9（ELF 放行） | ❌ **误报，保留原行为** | 桌面 Windows 模块是 `.exe`，拒绝非 ELF 会打断二进制模块 |
| B1（切源播错歌） | ✅ 已修 | `PlaylistDetailScreenModel` 记 `tracksProviderId` + 世代守卫 + 订阅代际 |
| B2/B3（刷新竞态） | ✅ 已修 | `HomeScreenModel` / `LibraryScreenModel` 的 `refreshJob`+`refreshGen`、IO 线程不再 read-modify-write |
| B4（批量取详情） | ✅ 已修 | 跳过个别畸形 id（全部非法仍 Error，补了回归用例）；取消上抛；全批失败报错 |
| B5/B7（AMLL 取消与缓存键） | ✅ 已修 | 取消先上抛；搜索键补 artist；磁盘索引/负缓存加锁；新增 `close()` |
| B10（评论页） | ✅ 已修 | 加载用 in-flight Job 去重；点赞以当前 state 为准 |
| B13（接口默认实现） | ✅ 已修 | `addNextToQueue` 改抽象 |
| N2（hasHandlers 不可观察） | ✅ 已修 | 改读 `mutableIntStateOf` 计数 |
| N3（SearchScreen 无外壳） | ✅ 已修 | 接入 `CpRouteScaffold`，用栈顶判断区分 tab 根 / 路由页 |

**因并行会话在途占用而未修**（相关文件当时正被另一会话改写，按仓库约定不碰）：
N1（`contentNavigator` 悬空，`MainScreen.kt`）、N4/N5/N6/N7（同在 `MainScreen.kt`）、
K1 的另一半（`MusicApiServiceImpl.callWithAllProviders`）、K4（`ApiFieldContract.kt` 为他人未跟踪文件）、
K11（`close()` 接入 `MusicBackend.reset()`）。这些文件落定后可按上文方案直接照做。

---

## 7. 第二轮修复进展（2026-10-04）

上一轮「因并行会话在途占用而未修」的项，本轮在文件落定后**按 §5 的原方案补齐**。

验证：`:core:compileKotlinDesktop` ✅、`:app:compileKotlinDesktop` ✅（首次编译成功）、
`ApiFieldContractTest`（11 用例，`skipped="0"`，`failures="0"`）✅。

| 编号 | 状态 | 落点 |
|---|---|---|
| N1（内嵌 Navigator 悬空） | ✅ 已修 | `MainScreen` 捕获引用由 `SideEffect` 改 `DisposableEffect`，`onDispose` 仅在仍是自己时置回 `null` |
| N4（播放页返回优先级最低） | ✅ 已修 | 播放页 `BackHandler` 从 `Content` 顶部移到覆盖层内（`SharedTransitionLayout` 之前）⇒ 后注册优先 |
| N5（宽屏平板入口重复） | ✅ 已修 | 侧栏「消息/设置」判据加 `contentNavSize > 1` 门控；`AppTopBar` 增 `onOpenMessages` 回调统一落点 |
| N6（每帧测量所有已访问 tab） | ✅ 已修 | `TabContent` 的 `Layout` 只对「选中页 + 仍在淡出页」用真实约束，其余改零尺寸约束测量 |
| N7（小播放器让位复位滞后） | ✅ 已修 | `desktopPane` 变化时在**同一帧**复位 `messagesChatOpen`（不再等 `AnimatedContent` 淡出 160ms） |
| K1 另一半（`callWithAllProviders`） | ✅ 已修 | `provider.callApi` 包 `withContext(Dispatchers.IO)`；并前置 `catch (CancellationException) { throw }` |
| K4（契约全局回退表洗白） | ✅ 已加固 | `FALLBACK_FIELDS` 命中要求值非 `JsonNull`；主字段 / `ALIASES` 保留 null 容错（`login/status` 合法）；补用例 |
| K6（`moveDir` 非原子） | ✅ 已修 | 先试同卷 `ATOMIC_MOVE`；失败回退「先备份 `.bak` → 替换 → 失败回滚」，不再「先删后移」 |
| K11（HTTP 客户端从不关闭） | ✅ 已修 | `BackendProvider.close()` 默认空实现；`HttpProvider` / `BinaryProvider` 覆写（后者并 destroy 子进程）；`MusicBackend.reset()` 遍历关闭全部 Provider + 关 `amllClient`（已提为 `amllClientLazy` 字段） |

**N5 属产品行为微调**：宽屏平板**根页**不再从侧栏进「消息 / 设置」（由顶栏承担，落点统一为「开面板」）；
只有进入内嵌详情页（顶栏 `hide`）时才轮到侧栏。如需回退，把 `DesktopSidebar` 的
`showMessagesEntry` / `showSettingsEntry` 判据还原为 `!chromeActive` 即可。

**仍未修**：N9（未使用 import）、K9（误报，保留原行为）、K11 之外的 P3 清理项。

**回归测试**：`:app:desktopTest` **123 用例**、`:core:desktopTest` **337 用例**，
均 `skipped="0"`、`failures="0"`（含主壳层 `MainTabScrollRestore` / `TabHostScrollRestore` /
`ScrollStateRestore` / `SidebarPreview` 回归）。

> 过程中一度被**另一会话的在途改动**挡住：`AppModel.kt` 新增 `ListeningSession` 等 + 未跟踪的
> `core/.../insights/` 包，令 `:app:compileKotlinDesktop` 报一串 unresolved。判据是
> `git grep ListeningSession HEAD` 零命中 ⇒ 在途 WIP、与本轮改动无关。其落地后重跑即通过。

---

## 8. 第三轮修复进展（2026-10-04 下午）

把前两轮之后**仍开着的条目**全部处理。验证：`:core`/`:app:compileKotlinDesktop` ✅、
`:core:compileAndroidMain` + `:app:compileAndroidMain` + `:app-android:compileDebugKotlin` ✅、
`TtmlParserTest` **11 用例**（含 2 个新增 B6 回归）`skipped="0" failures="0"` ✅。

| 编号 | 状态 | 落点 |
|---|---|---|
| N9（未使用 import） | ✅ 已修 | `MainScreen.kt` 删 `IconButton` / `LinearEasing`（该文件含 §7 在途改动，随其提交） |
| B12（未读数刷新无取消） | ✅ 已修 | `AppModel.refreshUnreadMessages` 加 `unreadRefreshJob?.cancel()`，同 `profileRefreshJob` 模式 |
| B5 尾巴（runCatching 吞取消） | ✅ 已修 | 新增 `core/util/Catching.kt` 的 `runCatchingExceptCancellation`；`MusicRepository.getArtistProfile` 与 `HomeScreenModel.safe` 两处点名位置改用 |
| B6（TTML 单词行误判） | ✅ 已修 | `TtmlParser` 仅当唯一 span 时间**恰好覆盖整行**才判行级；补两个回归用例（整行覆盖 / 子区间） |
| K5（模块无完整性校验） | ✅ 已修 | `ModuleManifest` 增可空 `sha256` 字段（缺省跳过 = 向后兼容旧包）；`importZip` 对原始 zip 字节重算比对，不匹配拒绝并清理；`PlatformSupport` 增 `sha256Hex`（expect + jvm actual，MessageDigest 流式） |
| K10（Media3 release 归属） | ✅ 已修 | `ControllerForwardingPlayer.release()` no-op（封死误释放单例 ExoPlayer 的路径，注释含 media3 1.4.1/1.11.1 字节码依据）；`MusicBackend.reset()` 加「会话存活勿调」警告 |
| N8（pop 过渡期旧标题） | ⏸ **评估后暂缓** | 影响仅数百毫秒的过渡期标题；修复需在返回链**全部入口**（标题栏 / Esc / 右键 / 页面内 pop）同步 popTopClaim，返回链是本仓踩坑重灾区，回归风险大于收益。如要修：在 `dispatchPageBack` 消费处统一同步移除 `routeTitleClaims` 栈顶（`onDispose` 的 remove 引用相等、幂等，可安全重复） |
| K9 | ❌ 维持误报结论 | 桌面 Windows 模块是 `.exe`，拒绝非 ELF 会打断二进制模块 |

**提交说明**：本轮代码以独立提交落地（纯净子集）；`MainScreen.kt` / `MusicBackend.kt` /
本文档混有 §7 会话的未提交在途改动（K11 的 `amllClientLazy`、N4/N7 等），按「不动他人在途」约定
**不随本轮提交**——本轮落在其中的三个小改动（2 个 import 删除、1 段注释）暂留工作区，随 §7 会话一并提交即可。
