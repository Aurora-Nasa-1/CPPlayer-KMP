# 参考 Kazumi / PixelPlayer 的 M3 Expressive 借鉴（体验版）

> 参照对象：`reference/PixelPlayer`（Android Compose / M3 Expressive，**重点**）、`reference/Kazumi`（Flutter / M3）
> 分析基准：material3 `1.11.0-alpha07`（本项目实际编译版本；令牌与 API 由 `javap` 从 jar 读出）
> 日期：2026-10-10
> 前置：[`../history/VISUAL_EXPRESSIVE_PLAN.md`](../history/VISUAL_EXPRESSIVE_PLAN.md)（对标 Kazumi 的第一轮，已落地）

---

## 0. 这一版换了视角

初版按**技术项**列（形状刻度 / 字体轴 / 组件清单），结论不算错，但读不出「为什么它好看」——
因为**最值钱的那部分设计，在代码结构上完全看不出来**：它们只是一个 `alpha`、一个阈值、
一个 `delay`，散在几百行里，而用户感受到的是「这个 App 很贵」。

所以这一版按**用户能感觉到什么**来组织。筛选标准只有一条：

> **用户说不出这个设计，但把它拿掉，他会觉得「廉价」。**

每条给出：**现象**（用户看到什么）→ **机制**（代码里那个不起眼的数）→ **我们的现状** → **怎么抄**。
偏技术项、但确实有效的清单收在 §11 附录，避免丢失。

---

## 0.5 实施状态（2026-10-10 第一批）

第一批 7 项里已落地 **6 项**（全部编译 + 离屏出图核对通过）：

| 项 | 文件 | 状态 |
|----|------|------|
| 传输键 **weight 联动** | `ui/component/PlaybackControls.kt`（整体重写） | ✅ 按下者 ×1.1、另两颗 ×0.65 |
| **均衡器暂停塌成三个点** | `ui/component/ExpressiveKit.kt` | ✅ 暂停态是静态三点，不挂无限动画 |
| **播放/暂停按钮形状随状态变** | `ui/component/ExpressiveKit.kt` | ✅ 播放中 `size/2`（胶囊）/ 暂停 `size*0.36` |
| **跑马灯只在播放时滚** | `ui/component/CpText.kt` + `MiniPlayer.kt` + `PlayerScreen.kt` | ✅ 新增 `marqueeEnabled`，false 时降级为悬停揭示 |
| **暂停封面缩 95%** | `ui/screen/PlayerScreen.kt` | ✅ 260ms tween（刻意不用 spring） |
| **关于页 hero 化** | `ui/screen/AboutScreen.kt` | ✅ 大号主色应用名 + 版本 + 定位 + 更新日志入口 |
| **骨架屏** | **新增** `ui/component/CpShimmer.kt` + `ui/screen/LibraryScreen.kt` | ✅ 曲库首屏接上 |
| 底部渐变消隐 | — | ⏸️ **推迟**：需要 `ui/screen/MainScreen.kt`，当时正被另一个并行会话改动 |

**离屏出图实测证据**（浅/深两套）：

- 关于页 hero 在浅/深主题下都正确，标题 / 版本 / 定位 / 更新日志**左边缘对齐**（同一竖线）；
- 骨架屏在深色下用 `surfaceContainerHigh→Highest`，**可见但不刺眼**；
- 主控件中央按钮经**像素反解**确认是 stadium：高 72dp、最大宽 ~190dp、距顶 3px 处宽 148px ⇒ 反解半径 ≈37px = 满圆；
- 均衡器两态放大 4× 后逐像素量：**播放中三根棒高 28 / 40 / 36 px（各不相同，动画真的在跑）；暂停是 8×8 px 的方点**。这正是「形状表达状态」。

> ⚠️ **踩到的坑（写进 `TOPICS.md` 级别的）**：`ImageComposeScene.render()` **不传 `nanoTime`**
> 时，无限动画停在 `initialValue`。第一次出图时「播放中」的均衡器拍到的其实是最矮那一帧
> （0.3），与暂停态的点几乎一样高 —— **等于没验**。必须 `render(t)` 手动推进时钟。
> 本仓库的 `CpTextPreviewTest` 只用 `render()`，因为它验的是静态版式；**凡是验动画的预览都要推进时钟**。

**同时订正一处文档错误**：初版本文写「我们的分组标题是灰的」是**错的** ——
`SettingsKit.SettingsSection`（`SettingsKit.kt:131`）早就是 `primary` 色。详见 §5 第 4 条。

### 第二批（2026-10-10 当天继续）

| 项 | 文件 | 状态 |
|----|------|------|
| **底部渐变消隐** | `ui/component/MiniPlayer.kt` | ✅ 用 `drawBehind` 画在节点边界**之外**（向上 28dp）——不参与布局，也不污染宿主的 `onBarHeight` 尾留白。改在 `MiniPlayer` 内部 ⇒ 两个宿主（`App.kt` 的路由页 / `MainScreen.kt` 的 tab）**自动同时生效**，不必碰 `MainScreen.kt` |
| **沉浸歌词（影院模式）** | `ui/component/LyricContent.kt` + `ui/screen/PlayerScreen.kt` | ✅ 无操作 6s → 歌词放大 1.25×、宿主收起底部浮动工具条；任意指针交互复位。只在播放中进入 |
| **seek 后「保持目标」** | `ui/component/ExpressiveKit.kt`（`CpSeekBar` + `CpPlainSeekBar`） | ✅ 松手后滑条**停在放手位置**，等播放追上来（4% 时长 / 500ms 下限 / 5s 安全网）；换曲立刻放手 |

**第二批里两个刻意的「不做」，理由都写进了代码注释**：

1. **滑条不做逐帧插值**（只做「保持目标」）。原因：歌词的位置是在**绘制阶段**读的
   （lambda provider，每帧只重绘），而 `Slider` 的 `value` 是**组合期参数** ——
   喂逐帧变化的值就是让整条滑条每秒重组 60 次。而收益是
   `200ms / 曲长 × 轨道像素宽`：240s 的歌在 600px 轨道上是 **0.5px**（亚像素，看不见），
   只有 30s 级的短曲才会到 ~4px。波形那一路本来就有 `animateFloatAsState` 弹簧，已是连续的。
   ⇒ 为一个多数曲目下不可见的收益换 60Hz 重组，不划算。
   真要解决短曲目台阶，正确做法是把拇指改成**自绘 + 手势自接**（那时位置可以在绘制阶段读），
   而不是给 `Slider` 喂逐帧值。
2. **沉浸歌词不做上下渐变遮罩**。遮罩要么用固定色（但播放页背景是一条**竖向渐变**，
   固定色会在中间出现一条色带），要么用 `DstIn` 把内容本身淡出（但那要求
   `CompositingStrategy.Offscreen`，而歌词本来就在逐帧重绘，再套一层全尺寸离屏缓冲
   是实打实的开销）。两害相权先不做。

### 尚未做（附具体约束，便于下次接手）

| 项 | 为什么还没做 |
|----|-------------|
| **桌面滚动条表达性增强**（hover 膨胀 + 拖拽 A–Z 标签） | 三个障碍叠加：① 现有实现是 foundation 的 `VerticalScrollbar`，**不支持**膨胀与标签，必须整体换成自绘；② A–Z 标签需要把「当前排序字段的首字母」从**每个列表页**传下来，是穿过 `LazyScrollColumn` / `ScrollColumn` 的宽 API 改动；③ ⚠️ 滚条必须避开 `Undecorated(6dp)` 的窗口缩放抓手带（见 `DesktopScrollbars.desktop.kt` 的 `ScrollbarEdgeInset` 注释 —— 重叠时**拖滚条会变成缩放窗口**）。④ 交互行为**离屏渲染验不了**（离屏只验版面与配色）。⇒ 需要一次带真窗口的手动验证，单独排期 |
| **播放页 `expansionFraction` 门控编排** | 真正的价值来自「小播放器跟手长成大播放器」的 sheet，那是结构级改动（动 `MainScreen` / 双栏 / 返回键三处约定）。轻量版（在路由 push 后自己 stagger 一次入场）观感存疑、且会和已有的 `CoverFlight` 共享元素抢戏，**不如不做** |
| 歌词鱼眼（距离驱动 scale/padding/alpha/blur） | 需要第三方 `KaraokeLyricsView` 暴露行级渲染入口，或自研行渲染。属第三批 |
| 播放页局部主题 + 跨曲回退 + 封面 pager 预取 | 结构级，属第三批 |

---

## 1. 最重要的一条：PixelPlayer 的「贵」来自**同一个数驱动的编排**

### 现象

手指按住小播放器往上拖，**不是「打开一个页面」**，而是：

- 标题 / 歌手一边上浮一边淡入；
- 拖到约 **8%** 时封面出现；
- 拖到约 **42%** 时播放控制出现；
- 顶栏（收起箭头 / 投送 / 队列）在整段拖拽中同步淡入；
- **全程跟着你的手指，松手停在哪就是哪**。

拖到一半松手，看到的是内容**按顺序生长**出来，而不是「一个页面淡入」。

### 机制

`FullPlayerContent.kt:192` 接了一个 `expansionFractionProvider: () -> Float` —— 小播放器 ↔
大播放器的拖拽进度 `[0, 1]`。**所有子区块都读这一个数**：

| 区块 | 消费方式 | 出处 |
|------|---------|------|
| 标题 / 歌手 | `alpha = fraction`；`translationY = (1 - fraction) * 24f` | `FullPlayerContent.kt:2168-2172` |
| 顶栏 | `alpha = fraction` | `:689-695` |
| 专辑封面 | 门控阈值 `0.08` | `:1057` |
| 进度条 | 门控阈值 `0.08` | `:1778` |
| 控制区 | 门控阈值 `0.42` | `:1144` |
| 内容 / 占位符交叉淡入 | 出现 **260ms** / 消失 **140ms**（**不对称**） | `:2076-2093` |

门控组件是 `DelayedContent`（`:1971`）—— 一个 150 行的组件，专门做「按拖拽进度分阶段放行内容」，
还带 `switchOnDragRelease`（内容切换发生在**松手**时，而不是拖拽途中）。

### 我们的现状

播放页是 **Voyager 路由 push**（`PlayerScreen.kt:236` / `DesktopPlayerScreen.kt`），配一个自制的
共享元素封面飞行（`anim/CoverFlight.kt`，356 行，做得相当好）。但**没有那个连续量** ——
封面会飞过去，其余内容是一次性淡入的。所以「封面飞行」很惊艳，**但它周围的一切跟不上**。

### 怎么抄（分两档，不必一步到位）

- **轻量版（建议先做）**：不动导航结构。给播放页引入一个 `expansionFraction`，
  由**进入动画的进度**驱动（Voyager 转场进度与封面飞行进度取同一个值），
  让标题 / 进度 / 控制按 0.08 / 0.42 的门控依次出现。**代价小、观感提升最大。**
- **完整版**：桌面端把大播放器做成「从小播放器长出来」的 sheet（不 push 路由），
  才有真正的跟手拖拽。⚠️ 会动到 `MainScreen` / 桌面双栏 / 返回键三处既有约定
  （`AGENTS.md` §6），**属大工程，单独排期**。

---

## 2. 十个「用户能感觉到、代码结构看不出」的设计

### 2.1 暂停时封面缩到 95%

- **现象**：一暂停，封面轻微「收」一下，像屏住呼吸。
- **机制**：`albumArtScale = if (paused) 0.95f else 1f`，**260ms tween**（`FullPlayerContent.kt:1030-1034`）。
  注释写明：原来用 `spring(StiffnessLow)`，要 ~1s 才停，与后续手势重叠产生 60 帧无效化，
  所以换成确定时长的 tween。
- **我们**：无。
- **抄**：播放页封面加一个 `animateFloatAsState` 接 `isPlaying`。一行的事，但整个播放页会「活」过来。

### 2.2 播放/暂停按钮：播放中是胶囊，暂停时是圆角方

- **现象**：按钮**形状**随播放状态变 —— 不是换图标，是换形状。
- **机制**：`playPauseCornerPlaying = 60.dp` ↔ `playPauseCornerPaused = 26.dp`，
  `animateDpAsState(defaultSpatialSpec)`（`AnimatedPlaybackControls.kt:179-183`）。
- **我们**：`CpPlayPauseButton` 已有「按下时圆角收缩」（`ExpressiveKit.kt:555`），
  但**只跟按压走，不跟播放状态走**。
- **抄**：给 `corner` 加一个状态项 —— 播放中 `size/2`（胶囊），暂停 `size*0.36`（圆角方）。

### 2.3 三个传输键的权重联动（按下哪个哪个胀）

- **现象**：按「下一首」时，下一首那颗变宽，**另外两颗同时变窄** —— 整排像被捏了一下。
- **机制**：三个按钮用 `Modifier.weight()` 布局，权重是动画值（`AnimatedPlaybackControls.kt:139-143`）：

```kotlin
fun weightFor(button) = when (lastClicked) {
    button -> expansionWeight   // 1.1
    null   -> baseWeight        // 1.0
    else   -> compressionWeight // 0.65
}
```

- **我们**：`PlaybackControls.kt` 是等宽排列。
- **抄**：三个按钮从固定 `size` 改成 `weight(animatedWeight)`。
  **投入产出比最高的一处** —— 改动小，按下瞬间的「整排呼吸感」非常明显。

### 2.4 按钮先动、动作后发 + 图标状态机

- **现象**：点「下一首」，按钮先胀开，然后才切歌；**连点不会让图标闪烁**。
- **机制**：
  1. `launch { delay(180); onNext() }` —— **动画领跑动作**（`AnimatedPlaybackControls.kt:159-162`）。
     配合「乐观索引」，切歌看起来是瞬时的。
  2. `playPauseVisualState` + `pendingPlayPauseState` + `isPlayPauseLocked`：
     按了上/下一首后 **600ms 内锁定播放图标**（`:94-127`），避免连点时图标来回跳。
- **我们**：`PlaybackControls.kt` 直接调 `onNext()`。
- **抄**：至少加第 1 条（`delay(~160ms)` 再发动作），代价极小。

### 2.5 进度条是插值的，而且松手后**停在原地等播放追上来**

- **现象**：进度条**连续地**走，不是每半秒跳一格；拖动松手后，滑条**不会弹回去再追上**，
  而是稳稳停在你放手的位置，等播放追上来。
- **机制**（`FullPlayerContent.kt:1632-1850`）：
  - `rememberSmoothProgress(...)`：60fps 插值状态，原始位置采样间隔按场景自适应 ——
    **展开+播放中 180ms / 其他 500ms / 暂停 800ms**；**不可见时整个采样停掉**（省电）。
  - **保持目标**：`targetSeekFraction` 记住放手位置，直到「插值进度追到 4% 以内」
    或 **5 秒安全网**才释放（`:1722-1736`）。
  - 时间文字拆成独立 composable（`EfficientTimeLabels`），**不让时间刷新拖累滑条**。
- **我们**：`ExpressiveKit.CpSeekBar` 用「波形 + 透明 Slider」，位置来自 `positionMs`
  （引擎每 200ms 推进）⇒ **波形是 200ms 一跳的**；松手后由引擎值直接接管，会有一次「弹回再追上」。
- **抄**：把位置源换成插值状态 + 保持目标。这是**播放器「手感」的核心**，值得单独做一次。

### 2.6 歌词：距离驱动的鱼眼（Apple Music 那套）

- **现象**：当前行最大最亮，上下相邻行小一点、淡一点，更远的行**模糊**掉；
  切换行时是**弹**过去的，不是滑过去的。
- **机制**（`LyricsSheet.kt:1436-1481`）—— `distanceFromCurrent` 一个数同时驱动四件事：

| 属性 | 当前行 | ±1 行 | 更远 |
|------|-------|-------|------|
| scale | **1.1**（沉浸态 1.02） | 0.95 | 0.85 |
| 垂直 padding | **32dp** | 16dp | 8dp |
| alpha | 1.0 | 0.6 | 0.3 |
| blur | 0 | `distance × strength`（默认 2.5），**上限 10dp** | 同左 |

  外加两个容易被忽略、但决定「精致 or 粗糙」的细节：
  - **透明粗体占位**：当前行变粗会撑开行宽、导致整个歌词列表回流。它在后面放一份
    `Color.Transparent` 的**粗体**同文本占位（`:1553-1567`）⇒ 布局空间恒定，不抖。
  - **`transformOrigin` 跟随对齐方式**（`:1499-1506`）：左对齐的歌词从**左边**放大，
    不是从中心。绝大多数实现都做错了这一点，观感差别很大。

  弹簧：`StiffnessVeryLow + DampingRatioMediumBouncy`（慢而弹）。
- **我们**：`component/LyricContent.kt` 用第三方 `accompanist-lyrics-ui` 的 `KaraokeLyricsView`
  （逐字卡拉 OK 有了 ✅），但**没有距离驱动的缩放 / 透明度 / 模糊**，也没有「按行吸附到固定位置」。
- **抄**：**最能让用户「哇」的一处**。两条路：① 若该 view 暴露行 composable，
  在外面用 `LayoutInfo` 算每行 `distance` 喂进去；② 自研行渲染，只保留它的 `SyncedLyrics` 数据模型。
  **建议先探 ①**。

### 2.7 沉浸歌词：无操作自动「影院模式」

- **现象**：歌词页放着不动几秒，字号**放大 1.4×**、所有控件消失、上下出现渐变遮罩，
  屏幕变成纯粹的歌词。任何触摸 / 滑动立刻复位。
- **机制**：`fontScale = if (immersiveMode) 1.4f else 1f`（`LyricsSheet.kt:500-506`），
  控件 `AnimatedVisibility(!immersiveMode)`（`:883`），上下 `Brush.verticalGradient` 遮罩（`:861,874`），
  超时由 `LaunchedEffect(..., lastInteractionTime)` 驱动（`:490-495`），每次交互 `resetImmersiveTimer()`。
- **我们**：无。
- **抄**：加「超时 → 字号放大 + 控件隐藏」。**成本低、戏剧性强**，「一眼惊艳」里最便宜的一个。

### 2.8 跑马灯**只在播放时**滚动

- **现象**：歌名太长时横向滚动；**暂停就不滚了**，像画面跟着声音一起停住。
- **机制**：`canScroll = isPlayingProvider()`（`FullPlayerContent.kt:2186, 2226`）。
- **我们**：`CpText.kt` 的 `Marquee` 有，但**没和播放状态绑定**（`Hover`/`Marquee` 由 `emphasized` 决定）。
- **抄**：焦点位的跑马灯接 `isPlaying`。一行。

### 2.9 列表底部**渐变消隐**，不是硬切

- **现象**：滚到底部时内容**融进**底部栏，而不是被一条直线切断。
- **机制**：底部叠一层 `Brush.verticalGradient(Transparent → Transparent → surfaceContainerLowest)`，
  高度 = 导航栏 + mini player + 8dp（`HomeScreen.kt:496-513`）。
- **我们**：`DesktopScrollbars.kt` 的 `LocalMiniPlayerTailSpace` 只做**留白**（内容能在 mini player
  下面继续铺）。功能对，但视觉上是硬切。
- **抄**：在 `MiniPlayer` / 底栏那一层加一条同款渐变。**改动小、观感提升明显**，
  还能顺手缓解「小播放器看起来自带背景」的历史问题。

### 2.10 滚动条拖拽时显示 A–Z 索引字母

- **现象**：拖动右侧滚动条时，旁边浮出一个圆形气泡显示**当前首字母**（A / B / C…），
  像 iOS 通讯录。而且**换排序字段，字母跟着换**（按歌名 → 歌名首字母；按歌手 → 歌手首字母）。
- **机制**：`ExpressiveScrollBar(dragLabelProvider: (Int) -> String?)` +
  `ExpressiveScrollBarLabelResolvers.kt` 按 `SortOption` 分发取首字母；
  气泡是 40dp 圆形 `secondaryContainer`（tonal 6 + shadow 2），180ms 缩放淡入。
- **我们**：`DesktopVerticalScrollbar`（foundation 包装）只有裸滑块。
- **抄**：桌面端滚动条加拖拽标签 + 按排序字段取首字母。
  **桌面端用户会立刻感觉到「这个 App 是认真做过桌面的」**。

---

## 3. 播放页的「主题跟着歌走」

- **现象**：切到下一首，整个播放页（**只有播放页**）的配色跟着封面变，而且**平滑过渡**，
  不会闪一下系统色。
- **机制**（`scoped/SheetThemeState.kt`）：
  - 播放器 sheet 有**自己的 `ColorScheme`**（通过 `LocalMaterialTheme` 注入），**全局主题不动** ——
    列表页保持系统配色，只有播放页跟着封面走。
  - **跨曲回退**：新歌配色还没算出来时**沿用上一首的**（`lastAlbumScheme` + `lastAlbumSchemeSongId`），
    避免「闪一下系统色」。⚠️ 而**无封面的歌不走回退**，直接回落系统色，否则上一首的颜色会「粘住」。
  - **批量插值**：用 **1 个** `Animatable<Float>` 手动 lerp 整个 ColorScheme（39 个角色），
    代替 **68 个** `animateColorAsState` —— 每帧 State 读取从 68 降到 0。
  - 换色弹簧从 `StiffnessLow` 提到 `StiffnessMediumLow`，为的是**跟上变快了的封面横滑**。
- 配套的三个「感觉很快」的技巧：
  - **相邻封面预取**（`scoped/PrefetchAlbumNeighbors.kt`）：预取队列 ±1 首的封面 ⇒ 横滑**没有加载过程**。
  - **封面就是队列**：横滑封面 = 切歌，三档 `NO_PEEK / ONE_PEEK / TWO_PEEK` 控制邻封面露出量
    （`FullPlayerContent.kt:1041-1046`）。
  - **乐观索引**：按 next 时封面**先滑到预测的下一首**（`predictSkipCarouselIndex`），音频后到
    （`:460-498`）。
- **我们**：`ui/theme/ColorSource.kt` 已有 `COVER` 模式（跟随封面主色），但那是**全局换肤**，
  不是「只有播放页」；也没有跨曲回退、批量插值、封面预取。
- **抄**：分三步 —— ① 播放页注入自己的 `LocalColorScheme`（局部主题）；② 加跨曲回退；
  ③ 封面横向 pager + ±1 预取。

---

## 4. 手势与物理（「手感」就是这些数）

| 设计 | 机制 | 出处 |
|------|------|------|
| 拖拽越界**橡皮筋** | 只允许超出 `miniHeight * 0.2` | `SheetVerticalDragMath.kt:26-29` |
| **松手弹性随拖拽深度变化** | `dampingRatio = lerp(NoBouncy, LowBouncy, fraction)` —— 拖得越远弹得越欢 | 同上 `collapseSpringDampingForFraction` |
| **收起时先压扁** | `squash = lerp(1.0f, 0.97f, fraction)` | 同上 `collapseInitialSquashForFraction` |
| 目标状态三判据 | 距离 > 阈值 → 看方向；否则速度 > 阈值 → 看方向；否则 `fraction > 0.5` | `resolveVerticalSheetTargetState` |
| **预测返回**（Android 14+） | 返回手势里 sheet **跟着手指走**，松手才提交 / 回弹 | `PlayerSheetPredictiveBackHandler.kt` |
| 歌词页左右滑 = 上一首 / 下一首 | 拖动带进度反馈，提交时 `HapticFeedbackType.LongPress` | `LyricsSheet.kt:626-671` |
| **滑条区域吃掉垂直拖拽** | 内层 `detectVerticalDragGestures` 消费，避免调进度时把播放器拖下去 | `FullPlayerContent.kt:1803-1808` |
| 歌词页退出 | `scale → 0.92` + `translationY → 8% 高` + 圆角 32dp | `LyricsSheet.kt:618-625` |

- **我们**：`CoverFlight` 有弧线飞行（含 `sin` 缓动），但**没有橡皮筋、没有随深度变化的弹性、
  没有压扁、没有预测返回**。
- **抄**：优先级最高的两条是 **「滑条区域吃掉垂直拖拽」**（bug 级体验问题，建议先实测确认）
  与 **预测返回**（Android 端适用，桌面端不适用）。

---

## 5. Kazumi 的「关于」页为什么好看

- **现象**：一打开就觉得「这是个正经项目」，而不是「设置里的一个子页」。
- **结构**（`lib/pages/about/about_page.dart` + `about_widgets.dart`）：
  1. **Hero 头**：应用名用 `displayMedium` + **主色** + `w600` —— 一个巨大的彩色标题当门面；
     下面一行版本号（`onSurfaceVariant`），再一行一句话定位（`bodyLarge`）。
  2. **药丸按钮组**：`Wrap` 包「检查更新」+「更新日志」，间距 8dp，最小触达 48×48。
  3. **分组列表**：`ContentSection.group` → `SplitListGroup` → `AboutLinkTile`：
     36dp **圆角方形图标底**（`secondaryContainer` / `onSecondaryContainer`）+ 标题 + 副标题 +
     右侧图标（**外链用 `open_in_new`，内跳用 `chevron_right`** —— 图标就告诉你会发生什么）。
  4. **分组标题用主色**：`titleSmall` + `primary` + `w600`（`content_section.dart` 的 `SectionHeader`）。
     > ✅ **订正（2026-10-10 实施时核实）**：初版本文写「我们的分组标题是灰的」——**这是错的**。
     > `SettingsKit.SettingsSection`（`SettingsKit.kt:131`）早就是 `MaterialTheme.colorScheme.primary`
     > + `labelLarge` + `letterSpacing = 0.5sp`，与 Kazumi 的做法一致。
     > 当时看错的是 `UiFoundation.SectionHeader`（首页内容区块的标题，用 `titleLarge` 默认色）——
     > 那是**另一类东西**（页面内容区块，不是设置分组），内容区块标题用 `onSurface` 是对的，
     > 全都刷成主色只会变吵。**所以这一条不需要改。**
  5. **宽屏自动双列**：`maxWidth >= 640` 且字号缩放正常时，两个分组并排。
  6. 行的按压形状变形由 `SplitListRow.pressReporterOf(context)` 从**分组下传**。
- **PixelPlayer 的关于页**（`screens/AboutScreen.kt`）同一思路且更「重」：
  - `AboutHeroCard`：30dp 平滑圆角卡，圆形图标底（`primaryContainer`）+ 名称 + 标语 +
    **长按版本号触发彩蛋**（`HapticFeedbackType.LongPress`）+ 社区数据行 + 两个 52dp 社交 chip。
  - 列表用 `expressiveListShape(index, count)` 做**分段圆角**：首尾 22dp、中间 8dp（`:1034-1062`）
    —— 与我们的 `legacySegmentShape` 同构。
- **我们**：`screen/AboutScreen.kt` 是**一个纯设置页** —— 全是 `SettingsClickItem`，
  没有 hero、没有版本药丸、没有社交入口、分组标题是灰的。
- **抄**：**投入产出比最高的一处**。加 hero 头（大号主色应用名 + 版本药丸 + 一句话定位）
  + 两个社交 chip，分组标题改主色。**一天之内可完成，效果立刻可见。**

---

## 6. 其它「小而贵」的细节

| 设计 | 机制 | 出处 |
|------|------|------|
| 导航项选中 | 64×32 指示药丸 `MediumBouncy` 弹入 + 图标 scale 1.1 + 标签**延迟 50ms** 淡入 | `scoped/CustomNavigationBarItem.kt` |
| **均衡器暂停时塌成三个点** | `activity 1→0` 把三根棒 morph 成小圆点；棒高 = 两个正弦叠加（快相位 + **12s 慢游走**）避免肉眼看出循环 | `subcomps/PlayingEqIcon.kt` |
| 占位符与真内容**交叉淡入**且时长不对称 | 内容 260ms 进 / 140ms 出；占位 360ms 进 / 140ms 出 | `FullPlayerContent.kt:2076-2093` |
| 社交 chip 读屏只读一次 | `clearAndSetSemantics { contentDescription = …; role = Button }` | `AboutScreen.kt:696-699` |
| 音频元信息 chip | 格式 / 码率 / 采样率显示在时间行旁，**清空延迟 500ms** 避免切歌闪烁 | `FullPlayerContent.kt:1686-1696` |
| 歌词文字颜色**按对比度选** | `preferredContrastColor` + `contrastRatio` + `relativeLuminance` —— 保证任何背景色上都可读 | `LyricsSheet.kt:156-221` |
| 字体**自动缩放填充** | `AutoSizingTextToFill`：二分搜索字号直到刚好填满容器 | `subcomps/AutoSizingText.kt` |
| 行内文本**紧贴包裹** | `TightWrapText`：自定义 `Layout` + `drawText`，去掉行框多余留白 | `subcomps/TightWrapText.kt` |
| 转场「景深」 | 下层页 `dim 0→0.4`、圆角 `0→32dp`、blur `0→24dp`（可全局关） | `ScreenWrapper.kt` |
| 歌词行**吸附**到固定位置 | `highlightZoneFraction` + `calculateHighlightMetrics` 算出上下 padding，当前行 snap 进「高亮区」 | `LyricsSheet.kt:1899-1934` |
| 迷你播放器出现 | `miniAppearScale = lerp(0.985f, 1f, progress)`，260ms —— 1.5% 的缩放，几乎察觉不到，但「有生命」 | `scoped/SheetThemeState.kt` |

> ⚠️ 对应物：`ExpressiveKit.CpPlayingEqualizer` 已有三根棒 + `scaleY` 动画，
> 但**没有「暂停塌成点」**，而且它只在播放时挂载（所以也没有暂停态可言）。

---

## 7. 差距清单（按「用户感受」排序，不按技术分类）

| # | 用户感受 | 参照做法 | 我们的现状 | 目标文件 | 成本 |
|---|---------|---------|-----------|---------|------|
| 1 | 「关于页看起来是个正经项目」 | Hero 大号主色应用名 + 版本 + 一句话定位 + 社交 / 更新入口 | 纯设置页（全是 `SettingsClickItem`），没有门面 | `screen/AboutScreen.kt` | **低** |
| 2 | 「列表底部是融进去的，不是被切断」 | 底部渐变消隐 | 只留白，硬切 | `component/MiniPlayer.kt` / `MainScreen` | **低** |
| 3 | 「点一下整排按钮在呼吸」 | 传输键 weight 联动（1.1 / 0.65） | 等宽固定 | `component/PlaybackControls.kt` | **低** |
| 4 | 「播放页是活的」 | 暂停封面缩 95%；按钮形状随播放态变 | 无 | `PlayerScreen` / `ExpressiveKit.CpPlayPauseButton` | **低** |
| 5 | 「歌词页会自己进入影院模式」 | 沉浸歌词（字号 ×1.4、控件隐藏、渐变遮罩） | 无 | `component/LyricContent.kt` | **低** |
| 6 | 「暂停时均衡器变成三个点」 | `activity` morph | 无 | `ExpressiveKit.CpPlayingEqualizer` | **低** |
| 7 | 「骨架屏而不是空白转圈」 | Shimmer | 全仓 0 处 | 新 `CpShimmer` | **低** |
| 8 | 「进度条手感是连续的」 | 60fps 插值 + 松手保持目标 + 不可见停采样 | 200ms 一跳 + 松手弹回 | `ExpressiveKit.CpSeekBar` | 中 |
| 9 | 「拖到一半松手，内容按顺序长出来」 | 单一 `expansionFraction` 驱动全页编排 | 路由 push，一次性淡入 | `PlayerScreen` + `anim/CoverFlight.kt` | 中 |
| 10 | 「桌面滚动条能按字母找歌」 | 拖拽标签 = 排序字段首字母 | 裸滑块 | `desktopMain` 的 `DesktopVerticalScrollbar` | 中 |
| 11 | 「歌词是 Apple Music 那种鱼眼」 | 距离驱动 scale/padding/alpha/blur + 占位防抖 + transformOrigin | 第三方 view，无此效果 | `component/LyricContent.kt` | **中高** |
| 12 | 「切歌时配色跟着封面走，还不闪」 | 局部主题 + 跨曲回退 + 批量插值 + 相邻预取 | 全局换肤，无回退 | `ui/theme/` + 播放页 | 中高 |
| 13 | 「滑进度条不会把播放器拖下去」 | 滑条区吃掉垂直拖拽 | 未验证，需实测 | `ExpressiveKit.CpSeekBar` | 低（可能是 bug） |
| 14 | 「返回手势里播放器跟着手指走」 | 预测返回 | 无（Android） | `app-android` / `PlayerScreen` | 中 |

---

## 8. 建议的推进顺序

### 第一批（一周内可见，几乎零风险）

1. **关于页 hero 化** + 分组标题改主色（对齐 Kazumi）
2. **底部渐变消隐**
3. **传输键 weight 联动**
4. **暂停封面缩 95%** + **播放 / 暂停按钮形状随播放态变**
5. **跑马灯只在播放时滚**
6. **均衡器暂停塌成三个点**
7. **骨架屏**（列表首屏）

> 这七条互不依赖、都在既有组件内部改，**不需要动导航 / 主题 / 构建**，也不引新依赖。
> 建议一次做完再统一出图核对。

### 第二批（手感与编排）

8. `CpSeekBar` 插值 + 保持目标（播放器手感的核心）
9. 播放页 `expansionFraction` 门控编排（轻量版：复用进入动画进度）
10. 沉浸歌词
11. 桌面滚动条拖拽标签 + A–Z

### 第三批（结构级，单独排期）

12. 歌词鱼眼（先探第三方 view 能否插手行渲染）
13. 播放页局部主题 + 跨曲回退 + 封面 pager 与预取
14. 预测返回 / 完整版跟手 sheet

---

## 9. 明确**不采纳**的

| 项 | 为什么不 |
|----|---------|
| `com.github.racra:smooth-corner-rect-android-compose` | **Android-only**（artifact 名含 `-android-`），`commonMain` 用不了 ⇒ 走 `io.github.dev778g-me:korner:2.0.0`（MIT，KMP，同一套算法）或自研 |
| Google Play Services 动态字体（Montserrat） | Android-only + 依赖 GMS / 网络；我们已有更好的可变字体 |
| PixelPlayer 的 3 槽 `Shapes`（8/16/24） | 我们已是**官方 8 槽**（第一轮成果），照它改是倒退 |
| PixelPlayer 自研的 1565 行 carousel | material3 1.11 **自带** `carousel` 包（已 javap 确认有 `HorizontalMultiBrowseCarousel` / `carouselItem` / `CarouselItemDrawInfo`） |
| `XTRA` / `YOPQ` / `YTLC` 字体轴设置 | **字体里没有这三个轴**（解 `fvar` 实测：只有 `opsz` / `wdth` / `wght` / `GRAD` / `ROND` / `slnt`），静默无效 |
| `withPureBlackSurfaces` 只压 2 个角色 | 我们已踩过「全压黑 ⇒ 卡片与背景同色」并修正为「保留极暗灰阶」（`Theme.kt:193`），别退 |
| 连续圆角滑杆 | 破坏「圆角必须走 `MaterialTheme.shapes.*` 刻度」这条已落地的硬约定（`AGENTS.md` §6） |
| 大面积 blur 作为默认转场 | 与桌面三态渲染后端 + 流体背景冲突；建议只做 dim，blur 可选且默认关 |

---

## 10. 落地纪律（`AGENTS.md` §1，每次都要走）

- 编译跑 `:app:compileKotlinDesktop` **与** `:core:compileKotlinDesktop`；
  ⚠️ `commonMain` **整个源集一起编译** —— **看第一个报错的文件**，别「谁报错谁错」。
- 测试结论只认 `**/test-results/**/TEST-*.xml`，必须确认 `skipped="0"`。
- **改版式必须离屏渲染出图**（模板 `app/src/desktopTest/.../ui/preview/SidebarPreviewTest`），
  编译 + 单测量不到宽度、看不见对齐。
- 同一文件一次只发一个 `Edit`，改完 grep 复核。
- 动画纪律：**只在真的需要时挂载**无限动画（`ExpressiveKit` 里已有两条同源注释）；
  `effects` 系（颜色 / 透明度）**不带回弹**；**不要手写 `spring(...)`**，走 `CpMotion`。
- 引新依赖：读 `.module` 核 Kotlin / Compose 版本 + 跑 `./gradlew :app:suggestModules` 核 jlink 清单
  （同 hypnoticcanvas 的教训）。

---

## 11. 附录：仍然有效、但偏「技术项」的清单

这些不是体验层的「惊艳」，但确实是 PixelPlayer 比我们更到位的地方，一并保留：

| 项 | 参照出处 | 我们的目标 | 说明 |
|----|---------|-----------|------|
| **平滑圆角（squircle）** | `ShapeCache.kt`（全仓 **253 处**，`smoothness = 60`） | 新 `ui/theme/SmoothShape.kt` | 大半径下标准圆弧边缘会「折」；**只改大半径消费点**（28 / 32dp），小圆角不动 |
| **可变字体的 `opsz` / `wdth` / `GRAD`** | `Type.kt` / `DailyMixSection.kt` | `ui/theme/Font.kt` | 我们只用 `ROND`；`opsz` 恒为默认 18 ⇒ 57sp 的 display 用的是小字号骨架。⚠️ 轴值必须全进 `remember` key |
| **表达性滚动条** | `ExpressiveScrollBar.kt`（8dp 轨道 hover 膨胀到 28dp + `UnfoldMore`） | `desktopMain` 的 actual | 保留既有 `desktopScrollbarGutter` 与「95% 可见不画」两条约定 |
| **官方多浏览视差轮播** | PixelPlayer 自研 1565 行 → **改用 material3 自带** | `HomeScreen.BannerCarousel` | `@ExperimentalMaterial3Api`，在收口组件里 opt-in |
| **分段控件弹性形变** | `ToggleSegmentButton.kt`（选中圆角 spring 变形）/ `TabAnimation.kt`（邻项位移 12dp） | `SettingsKit.SettingsSegmentedItem` / 首页 Tab | ⚠️ `TabAnimation` 的「首次组合不播」守卫必须照抄 |
| **多图封面拼贴** | `CollagePatterns.kt`（5 套布局 + 形状混搭 + **-16dp 负间距叠压 + 2dp 描边**） | `PlaylistCard` 封面位 | 星形可照 `RoundedStarShape.kt`（60 行纯 `Path`）自研 |
| **调色板档位 / 圆角设置项** | `PaletteStyleSettingsScreen.kt` | 外观设置页 | 我们 `CpTheme` 的 `paletteStyle` 参数**已存在但无 UI**，等于做了一半；只暴露 `TonalSpot` / `Fidelity` / `Content` 三档 |
| **波形滑块拇指形变** | `WavySliderExpressive.kt`（圆点 → 拖动时 24dp 高竖条） | `ExpressiveKit.CpSeekBar` | 我们拇指仍是 M3 默认珠子；波形振幅可接 `isPlaying` |
| **空状态表达性容器** | `ExpressiveOfflineState.kt`（140dp 圆形渐变徽章 + spring 缩放入场） | `UiFoundation.ContentState` | **不新造组件**，直接升级既有 `ContentState` |

---

## 附：本次分析的复核命令

```bash
# material3 到底有没有 carousel（不要查文档）
JAR=$(cygpath -w ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.compose.material3/material3-desktop/1.11.0-alpha07/*/material3-desktop-1.11.0-alpha07.jar)
/e/java/bin/javap.exe -classpath "$JAR" androidx.compose.material3.carousel.CarouselKt

# 字体到底有哪些可变轴：Python 直接解 TTF 的 fvar 表
#   头是 8 个 uint16（16 字节）—— 用 7 个 H 会 struct.error
```

⚠️ Windows 上 classpath 分隔符是 `;`，路径必须 `cygpath -w`（见 `.workbuddy-ai/memory/MEMORY.md`）。
