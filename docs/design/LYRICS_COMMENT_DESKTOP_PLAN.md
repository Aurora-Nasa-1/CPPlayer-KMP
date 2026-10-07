# 歌词界面优化 · 评论/回复 · 桌面弹层 · 音源插件歌词/评论接口

> **状态：** 方案（未落地代码，供拍板）
> **日期：** 2026-10-07
> **范围：** `core`（lyrics / music / api / provider / lyricsplugin）+ `app`（播放页 / 评论页 / 桌面播放页 / 设置）
> **参考：** Halcyon（`E:/project/Halcyon`，Apache-2.0）+ 本仓库 `docs/design/UNIFIED_SOURCE_PLUGIN_V3.md`
> **依据：** 全部结论来自实际读代码，非仅读文档。

---

## 0. 一句话结论

四件事**共用一条主线**：把「歌词」与「评论」都做成**与音源解耦、可被插件提供**的一等能力，
再在这条主线上补三处体验：① 歌词页加工具条与来源指示；② 评论补齐楼层/回复/发帖；
③ 桌面端把「歌词 / 评论」从页签改成**按钮 → 弹层**。

不重造已有的 `LyricsSource` / `LyricsSourceRegistry` / `LyricsEngine`（已实现），
只在它旁边**对称地**加一套 `CommentSource` / `CommentSourceRegistry`，并让音源 manifest 声明能力。

---

## 1. 现状盘点（逐文件核实）

### 1.1 歌词链路 —— 地基已就绪

| 层 | 文件 | 现状 |
|---|---|---|
| 统一模型 | `core/.../playback/SyncedLyricLine.kt` | `time/text/endTime/translation/romanization/words[]`，所有解析器共用出口 |
| 统一来源接口 | `core/.../lyrics/LyricsSource.kt` | ✅ 已实现：`LyricsSource` / `LyricsRequest(mediaId,...)` / `LyricsSourceResult` |
| 来源注册表 | `core/.../lyrics/LyricsSourceRegistry.kt` | ✅ 已实现：`orderedSources()` / `setOrder()` / `setEnabled()` / `reload()` |
| 内置来源 | `core/.../lyrics/BuiltinLyricsSources.kt` | `builtin.sidecar` / `builtin.amll` / `builtin.provider` 三条 |
| 引擎 | `core/.../lyrics/LyricsEngine.kt` | 顺序求值，首个命中即胜出 |
| 渲染 | `app/.../ui/component/LyricContent.kt` | `KaraokeLyricsView`，逐字 + 翻译 + 罗马音；`SmoothPositionSource` 已修帧率 |
| 页签 | `PlayerScreen.LyricsPage`（窄屏 pager 页 0）/ `DesktopPlayerScreen.DesktopLyricsContent`（宽屏页签 1） | 两处**各自**调用 `LyricContent` |

> **结论：** 「优化歌词界面」不是补地基，而是**在渲染层外面加一层控制壳**（工具条 / 来源 / 开关 / 分享）。

### 1.2 评论链路 —— 有明显缺口

| 项 | 现状 | 缺口 |
|---|---|---|
| 模型 | `core/.../music/Comment.kt`：已有 `replyCount` / `beReplied` | `userId` / `timeMs` / `parentCommentId` / `ipLocation` / `isHot` 缺失 |
| 解析 | `MusicSourceFromApi.parseComments()` L87 | **只填 6 个字段**，`replyCount`/`beReplied` 声明了但**从不赋值** |
| API 层 | `MusicApiService`：`getComments` / `getFloorComments` / `likeComment` / `postComment` **全都有** | `postComment` / `getFloorComments` **未**经 `MusicSource`/后端暴露，UI 也够不到 |
| 状态 | `CommentScreenModel`：`loadComments` + `toggleLike` | 无排序、无分页、无楼层、无发帖、无回复 |
| UI | `CommentScreen`：扁平 `LazyColumn` + 点赞 | 无回复按钮、无楼层展开、无输入条 |
| 入口 | 窄屏 `PlayerScreen.CommentPage`（页 2）/ 宽屏 `DesktopPlayerScreen.DesktopCommentContent`（页签 2） | 两套各自实现 |

> **结论：** API 能力**已具备**，缺的是「解析补全 → 服务暴露 → 状态扩展 → UI 落地」这四步。

### 1.3 桌面端现状

`DesktopPlayerScreen`（宽屏 ≥840dp）右侧面板用 `CpToggleChip` 做 4 页签：**队列 / 歌词 / 评论 / 相似**，
全部挤在一个固定宽度的 `Surface` 里（`weight(1f)`）。歌词在这种窄栏里逐字滚动观感差，
评论列表也没有楼层空间。用户要的是**按钮入口 + 弹出界面**。

### 1.4 音源插件现状

| 项 | 现状 |
|---|---|
| 契约 | `core/.../provider/BackendProvider.kt`：`id/name/version/type/apiMap/callApi(method,params)` |
| manifest | `core/.../provider/ModuleManifest.kt`：**无 `apiVersion`、无 `capabilities`** |
| 能力表达 | 靠 `apiMap` 里把方法映射成 `"unsupported"` —— 隐式、无法被 UI 读取 |
| 歌词接入 | `builtin.provider` 来源内部调 `api.getLyric(songId)`（即 `/lyric/new`），**音源已能提供歌词，但没声明** |
| 评论接入 | 音源经 `api.getComments()` 提供，**同样没声明** |
| 已有设计 | `docs/design/UNIFIED_SOURCE_PLUGIN_V3.md` 已规划 manifest v3 的 `apiVersion`/`capabilities` |

> **结论：** 「允许开发者进一步封装进音源插件里单独的接口」= 落实 v3 的 `capabilities`，
> 并**对称地**定义 `CommentSource` 接口，让一个音源插件能声明并实现「取词 + 评论」。

---

## 2. 设计目标与原则

1. **对称**：评论与歌词走**同一套形状**（`XxxSource` 接口 + `XxxSourceRegistry` + `XxxEngine`），
   开发者学一次即可写两类插件。
2. **照搬原 API**：歌词继续用网易云 `/lyric/new`（`lrc/yrc/tlyric/romalrc/klyric` 五段）；
   评论继续用 `/comment/*`（`comment` / `comment/floor` / `comment/like`）。**不改协议，只补解析与 UI**。
3. **能力声明优先于猜测**：音源/manifest 显式声明 `lyrics` / `comment`，UI 据此决定入口可见性。
4. **单一事实源**：桌面弹层与窄屏页**复用同一个 `@Composable` 内容与同一个 ScreenModel**，不写两份。
5. **零破坏**：老音源模块（无 `apiVersion`）继续按 v1 全能力加载；老评论调用点行为不变。
6. **不重造**：`LyricsSource*` 已实现，只做扩展；桌面弹层复用现有 `cpFluidBackground` / `CpRouteScaffold`。

---

## 3. 歌词界面优化

### 3.1 抽出「歌词控制壳」，内容与外壳分离

现状 `LyricContent` 只负责渲染。新增一层 **`LyricsPane`**（共享组件），把「渲染 + 工具条 + 状态」收在一处：

```
LyricsPane(state, onSeek, ...)          // app/.../ui/component/LyricsPane.kt（新）
├─ LyricContent(...)                    // 复用现有逐字渲染（不动）
└─ LyricsToolbar(...)                   // 新：底部浮动工具条
     ├─ 来源指示/切换   「AMLL TTML ▾」→ 跳「歌词来源」页（复用 §6.3 的注册表）
     ├─ 翻译开关（showTranslation）
     ├─ 罗马音开关（showPhonetic）
     ├─ 字号/对齐（居中 ↔ 左对齐）
     ├─ 分享（→ 分享卡，二期）
     └─ 评论（→ 评论弹层，见 §5）
```

- **工具条外观**复用现有 `CpFloatingToolbar`（窄屏 `LyricsPage` 已在用），不新造样式。
- **开关状态**（翻译/罗马音/字号/对齐/来源）收进一个 `LyricsUiController`（见 §3.3），
  窄屏与桌面**共享同一份**，跨形态一致。

### 3.2 视觉与交互优化清单

| 项 | 现状 | 优化 | 依据 |
|---|---|---|---|
| 当前行强调 | 库默认 | 放大 + 加粗 + 主题色；非当前行降透明度 | 逐字库已支持，只需传样式 |
| 背景 | 纯色/流体背景 | 歌词页叠一层**封面取色**的柔和渐变（`PlatformSeed` 已有取色能力） | 观感提升，成本低 |
| 来源可见 | 只在「更多菜单」一行 | 工具条常驻显示当前命中来源（灰字） | v3 文档 §6.3 已预告 |
| 无歌词 | 只有一行文案 | 变**可点入口**：「未找到歌词 · 换个来源 →」 | 同上 |
| 空状态 | 文案 | 保留文案 + 一个「重试/换源」动作 | 一致性 |
| 逐字帧率 | 已修（`SmoothPositionSource`） | 保持；新增测试防回归 | `LyricFrameRateTest` 已在 |

> ⚠️ **不做**：不改 `LyricContent` 的帧率实现（已针对 5 Hz 根因修过，改动风险高），只在其外层加壳。

### 3.3 `LyricsUiController`（新，commonMain UI 层）

```kotlin
// app/src/commonMain/kotlin/cp/player/app/ui/lyrics/LyricsUiController.kt
class LyricsUiController(private val settings: SettingsStorage) {
    val showTranslation: StateFlow<Boolean>
    val showPhonetic: StateFlow<Boolean>
    val alignment: StateFlow<LyricsAlignment>   // CENTER / START
    val fontSizeScale: StateFlow<Float>
    fun toggleTranslation(); fun togglePhonetic()
    fun setAlignment(a: LyricsAlignment); fun setFontScale(f: Float)
}
```
持久化到 `SettingsStorage`（键前缀 `lyrics_ui_*`），窄屏/桌面共用。

---

## 4. 评论与回复

### 4.1 领域模型扩展（`core/.../music/Comment.kt`）

```kotlin
data class Comment(
    val id: Long,
    val content: String,
    val user: String,
    val avatar: String,
    val time: String,
    val likedCount: Int,
    val liked: Boolean,
    val replyCount: Int = 0,
    val beReplied: List<Reply>? = null,
    // ⭐ 新增（全部带默认值，不破坏既有构造）
    val userId: Long = 0L,
    val timeMs: Long = 0L,
    val parentCommentId: Long = 0L,
    val ipLocation: String = "",
    val isHot: Boolean = false,
) {
    data class Reply(val userId: Long, val nickname: String, val content: String)
}
```

### 4.2 解析层：照搬网易云评论原 API 形状

**照搬** Halcyon `NeteaseComments.kt` 的解析规则（同源同字段）：

| 上游字段 | 用途 | 备注（照搬 Halcyon 的处理） |
|---|---|---|
| `commentId` / `id` | 主键 | 取 `commentId`，无则退 `id` |
| `user.userId/nickname/avatarUrl` | 用户 | avatar 统一升 https |
| `timeStr` / `time` | 时间 | 优先 `timeStr`（"12分钟前"），`time` 存毫秒 |
| `likedCount` / `liked` | 点赞 | `likedCount` 钳到 ≥0 |
| `replyCount` + `showFloorComment.replyCount` | 楼层数 | **取两者较大值**（Halcyon 做法） |
| `parentCommentId` | 所属楼层 | |
| `beReplied[0]` | 「回复 @x:」目标 | **当 `beRepliedCommentId == parentCommentId` 时置空**（直回楼主不显示前缀） |
| `ipLocation.location` | IP 属地 | 可空 |

`MusicSourceFromApi` 新增：

```kotlin
fun parseComments(json): MusicResult<CommentPage>        // 改为返回分页对象（total/hasMore/cursor/sortType）
fun parseFloorComments(json): MusicResult<CommentFloorPage>  // 新：comment/floor
fun parseCreatedComment(json): MusicResult<Comment>      // 新：发帖/回复返回体（comment 或 data.comment）
```

`CommentPage` / `CommentFloorPage` 对齐 Halcyon（含 `hasMore` / `cursor` / `nextTime`）。
⚠️ **排序回退语义**（照搬）：未登录时请求「推荐」会被服务端按「热度」服务，
下一页必须用**服务端返回的 `sortType`** 请求，否则 400 —— 分页游标要把 `sortType` 一并带回。

### 4.3 服务/后端接口（打通到 UI）

`MusicSource` / `UnifiedMusicSource` / `MusicBackend` 增加：

```kotlin
suspend fun getComments(id: String, type: String, limit: Int, offset: Int, sortType: Int): MusicResult<CommentPage>
suspend fun getFloorComments(id: String, parentCommentId: Long, type: String, limit: Int, time: Long): MusicResult<CommentFloorPage>
suspend fun postComment(id: String, type: String, content: String, replyToCommentId: Long?, parentCommentId: Long): MusicResult<Comment>
suspend fun likeComment(id: String, cid: Long, type: String, liked: Boolean): MusicResult<Boolean>   // 已有，补 MusicResult 包装
suspend fun deleteComment(id: String, type: String, cid: Long): MusicResult<Boolean>                  // 可选
```

- 全部经 `MusicApiService` 已有的同名方法落地，**不改 API 协议**。
- 评论**不进读透缓存**（与现状一致），直连网络。

### 4.4 `CommentScreenModel` 扩展

对齐 Halcyon `NeteaseCommentsSheet` 的状态机（`CommentFloorUi` 思路），但用 KMP 惯例改写：

```kotlin
data class CommentUiState(
    val id: String, val type: String,
    val sortType: Int = 1,                 // 推荐/热度/最新
    val comments: List<Comment> = emptyList(),
    val hotComments: List<Comment> = emptyList(),   // 可选：热度前置
    val floors: Map<Long, CommentFloorUi> = emptyMap(),  // 楼层展开缓存
    val totalCount: Long = 0,
    val cursor: String = "", val hasMore: Boolean = false,
    val loading: Boolean = false, val loadingMore: Boolean = false,
    val error: String? = null,
    // 输入条
    val replyTarget: Comment? = null,      // null = 发新评论
    val draft: String = "", val sending: Boolean = false,
)
```

方法：`loadComments(reset)` / `loadMore()` / `loadFloor(comment)` / `toggleFloor(comment)` /
`startReply(comment)` / `cancelReply()` / `updateDraft(text)` / `send()`。

- **乐观插入**（照搬 Halcyon）：发帖成功后把服务端返回的 `created` 插到对应楼层或顶层，
  失败回滚 + 文案（中英各一）。
- **楼层归属**：回复「楼层里的回复」时，`parentCommentId = 该回复的 parentCommentId`（留在同一楼层），
  `replyToCommentId = 被回复者 id`（照搬 Halcyon `send()` L304-314）。
- **字数上限 140 码点**：照搬 `NETEASE_COMMENT_MAX_LENGTH` / `clipNeteaseComment`，
  不切断代理对（surrogate pair）。

### 4.5 `CommentScreen` UI 改造

```
┌───────────────────────────────────────────────┐
│ 评论 1.2万     [推荐 ▾] [热度] [最新]           │  ← 排序页签
├───────────────────────────────────────────────┤
│ ◯ 张三    👍 128                               │
│   12分钟前 · 江苏                              │
│   这首歌真好听                                  │
│   ↳ 回复 @李四：+1              [回复] [👍]     │  ← beReplied 前缀（可点）
│   └ 查看 36 条回复 ▾                            │  ← 楼层展开
│      ◯ 王五  ...                               │
│   ─────────────────────────────────            │
│ ◯ 李四    ...                                  │
├───────────────────────────────────────────────┤
│ [回复 @张三 ✕]                                  │  ← 回复目标条（可取消）
│ [ 说点什么…                          ] [发送]   │  ← 输入条（吸底）
└───────────────────────────────────────────────┘
```

- 复用现有 `ContentState` / `CpLoadingIndicator` / `LazyScrollColumn` 等既有组件。
- 头像/点赞样式沿用现有 `CommentItem`，只加「回复 / 楼层 / 属地」。
- **空/错/加载**三态保持现有处理。

### 4.6 参考 Halcyon 的取舍

| Halcyon 做法 | 是否照搬 | 原因 |
|---|---|---|
| `CommentFloorUi` 楼层缓存 + `expanded` | ✅ 照搬 | 楼层展开是刚需 |
| `replyTarget` 输入条 + `insertCreated` 乐观插入 | ✅ 照搬 | 体验关键 |
| 排序三档 + `sortType` 回退 | ✅ 照搬 | 协议要求 |
| 140 码点截断 | ✅ 照搬 | 与上游一致 |
| VIP 徽章 / `vipRights` | ⏸ 二期 | 需要额外图标资源 |
| 富文本 @ / 表情 | ❌ 不做 | 工作量大、收益低 |
| Miuix `TextField` | ❌ 不照搬 | 本项目用 M3，换 `OutlinedTextField` |

---

## 5. 桌面端：按钮入口 + 弹出界面

### 5.1 入口按钮

`DesktopPlayerScreen` 顶栏（「更多」按钮左侧）新增两枚 `FilledIconButton`（与现有一致）：

| 按钮 | 图标 | 打开 |
|---|---|---|
| 歌词 | `Icons.AutoMirrored.Filled.Subtitles`（或 `Lyrics`） | `LyricsOverlay` |
| 评论 | `Icons.AutoMirrored.Filled.Comment` | `CommentsOverlay` |

- 右侧面板**保留** `队列 / 相似` 两页签（它们是「伴随信息」，适合常驻窄栏）；
  **歌词 / 评论**移出页签，改按钮弹层（它们需要大空间）。
- 入口可见性：评论按钮仅在**当前音源声明 `comment` 能力**时显示（见 §6），
  否则隐藏（避免「点了没反应」）；歌词按钮恒显示（至少边车/AMLL 可用）。

### 5.2 弹层形态

**决策：应用内覆盖层（in-window overlay），不用多 `ComposeWindow`。**

理由：① 复用 `cpFluidBackground` 与主题，观感连续；② 避免多窗口焦点/置顶/跨平台差异；
③ 与窄屏 `CpRouteScaffold` 的「双栏右栏」范式一致，便于统一。

```
Box(fillMaxSize) {
    // 播放页原内容
    DesktopPlayerScreen(...)
    // 覆盖层
    AnimatedVisibility(visible = overlay == LYRICS) {
        Scrim(onClick = dismiss)                    // 半透明遮罩，点按关闭
        LyricsOverlay(...)                          // 居中大卡片：LyricsPane + 关闭键
    }
    AnimatedVisibility(visible = overlay == COMMENTS) {
        Scrim(onClick = dismiss)
        CommentsOverlay(...)                        // 右侧滑入 Sheet（宽 ~520dp）
    }
}
```

- **歌词弹层**：居中大卡片（`MaterialTheme.shapes.extraLarge` + `surface.copy(alpha=.72f)`，
  与现有右面板同款），内含 `LyricsPane`（§3）。转场走 `CpMotion.spatial*`。
- **评论弹层**：右侧滑入 Sheet，内含 `CommentScreen` 的**共享内容 Composable**（§5.3）。
- **Esc / 遮罩 / 关闭键**三选一即可关闭；桌面键盘 Esc 复用现有 `DesktopBackDispatcher`。

### 5.3 与窄屏共用（关键：不写两份）

把 `CommentScreen.Content()` 里的列表+输入条抽成 **`CommentPane(model, modifier)`**（无 Scaffold）。
- 窄屏 `CommentScreen` = `CpRouteScaffold { CommentPane(model) }`
- 桌面 `CommentsOverlay` = 卡片 `{ CommentPane(model) }`

歌词同理：窄屏 `LyricsPage` 与桌面 `LyricsOverlay` 都调 `LyricsPane`。

> 现状 `PlayerScreen.CommentPage` 与 `DesktopPlayerScreen.DesktopCommentContent` **是两份实现**，
> 本方案借这次改造**合并为一份**，顺带消除重复。

---

## 6. 音源插件：歌词 / 评论「单独接口」

> 目标：让一个**音源插件**能用**独立的、版本化的接口**声明并提供「歌词」与「评论」，
> 而不是靠 `apiMap` 里散落的 `"unsupported"` 猜。这是 v3 设计（`UNIFIED_SOURCE_PLUGIN_V3.md` §5）的落地。

### 6.1 `ModuleManifest` 增补（向后兼容）

```jsonc
// manifest.json —— 音源插件
{
  "id": "my-provider", "name": "...", "version": "2.1.0", "type": "http",
  "apiVersion": 3,               // 缺省 = v1 老模块（全能力），恒可加载
  "minHostApiVersion": 3,
  "capabilities": [              // ⭐ 新增：声明提供哪些能力
    "search", "songUrl", "trackDetail",
    "lyrics",                    // ← 提供歌词
    "comment"                    // ← 提供评论（含发帖/回复/点赞/楼层）
  ],
  "apiMap": { "lyric/new": "/lyric/new", "comment/music": "/comment/music" }
}
```

- **冲突规则**：声明了 `capabilities` 就**只认它**（忽略 `apiMap` 的 `unsupported` 猜测）；
  未声明则沿用 `apiMap` 判定；都没有 = 全能力（等价 v1）。
- `ModuleManifest` 增 `apiVersion` / `minHostApiVersion` / `capabilities: Set<String>`（全带默认值）。

### 6.2 `CommentSource` —— 与 `LyricsSource` 对称

新增一套（`core/.../comment/`）：

```kotlin
// 对称 LyricsSource / LyricsRequest / LyricsSourceResult
interface CommentSource {
    val id: String            // "builtin.provider" / 插件 id
    val name: String
    val bundled: Boolean
    val capabilities: Set<CommentCapability>   // LIST / FLOOR / POST / LIKE
    suspend fun list(request: CommentRequest): CommentPageResult?
    suspend fun floors(request: CommentRequest, parentId: Long, time: Long): CommentFloorResult?
    suspend fun post(request: CommentRequest, content: String, replyTo: Long?, parentId: Long): CommentResult?
    suspend fun like(request: CommentRequest, cid: Long, liked: Boolean): Boolean
}

data class CommentRequest(val mediaId: CPMediaId?, val type: String, val sortType: Int, val offset: Int, val limit: Int)

interface CommentSourceRegistry {          // 对称 LyricsSourceRegistry
    suspend fun orderedSources(): List<CommentSource>
    suspend fun allSources(): List<CommentSourceEntry>
    suspend fun setOrder(ids: List<String>)
    suspend fun setEnabled(id: String, enabled: Boolean)
    suspend fun reload()
}

interface CommentEngine {                  // 对称 LyricsEngine
    suspend fun list(req: CommentRequest): CommentPageResult
    suspend fun post(...): CommentResult
}
```

内置来源一条：`builtin.provider`（走 `api.getComments()` / `api.postComment()` …），
即「音源自带评论」，与 `builtin.provider` 歌词来源**同源同 id 前缀**。

### 6.3 音源插件如何提供歌词 / 评论

**两条接入路径**，开发者二选一：

| 路径 | 适合 | 做法 |
|---|---|---|
| **A. HTTP 端点（推荐）** | 已有 HTTP 后端的音源 | manifest 声明 `capabilities:["lyrics","comment"]` + `apiMap` 映射标准方法名；宿主用内置 `ProviderLyricsSource` / `ProviderCommentSource` 适配器调 `callApi()` |
| **B. 宿主 SPI（进阶）** | 需要复杂逻辑（签名/加密/多步） | 直接实现宿主接口 `LyricsSource` / `CommentSource`，在 `ProviderFactory` 注册（jvmMain） |

**标准方法名**（路径 A 用，已在 `MusicApiMethod` 定义，照搬原 API）：
`lyric/new`、`comment/music`（按 type 派生）、`comment/floor`、`comment/like`、`comment`（发帖/回复）。

**适配器**（新，`core/.../lyrics/ProviderLyricsSource.kt` 已存在，照抄到评论侧）：

```kotlin
internal class ProviderCommentSource(private val api: MusicApiService, private val type: String) : CommentSource {
    override val id = "builtin.provider"; override val bundled = true
    override val capabilities = setOf(LIST, FLOOR, POST, LIKE)
    override suspend fun list(req) = runCatching { MusicSourceFromApi.parseComments(api.getComments(...)) }.getOrNull()
    ...
}
```

### 6.4 与 Lyrico 歌词插件的关系（**不合并，只并列**）

- Lyrico 歌词插件（JS/Rhino）继续作为 `LyricsSource` 的第三方实现，**不动**。
- 音源插件的歌词能力是**另一条** `LyricsSource`（`builtin.provider`），二者在
  `LyricsSourceRegistry` 里**并列可排序** —— 用户可把「音源自带歌词」拖到任意位置。
- 评论目前**没有** JS 插件体系，先只做「音源提供 + 内置」，预留 `CommentSource` 接口给未来 JS 插件。
- **能力门槛**：只有声明了 `getLyrics` / `comment` 的来源才进列表（现状对歌词已如此）。

---

## 7. 落地路线（分批，每步可独立验证）

| 批 | 内容 | 验证 |
|---|---|---|
| **P1 评论地基** | `Comment` 模型扩展；`parseComments` 补全 + `parseFloorComments` / `parseCreatedComment`；`MusicSource`/后端暴露 5 个方法 | `CommentParseTest`（照搬 Halcyon 测试样例：楼层数取较大值、直回楼主不显前缀、140 截断） |
| **P2 评论 UI** | `CommentScreenModel` 状态机 + `CommentPane` 抽取；排序/分页/楼层/输入条/乐观插入 | 状态机单测；离屏出图（中英 × 宽窄） |
| **P3 歌词壳** | `LyricsPane` + `LyricsToolbar` + `LyricsUiController`；来源指示；无歌词换源入口 | `LyricFrameRateTest` 不回归；工具条出图 |
| **P4 桌面弹层** | `DesktopPlayerScreen` 去掉歌词/评论页签 → 顶栏按钮；`LyricsOverlay` / `CommentsOverlay` + 遮罩 + Esc；窄屏与桌面共用 `LyricsPane`/`CommentPane` | 宽屏出图；Esc/遮罩关闭断言 |
| **P5 插件接口** | `ModuleManifest` 加 `apiVersion`/`capabilities`；`CommentSource` 全套 + `CommentSourceRegistry`/`CommentEngine`；`ProviderCommentSource` 适配器；`ProviderManagementScreen` 能力标签 | 老模块加载不回退；能力裁剪生效；`PROVIDER_DEV_GUIDE.md` 补文档 |

> P1–P2 与 P3–P4 相互独立，可并行；P5 依赖 P1 的模型与 P2 的 UI 入口。

---

## 8. 风险与取舍

| 风险 | 对策 |
|---|---|
| 桌面改「页签→弹层」破坏既有用户习惯 | 保留队列/相似页签；歌词/评论按钮放顶栏显眼处；弹层可 Esc/遮罩关闭 |
| 评论分页 `sortType` 回退导致 400 | 严格照搬 Halcyon：用**服务端返回的** `sortType`/`cursor` 续页 |
| 楼层数据量爆炸 | 楼层**懒加载 + 折叠**，只展开才拉；缓存 `floors` 表 |
| 老音源被 v3 判不兼容 | `apiVersion` 缺省 = v1 **恒可加载**；`minHostApiVersion` 只在显式声明时校验 |
| `CommentSource` 与 `LyricsSource` 重复代码 | 允许少量重复（两者语义不同：分页游标 vs 首个命中），**不强行泛型化**，避免抽象过度 |
| 弹层与流体背景性能 | 覆盖层只在可见时组合；复用现有 `AnimatedVisibility` |

---

## 9. 明确不做

- ❌ 不改 `LyricContent` 的逐帧外推实现（已修，风险高）。
- ❌ 不引入 compose-resources（文案继续走自建 `CpStrings` 树）。
- ❌ 不做评论富文本/@/表情（二期再议）。
- ❌ 不把音源歌词能力包成 JS 插件（Rhino 有成本，且要访问 Ktor）。
- ❌ 不合并 Lyrico 歌词插件与音源歌词接口（只并列）。

---

## 10. 待拍板

1. **桌面弹层形态**：应用内覆盖层（本方案）还是独立 `ComposeWindow`？
   —— 本方案取前者（复用背景/主题、无多窗口差异）。
2. **歌词/评论是否移出右面板页签**：本方案移出（改为按钮弹层）；若希望「页签保留 + 额外弹层入口」，结构略不同。
3. **评论插件是否也开 JS（Lyrico 式）**：本方案先只做「音源提供 + 内置」，`CommentSource` 接口预留。

---

## 附：涉及的关键文件

| 文件 | 改动 |
|---|---|
| `core/.../music/Comment.kt` | 加 `userId/timeMs/parentCommentId/ipLocation/isHot` |
| `core/.../music/MusicSourceFromApi.kt` | 补全 `parseComments`；新增 `parseFloorComments`/`parseCreatedComment`；新增 DTO |
| `core/.../music/MusicSource.kt` / `UnifiedMusicSource(.Impl).kt` | 暴露评论 5 方法 |
| `core/.../comment/*`（新） | `CommentSource` / `CommentRequest` / `CommentSourceRegistry` / `CommentEngine` / `BuiltinCommentSources` |
| `core/.../provider/ModuleManifest.kt` | 加 `apiVersion` / `minHostApiVersion` / `capabilities` |
| `core/.../provider/ModuleManager.kt` | v3 版本校验（缺省按 v1） |
| `app/.../ui/component/LyricsPane.kt`（新） | 歌词壳 + 工具条 |
| `app/.../ui/lyrics/LyricsUiController.kt`（新） | 歌词 UI 开关持久化 |
| `app/.../ui/component/CommentPane.kt`（新） | 评论内容（列表+楼层+输入条），窄屏/桌面共用 |
| `app/.../ui/model/CommentScreenModel.kt` | 排序/分页/楼层/发帖/回复状态机 |
| `app/.../ui/screen/CommentScreen.kt` | 改为 `CpRouteScaffold { CommentPane }` |
| `app/.../ui/screen/PlayerScreen.kt` | `LyricsPage`/`CommentPage` 改用 `LyricsPane`/`CommentPane` |
| `app/.../ui/screen/DesktopPlayerScreen.kt` | 去掉歌词/评论页签 → 顶栏按钮 + `LyricsOverlay`/`CommentsOverlay` |
| `app/.../i18n/*` | 评论排序/楼层/回复/发送/字数 等文案（中英同步） |
| `docs/dev/PROVIDER_DEV_GUIDE.md` | 补 v3 `capabilities` 与 `lyrics`/`comment` 能力说明 |

---

# 实施进度（2026-10-07 更新）

## ✅ 已完成并验证（评论地基 + 楼层 + 桌面弹层）

### P1 领域模型
- `core/.../music/Comment.kt`：新增 `userId` / `timeMs` / `parentCommentId` / `ipLocation` / `isHot`
  与派生属性 `isFloorReply` / `replyToNickname`；新增 `CommentPage` / `CommentFloorPage`；
  新增码点工具 `COMMENT_MAX_LENGTH` / `commentCodePointLength()` / `clipComment()`
  （Kotlin common 无 `String.codePointCount`，手写且不切断代理对）。

### P2 解析层（照搬网易云原 API 形状）
- `MusicSourceFromApi`：`parseComments` 改为返回 `CommentPage`（补全 `replyCount` 取
  `replyCount` 与 `showFloorComment.replyCount` 的**较大值**、`beReplied` 直回楼主置空、
  `parentCommentId`/`userId`/`timeMs`/`ipLocation`）；新增 `parseFloorComments`（回填
  `parentCommentId`、取 `nextTime` 游标）与 `parseCreatedComment`（`comment` / `data.comment` 两处取）。
- DTO 扩充：`CommentDto` + `showFloorComment` / `beReplied` / `ipLocation` / `parentCommentId` /
  `replyCount`；新增楼层与发送结果响应 DTO；主键缺失/非正的条目**丢弃**。

### P3 服务接口
- `MusicRepository`：`getComments(分页, sortType)` / `getFloorComments(time 游标)` /
  `postComment(replyToCommentId, parentCommentId)` / `likeComment`。

### P4 状态机
- `CommentScreenModel`：排序（推荐/热度/最新，**翻页回传服务端实际 `sortType`**）、
  分页（`hasMore` + offset）、楼层懒加载与**折叠不丢数据**、回复目标、草稿、发送（乐观插入
  + 失败回滚 + 无回体时重新拉页）、点赞（顶层与楼层都能点，同时写回三处）。

### P5 UI
- `app/.../ui/component/CommentPane.kt`（新，窄屏与桌面**共用**）：排序页签 + 总数（万/k 分档）、
  评论列表、**楼层「查看 N 条回复 / 收起回复」显示与折叠**（折叠不请求、展开懒加载、
  楼层内「查看更多回复」）、「回复 @昵称」前缀、点赞、底部输入条（回复目标条 + 140 码点校验）。
- **楼层可见性加固**（针对「看不到楼」的反馈）：
  - **平铺回复归组（真正的根因）**：有些音源把 `parentCommentId > 0` 的回复**平铺在顶层
    `comments` 数组里**，而不是放进 `showFloorComment.topReplies`。不归组就会表现为
    「一条孤零零的『回复 @xxx』独立成条、父评论下永远没有楼层」。`parseComments` 新增
    `groupFlattenedReplies()`：按 `parentCommentId` 归回父评论、把 `replyCount` 抬到至少
    等于归组条数；父评论不在本页的回复原样留在顶层（不丢）。对标准网易云响应是恒等变换。
  - 解析上游内联的 `showFloorComment.topReplies` 进 `Comment.topReplies`（只向下取一层），
    楼层数取 `replyCount` / `showFloorComment.replyCount` / 内联条数**三者较大值** ——
    有些音源只给内联回复、不给计数，只看前两个就永远不显示楼层入口；
  - `CommentScreenModel` 用 `topReplies` **预置楼层缓存**（`seedFloors`），入口立刻可见、展开不用先转圈；
    新增 `CommentFloorUi.loadedFromServer` 区分「内联种子」与「完整一页」，展开时仍补一次请求；
    已有内容时不再显示加载态 / 错误（补页失败不该盖住已经能看的回复）；
  - 楼层折叠行从「一行主题色文字」改成**实心 chip**（`secondaryContainer`），不再混在正文里被忽略。
- **点赞按钮对齐**：昵称原先是 `weight(1f, fill = false)` 且与一个 `Spacer(weight(1f))` 平分权重，
  剩余空间没全让给右侧 ⇒ 点赞按钮的 x 随昵称长短浮动、多条之间对不齐。改成昵称独占权重、
  点赞按钮不参与权重，右侧恒定贴边。
- `CommentScreen` 改为 `CpRouteScaffold { CommentPane }`；`PlayerScreen.CommentPage` 同步收敛；
  删除重复的 `DesktopCommentContent` 与旧 `CommentItem`。

### P6 桌面（**已按反馈撤回**）
- 初版把「歌词 / 评论」从右侧页签改成顶栏按钮 + 应用内覆盖层；实测观感不佳，**已整体还原**为
  原来的四页签（队列 / 歌词 / 评论 / 相似），歌词与评论都回到窄栏，`DesktopCommentContent` 恢复。
  覆盖层相关代码（`DesktopOverlay` / `DesktopOverlayScrim` / `DesktopLyricsOverlay` /
  `DesktopCommentsOverlay`）已删除。
- 保留的改进：**评论页签**内部改用 `CommentPane`（排序 / 楼层 / 回复输入条），不再是原来的扁平列表。

### P7 文案
- `SocialStrings.Comment` 扩为完整一组（排序 / 总数 / 热评 / 回复 / 取消 / 占位 / 发送 / 失败 /
  超长 / 楼层展开折叠 / 查看更多 / 加载更多 / 无更多 / 空态），中英同步。

### 验证（实际执行结果）
- `:core:compileKotlinDesktop` / `:app:compileKotlinDesktop` / `:app:compileAndroidMain` 全部 SUCCESSFUL。
- `:core:desktopTest` 全量：**514 tests / skipped=0 / failures=0 / errors=0**（50 个测试类）。
- `CommentParseTest`：**16 tests / skipped=0 / failures=0 / errors=0**（含内联 `topReplies` 与平铺归组各若干项）。

## ⏳ 尚未实施

| 项 | 说明 |
|---|---|
| P3 歌词壳（`LyricsPane` + 工具条 + `LyricsUiController`） | 本轮未动；`LyricContent` 与 `LyricsPage` 保持原样 |
| P5 插件接口（manifest v3 `capabilities` + `CommentSource` 全套） | 本轮未动；评论目前只走「音源 + 内置」一条来源 |
| 歌词分享卡 / 打轴器 | 未动 |
| 评论 UI 的离屏出图校验 | 未做（`CommentPane` 依赖会发网络请求的 ScreenModel，需先注入假状态才能渲出列表） |

