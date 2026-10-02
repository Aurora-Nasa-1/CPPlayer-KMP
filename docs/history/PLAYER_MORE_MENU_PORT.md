# 播放界面「更多」按钮与菜单移植方案

> 目标：把 `reference/cp-player-legacy` 播放页的「更多（MoreVert）」按钮及其菜单/功能
> 移植到 KMP 版。**结论先行：这项移植已经完成了约 90%** —— 本文的重点不是「怎么移植」，
> 而是**核对既有实现**、列出**真实缺口**、并给出**每个缺口的取舍与做法**。
>
> 只出方案，未改任何 `.kt`。工作区当前有多个并行会话的在途改动（见文末 §6）。

---

## 1. 现状核对（先别重复造轮子）

### 1.1 参考实现长什么样

旧版播放页的「更多」入口有**两处**，都是同一个弹层：

| 位置 | 文件:行 | 容器 |
|---|---|---|
| 紧凑布局「二级控件行」右端 | `PlayerScreen.kt:826-862` | `Background=surfaceVariant 50%` 的圆钮 |
| 宽屏（平板/横屏）底部操作栏 | `PlayerScreen.kt:1304-1334` | 纯 `IconButton`（无背景） |

两处都开 `PlayerSongOptionsBottomSheet`（`ui/component/PlayerSongOptionsBottomSheet.kt`，343 行），
内容是**三层**：

1. **头部**：圆形封面 + 歌名（24sp）+ 歌手（18sp）+ 右侧 48dp 圆形「分享」钮；
2. **四宫格动作**（`PillButton`，2×2，高 56dp）：
   加入歌单 / 下载 / 睡眠定时 / 不感兴趣；
3. **信息卡**（`surfaceContainerHigh`，圆角 24dp）：歌曲信息（ID / 专辑）→
   歌词信息（来源 / 格式 / 逐字 / 翻译 / 音译）→ 音频质量（编码 / 采样率 / 位深 / 码率 / 声道）
   → Hi-Fi & USB DAC（仅 Rust 引擎 = 1 时：USB 独占状态轮询 + 硬件音量滑条）。

### 1.2 KMP 版现状 —— 已经全部在了

| 参考能力 | KMP 对应实现 | 状态 |
|---|---|---|
| MoreVert 按钮 | `PlayerScreen.kt:754-779`（在 `CpFloatingToolbar` 内） | ✅ 有 |
| 弹层 | `ui/component/PlayerMoreBottomSheet.kt`（318 行） | ✅ 有 |
| 头部（封面/歌名/歌手/分享钮） | 同文件 L65-124 | ✅ 有 |
| 加入歌单 | `PlayerPillButton`，接 `AddToPlaylistSheet` | ✅ 有 |
| 下载 / 已下载态 | `AppModel.downloadTrack(track)`，`isDownloaded` 动态 | ✅ 有 |
| 睡眠定时 | `SleepTimerDialog`，且带「· 播完本曲 / · N 分钟」态文案 | ✅ 有，**比旧版强** |
| 不感兴趣 | `AppModel.api.dislikeSong(rawId)` + notify + `skipNext()` | ✅ 有 |
| 分享 | `shareText(...)`（KMP expect/actual），非 Android Intent | ✅ 有 |
| 歌曲信息 | 弹层内「信息卡」+ `SongInfoDialog` 两处 | ✅ 有，**比旧版多** |
| 歌词信息（来源/格式/逐字/翻译/音译） | 弹层信息卡 L230-254 | ✅ 有 |
| 音频格式（编码/采样率/位深/码率/声道） | 弹层信息卡 L255-281 | ✅ 有 |
| MIME（旧版没有） | `SongInfoDialog` L819 | ✅ 有，**新增** |

**并且**：`PlayerMoreBottomSheet` 已在 2026-10-01 那一轮做过 Expressive 收敛
（圆角映射到 `shapes.large`、图标族归一为 `Filled`、`PlayerPillButton` 私有化）。
见 `.workbuddy-ai/memory/2026-10-01.md` 第六轮。

### 1.3 一句话结论

> 「更多按钮 + 菜单 + 功能」按**旧版播放页**的口径，已经移植完。
> 剩下的差异集中在**三件事**：宽屏没有入口、旧版播放器专属的 USB/DSD 面板、
> 以及旧版**其他页面**里的若干动作。下面逐个给方案。

---

## 2. 缺口 A：宽屏（`DesktopPlayerScreen`）**没有「更多」入口** —— 建议补

### 现象

`DesktopPlayerScreen.kt:124-126` 有一段注释，说明这里**主动删掉**了一个
`onClick = { /* reserved */ }` 的 `MoreHoriz` 假按钮，理由是「长得像入口、点了什么都不发生」。

这个删除**在当时是对的**（假可供性确实更糟）。但它同时把宽屏的「更多」**整个取消**了：
现在 `DesktopPlayerScreen` 的控件行只有 随机 / 上一首 / 播放 / 下一首 / 循环
（`PlayerControls`，L277-308），**没有任何路径**能到达 `PlayerMoreBottomSheet`。

而宽屏是**桌面端默认形态**（默认窗口 1320×860，最小 900×640，均 ≥ 840 断点）。
即：**桌面用户永远看不到「加入歌单 / 下载 / 睡眠定时 / 不感兴趣 / 分享 / 歌曲信息」这一组功能** ——
它们只存在于窄屏布局里。这是当前最大的真实缺口。

### 方案

`PlayerMoreBottomSheet` 本身是 KMP 通用组件（无 `android.*` 依赖），**可直接复用**，
不需要新建组件。改动只有两处：

**① 提升状态到 `PlayerScreen`（外层），两套布局共享**

当前 `showMoreMenu` 是 `PlayerScreenContent` 的局部状态（`PlayerScreen.kt:221`），
而 `DesktopPlayerScreen` 是**兄弟分支**（L155），拿不到。

```
PlayerScreen.Content()
 ├── 现有：state / scope / onRepeat
 ├── 新增：var moreSheetTrack / showMoreMenu 等状态 + 回调
 └── BoxWithConstraints
      ├─ if (isExpanded) DesktopPlayerScreen(..., onMoreClick = …, )
      └─ else            PlayerScreenContent(..., 同上)
```

**保持参数名与 `PlayerScreenContent` 一致**（`onMoreClick` / `onAddToPlaylist` /
`onDownload` / `onSleepTimer` / `onShare` / `onShowInfo` / `onDislike`），
把 `PlayerScreen.kt:442-472` 那段回调实现**提出来**变成外层的一个 `PlayerMoreActions`
holder（或直接内联），两套布局引用同一份 —— 避免第二份实现漂移。

⚠️ 注意 `PlayerScreenContent` 现在自带 `addToPlaylist / sleepTimer / songInfo` 三个弹窗
（L494-518），提升后这些也应一并上移，否则宽屏点了没反应。

**② 在 `DesktopPlayerScreen` 放置入口 —— 位置建议「顶栏右侧」而非控件行**

理由：
- 控件行（`PlayerControls`）现在是 `Arrangement.spacedBy(8.dp, CenterHorizontally)`，
  往里插一颗会和「随机/循环」这两颗 `CpModeToggle` 混在一起 ——
  `CpModeToggle` 是有选中态的模式开关，`MoreVert` 是**无状态动作**，语义不同，并排会让
  用户以为「更多」也是个可切换的模式。
- 顶栏右侧现在**是空的**（那颗假按钮删掉后一直没补），且与「喜欢」按钮、歌名不冲突。
- 更关键：顶栏是**全屏页**级别的动作位，弹层内容里包含「分享 / 歌曲信息」这类
  **页级动作**，放顶栏语义更贴。

**建议用 `FilledIconButton` + `surfaceContainerHighest`**，与播放页顶栏的
「队列」按钮（`PlayerScreen.kt:382-394`）**同款** —— 那个位置已经确立了外观，
不要另造一套。

⚠️ 不要贴 `surfaceVariant.copy(alpha = 0.5f)` 的圆底（旧版紧凑布局的写法）：
本仓库已经因为「看起来像个按钮的按钮」返工过一次（见 `PlayerScreen.kt:378-381` 注释）。

### 验收

- 1600×1000（宽屏）与 900×640（最小窗口）下，顶栏能看到「更多」，点开弹层四宫格 + 信息卡完整；
- 窄屏（< 840）行为**不变**；
- 两个断点各出**浅色 / 深色**离屏渲染图核对版式（本仓库硬性要求，编译+单测量不到版式）。

---

## 3. 缺口 B：Hi-Fi / USB DAC / DSD 面板 —— **不建议移植**

### 现象

旧版信息卡末尾有一整段（`PlayerSongOptionsBottomSheet.kt:291-323`），仅当
`UserPreferences.getAudioEngine(context) == 1`（Rust 引擎）时出现：

- USB 独占会话状态（`isRustDirectUsbSessionActive()`），且用 `for (i in 1..10) delay(500)`
  **轮询**等设备注册；
- 硬件音量滑条（`hasRustDirectUsbHardwareVolume()` / `getRustDirectUsbHardwareVolume()`）。

### 不建议移植的理由

1. **KMP 侧没有对应能力**。这三个 API 全在旧版的 `cp.player.engine.RustEngine`（Android JNI），
   本仓库的 `core` 播放抽象（`PlaybackController` / `UnifiedMusicSource`）里**没有** USB 独占
   或硬件音量这一层。移植等于先造一套跨平台音频设备枚举/独占 API，成本远超「移植一个菜单」。
2. **轮询写法本身是坏的**。固定 10 次 × 500ms = 最多 5 秒 `LaunchedEffect`，且**打开弹层就
   开始轮询**（不关弹层也一直转）。这是旧版的缺陷，不该照抄。
3. **它不属于「更多菜单」**。它是**调音设置**，和「加入歌单 / 下载」不是一类东西。
   塞进弹层会把一个本来 2 秒读完的菜单变成一屏要滚动的诊断面板。

### 建议的替代做法（如果桌面端确实需要）

相关能力已经在**设置**里：`core/settings` 的音频后端选择 + `docs/STREAM_OUTPUT` 相关页面
（`RenderTuningSettingsScreen` / `StreamOutputSettingsScreen`）。
正确做法是在**设置页**里呈现当前音频后端与设备状态，
而不是把它二次塞进播放页的「更多」弹层 —— 与 `PlaybackSettingsScreen` 同源、可长驻。

**若产品坚持要在播放页可见**（比如「正在 USB 独占输出」是个用户必须知道的状态），
建议只做**一行只读状态文字**放进信息卡的「音频格式」组末尾，**不做滑条、不做轮询** ——
状态从播放引擎已有的 `AudioFormatInfo` 旁边取，随状态流更新。

---

## 4. 缺口 C：旧版**其他页面**的菜单动作 —— 分类处理

`SongOptionsBottomSheet.kt`（旧版通用曲目菜单，22KB）里有几个动作，
播放页那个弹层**没有**，但 KMP 已在别处实现：

| 动作 | 旧版 | KMP 现状 | 建议 |
|---|---|---|---|
| 播放 | `onPlayClick` | 播放页本身就是播放中 | 不需要 |
| 收藏 | `onFavoriteClick` | 播放页歌名右侧 `ExpressiveLikeButton` | 已有，**别再加** |
| 加入播放队列 | `onAddToQueueClick` | `SongItem` 菜单已有（`HomeScreen.kt:332`） | 见下 |
| 下一首播放 | `onNextClick`（`insertNext`） | **无** `insertNext` API | 见下 |
| 删除 | `onDeleteClick` | 下载页 | 与播放页无关 |
| 设为铃声 | `onSetAsSoundClick` | — | **不做**（移动端专属，桌面无意义） |
| 关联云盘歌曲 | `onBindCloudClick` | — | 云盘功能未移植，**不做** |

### 4.1 「下一首播放」—— 唯一值得补的 API 缺口

旧版走 `playbackViewModel.insertNext(song)`。KMP 的
`PlaybackController`（`core/.../PlaybackController.kt`）目前只有：

```
addToQueue(mediaId)      // 追加到队尾
removeQueueItem(index)
moveQueueItem(from, to)
playAt(index)
```

**没有「插入到当前曲目之后」**。「下一首播放」是点唱机最常用的动作之一，值得补。

实现建议（`core` 侧，**给接口加成员必须带默认实现**，见 AGENTS.md §5）：

```kotlin
// PlaybackController.kt
suspend fun insertNext(mediaId: String) {
    // 默认实现：移到 currentIndex+1 再插入
    // ⚠️ 别写成空实现 —— 跨平台实现方会静默失效
}
```

`PlaybackControllerImpl` 里按现有 `addToQueue`（L285）同款写法实现，
**注意 `currentIndex` 与队列 mutable list 的同步**（这是队列三个方法最容易错的地方，
`moveQueueItem` 已有先例可参照）。

### 4.2 播放页的「更多」要不要加「加入队列 / 下一首播放」

**建议不加**。理由：
- 当前曲目**已经在队列里**，「把当前这首加入队列」是自我指涉的怪动作；
- 「更多」弹层现在正好是 2×2 四宫格，加两个会变成 2×3 —— 版式要重排，
  且和一屏能读完的节奏冲突；
- 真正需要这两个动作的是**列表页**（首页/搜索/歌单）的曲目菜单，那里 `SongItem` 已经有了。

**唯一例外**：补完 `insertNext` 后，在**列表页** `SongItem` 菜单里加「下一首播放」一行
（`MoreOptionsSheet.kt` 的 `SongOptionsSheet` 加一个可选参数，与 `onAddToQueue` 并列）。
这属于传播既有能力，不属于播放页。

---

## 5. 缺口 D：宽屏右侧面板没有「相似歌曲」—— 可选，低优先

旧版评论页顶部有 `SimilarSongsSection`（`PlayerScreen.kt:1349-1382`），
进评论页时 `onFetchSimilarSongs()` 拉一次。

KMP 侧：`MusicRepository.getSimilarSongs(seedId)` **已存在**（L40），
`MusicSourceFromApi.parseFmSongs` 也已就绪 —— **只有 UI 没接**。
首页有「相似歌曲」入口（`HomeScreen.kt:165/1134`），是虚拟歌单（id `-103`）。

**建议**：这一项**不属于「更多按钮」的移植范围**，且播放页评论区塞相似歌曲会
让「评论」这一页承担两个职责。如果要做，建议放在**宽屏右侧面板新增第四个页签**，
或复用现有虚拟歌单页（`PlaylistDetailScreen` 已能承载 `-103`）——
**不要**塞进「更多」弹层。

若要立项，单独开一轮，与本方案解耦。

---

## 6. 落地顺序与前置约束

### 6.1 建议顺序

| 优先级 | 项 | 改动面 | 风险 |
|---|---|---|---|
| **P0** | 缺口 A：宽屏补「更多」入口（复用 `PlayerMoreBottomSheet`，状态上移） | `PlayerScreen.kt`、`DesktopPlayerScreen.kt` | 中（状态提升要动两套布局的参数表） |
| **P1** | 缺口 A 附带：弹层内动作回调 **单一来源**（删掉可能出现的第二份实现） | 同上 | 低 |
| **P2** | 缺口 C.1：`core` 补 `insertNext(mediaId)`（带默认实现）+ `SongItem` 菜单加「下一首播放」 | `core` + `MoreOptionsSheet.kt` + 4 个调用页 | 中（队列索引同步是雷区） |
| **—** | 缺口 B（USB/DSD）、缺口 D（相似歌曲） | — | **本轮不做**，理由见 §3 / §5 |

### 6.2 并行会话约束（**动手前必读**）

当前 `git status` 显示**多个文件被别的会话在途改动**（`M`）：

```
AGENTS.md, AppModel.kt, MusicRepository.kt, AccountScreen.kt, HomeScreen.kt,
MainScreen.kt, SearchScreen.kt, TimeFormat.kt, MusicSource.kt,
MusicSourceFromApi.kt, BackendProvider.kt, PlatformSupport.kt(×2), ProviderFactory.kt,
PROVIDER_DEV_GUIDE.md
```

另有未跟踪在途文件：`SocialRepository.kt`、`AlbumCard.kt`、`ArtistCard.kt`、
`AlbumDetailScreen.kt`、`ChatScreen.kt`、`MessagesScreen.kt`、`UserProfileScreen.kt`、
`MiguProvider.kt`、多个 `*PreviewTest.kt`。

⚠️ **P2 会碰 `HomeScreen.kt`、`SearchScreen.kt`（都在 `M` 列表里）** ——
这两个文件正在被别的会话改。**必须在它们那轮工作提交或 stash 之后再动**，
否则会和在途工作互相覆盖（本仓库整包被删过一次）。

**P0 只碰 `PlayerScreen.kt` / `DesktopPlayerScreen.kt`，两者都干净，可以立刻开工。**

### 6.3 交付前验证（AGENTS.md §1 硬要求）

1. `:app:compileKotlinDesktop` + `:core:compileKotlinDesktop`（P2 涉及 core）；
2. 测试结论**只认** `**/test-results/**/TEST-*.xml` 里的
   `failures="0" errors="0" skipped="0"` —— 端到端测试被 `assumeTrue` 跳过时**同样 BUILD SUCCESSFUL**；
3. **版式必须离屏渲染出图核对**（宽屏 + 窄屏 × 浅色 + 深色，共 4 张），
   编译和单测都量不到宽度、看不见对齐。
   `PlayerScreen` 目前**没有** preview 测试，需新建（参照 `SettingsLayoutPreviewTest.kt` 的写法）。

### 6.4 改 `PlayerScreen.kt` 时的两个已知雷区

- ⚠️ **同一文件一次只发一个 `Edit`**（AGENTS.md §2）：同消息内多个 `Edit` 会互相覆盖，
  **被覆盖的那个仍然报成功**。改完用 `Grep`（走磁盘）复核。
- ⚠️ **`rememberScreenModel` 只能在 `Screen` 子类成员里调**（AGENTS.md §5）。
  本方案若要在 `PlayerScreenContent` 里取数据，**不能**直接调 ——
  必须在 `PlayerScreen.Content()` 里建好再当参数传下去（`PlaylistDetailScreen` 是正确范例）。

---

## 7. 一句话总结

「更多」按钮与菜单在 KMP 版**已经移植完成**（含 Expressive 收敛）。
真正要做的只有一件事：**把窄屏已有的这套弹层接到宽屏 `DesktopPlayerScreen` 上**
—— 那是当前唯一「功能对用户完全不可达」的缺口。其余项要么不该做（USB/DSD 面板），
要么属于别的页面（`insertNext` / 相似歌曲），建议解耦、单独立项。
