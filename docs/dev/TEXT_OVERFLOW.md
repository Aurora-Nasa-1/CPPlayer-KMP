# 文本溢出治理方案（省略号问题）

> **目标**：解决「歌单名 / 歌名 / 歌手 / 其他文字过长出现省略号」，
> 且**静态观感与今天逐像素一致** —— 不改宽度、不改行高、不改字号、不改颜色、不改对齐。
>
> 状态：**P0 已落地**，S3 跑马灯已在焦点位启用（2026-10-07）。落地按文末 §7 分期执行。

---

## 1. 根因：为什么一定有省略号

省略号不是 bug，是「**有限容器 × 无限文本**」的必然结果。可动的只有三个维度：

| 维度 | 做法 | 代价 | 结论 |
|---|---|---|---|
| ① 给更多空间 | 放宽宽度 / 增加行数 / 卡片变高 | **改变布局** | ❌ 违反「保留原有视觉」 |
| ② 少显示几个字 | 智能省略（省略号挪位置） | 仍有一个省略号，但**次要信息被保留** | ✅ 静态、零交互 |
| ③ 让空间随交互展开 | 悬停浮层 / 跑马灯 / 右键复制 | 静态不变，动态才出现 | ✅ 主力手段 |

**所以正解 = ② + ③ 按场景分配，① 只在少数「本来就该换行」的地方用。**

> 反例（不要做）：把 `SongItem` 的 `maxLines` 改成 2 —— 列表行高立刻变 20dp，
> 一屏少看两首歌，这是把「看不见全名」换成「少看内容」，更糟。

---

## 2. 现状盘点（2026-10-06 实测）

```
TextOverflow.Ellipsis  101 处 / 30 个文件
maxLines >= 2           21 处      ← 多行场景，不能按单行逻辑处理
ClickableText/BasicText  5 处      ← 歌手名带点击区，不能换成普通 Text
```

Top 5 重灾区：`HomeScreen`(15) / `DownloadsScreen`(7) / `BentoCards`(6) /
`DesktopPlayerScreen`(4) / `MoreOptionsSheet`、`PlayerMoreBottomSheet`、`AlbumCard`、`SimilarSongsPanel`(各 4)。

**结构性结论**：现有 101 处全是**裸 `Text(text, maxLines=1, overflow=Ellipsis)` 就地手写**，
没有任何统一入口 ⇒ 不可能靠改 101 个点解决，必须先造一个组件再收敛。

---

## 3. 方案总览：一个 `CpText` + 四档揭示策略

### 3.1 新增统一入口 `CpText`

签名与 `Text` 高度兼容（绝大多数调用点可机械替换）：

```kotlin
// app/src/commonMain/kotlin/cp/player/app/ui/component/text/CpText.kt

/** 溢出揭示策略。 */
enum class CpTextReveal {
    /** 不揭示：只做智能省略。与现状观感一致。 */
    None,
    /** 指针悬停时揭示（桌面端主力）。无指针设备时天然退化为 [None]。 */
    Hover,
    /** 溢出即横向滚动。**只允许用在"焦点位"**，见 §5.1。 */
    Marquee,
    /** 默认：焦点位→Marquee，其余→Hover。 */
    Auto,
}

/** 省略号落点。 */
enum class CpEllipsisMode {
    /** 默认：与现状 100% 一致的尾部省略。**新接入点一律先用这个**。 */
    Tail,
    /** 中间省略：`/music/…/track.flac`（路径类）。 */
    Path,
    /** 保留尾部扩展名：`一个非常非常长的歌….flac`（文件名类）。 */
    Filename,
    /** 首尾各留一半（歌单名 / 长标题）。 */
    Middle,
}

@Composable
fun CpText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = 1,
    // ↓ 新增，全部有默认值 ⇒ 未迁移的调用点保持现状行为
    reveal: CpTextReveal = CpTextReveal.Auto,
    ellipsisMode: CpEllipsisMode = CpEllipsisMode.Tail,
    /** 焦点位标记（正在播放 / 当前详情页标题）。只有它为真才允许自动滚动。 */
    emphasized: Boolean = false,
)
```

配套重载（覆盖剩下 5 处非 `String` 场景）：

* `CpText(text: AnnotatedString, …)`
* `CpClickableText(text: AnnotatedString, onClick, …)` —— 接替 `TrackArtistText` 里的 `ClickableText`

### 3.2 四档策略（按场景自动选，也可手动钉死）

| 档 | 适用 | 行为 | 静态视觉 |
|---|---|---|---|
| **S1 智能省略** | 文件名/路径类文本 | 省略号挪到信息量最低的位置 | 仅省略号位置变（默认关闭） |
| **S2 悬停揭示** | 列表行 / 卡片标题 / 副标题（90% 场景） | 指针悬停 250ms → 就地浮出完整文本卡片 | **完全不变** |
| **S3 跑马灯** | 焦点位：正在播放的歌名/歌手、桌面标题栏、播放页大标题 | 溢出即缓慢横向滚动，停 1.2s 再滚 | 完全不变（只有内容在动） |
| **S4 兜底复制** | 桌面端所有被截断文本 | 右键菜单追加「复制完整标题」 | 完全不变 |

**触屏（Android）**：没有 hover，`PointerEventType.Enter` 不会由手指触发 ⇒
S2 天然降级为不显示，S3 在焦点位照常滚动，S4 换成「长按 → 详情/复制」。
列表项本身在触屏上**接受截断**（点进详情页本来就能看全），这是显式取舍，不是遗漏。

### 3.3 S2「就地浮出」而不是 Tooltip

不用 `TooltipBox`（material3 还是 alpha 线，延迟与位置不可控，且视觉与 M3 Expressive 不一致）。
自绘 `Popup`：

* 位置：`onGloballyPositioned` 拿 `positionInWindow()`，浮层**贴着原文左上角**展开；
* 形态：`Surface(shape=medium, tonalElevation=6.dp)` + 内边距，**最多扩到窗口边界**；
* 内容：完整文本，**允许换行到 3 行**（不受原 `maxLines` 限制）；
* 时机：`Enter` 后 250ms 显示，`Exit` 立即隐藏；滚动/拖动列表时取消（监听 `PointerEventType.Move` + 列表滚动状态）；
* 非模态、不吃点击 —— 不会挡住行点击。

> 为什么不选「悬停就滚」：长文本（如 80 字的歌单名）滚一轮要好几秒，读不完还伤眼；
> 浮层一屏看全、可停留、可复制。焦点位才用滚动，因为那是「目光本来就在那儿」的地方。

---

## 4. 关键实现骨架（P0 已落地，与文档的出入见 §4.4）

### 4.0 P0 落地清单（2026-10-06）

* `app/src/commonMain/.../ui/component/CpText.kt` —— 组件 + `fitText`/`ellipsize`；
* `app/src/commonMain/.../ui/component/CpHoverReveal.kt` —— 悬停浮层；
* 接入点：`SongItem`（歌名/歌手行）、`PlaylistItem`（歌单名/副标题）、
  `MiniPlayer`（标题/歌手）、`DesktopTitleBar`（页面标题，两处分支）；
* 验收：`app/src/desktopTest/.../ui/preview/CpTextPreviewTest.kt`
  —— **像素级比对**证明默认档与治理前逐字节一致（比人眼看图硬）。



### 4.1 溢出检测（不额外测量、不引入 Subcompose）

不用 `BoxWithConstraints`（每行一次 subcompose，LazyColumn 里太重）。
用 `onTextLayout` 回调里的现成结果：

```kotlin
var overflow by remember { mutableStateOf(false) }
var maxWidthPx by remember { mutableIntStateOf(0) }

Text(
    text = shownText,
    onTextLayout = { layout ->
        // ⚠️ 只写一次：overflow 一旦为 true 就不再回落，避免"重组→再测量→再重组"震荡
        if (!overflow && layout.hasVisualOverflow) {
            maxWidthPx = layout.layoutInput.constraints.maxWidth
            overflow = true
        }
    },
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
    …
)
```

### 4.2 智能省略（单向 + 缓存 ⇒ 不会震荡）

```kotlin
val measurer = rememberTextMeasurer()
val fitted: String = if (overflow && maxLines == 1 && ellipsisMode != Tail) {
    remember(text, maxWidthPx, style, ellipsisMode) {
        fitText(measurer, text, style, maxWidthPx, ellipsisMode)
    }
} else text

/** 二分找"能塞下的最大字符数"，再按 [mode] 组装 `head + "…" + tail`。 */
private fun fitText(
    measurer: TextMeasurer, text: String, style: TextStyle,
    maxWidthPx: Int, mode: CpEllipsisMode,
): String {
    if (measurer.measure(text, style, maxLines = 1).size.width <= maxWidthPx) return text
    var lo = 0
    var hi = text.length
    while (lo < hi) {                       // 二分：约 log2(n) 次测量，n=80 时 ≈ 7 次
        val mid = (lo + hi + 1) / 2
        val w = measurer.measure(
            ellipsize(text, mid, mode), style, maxLines = 1
        ).size.width
        if (w <= maxWidthPx) lo = mid else hi = mid - 1
    }
    // 兜底：结果仍然放不下 ⇒ 退回原样，让 Text 自己截，**绝不递归再算**
    val result = ellipsize(text, lo, mode)
    return result.takeIf { measurer.measure(it, style, maxLines = 1).size.width <= maxWidthPx }
        ?: text
}
```

> **收敛性证明**：`fitText` 只在 `overflow == true` 时执行，`overflow` 单向置位不回落，
> 且结果用 `remember(text, maxWidthPx, style, mode)` 缓存 ⇒ 每组 key 最多算一次。
> 这是本方案唯一的测量开销点，必须钉死在单元测试里（见 §6）。

### 4.3 悬停浮层

```kotlin
// ui/component/CpHoverReveal.kt（P0 已落地）
@Composable
fun CpHoverReveal(
    fullText: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var anchor by remember { mutableStateOf(IntOffset.Zero) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(hovered) {
        if (hovered) { delay(HOVER_REVEAL_DELAY_MS); visible = true } else visible = false
    }
    Box(
        modifier.hoverable(interaction)
            .onGloballyPositioned { anchor = it.positionInWindow().toIntOffset() },
    ) {
        content()
        if (visible) {
            Popup(alignment = Alignment.TopStart, offset = anchor,
                   properties = PopupProperties(focusable = false)) { RevealCard(fullText, style) }
        }
    }
}
```

> **跨平台判据不需要 expect/actual**，但**悬停检测也不能用 `onPointerEvent(Enter)`**
> —— 它是 Compose Desktop 的扩展，`commonMain` 里不存在（`WallPointerZoom` 的 KDoc
> 同款教训）。用 foundation 通用的 `hoverable` + `collectIsHoveredAsState()`：
> 触屏不派发 hover，天然降级；外接鼠标/触控笔时反而**应该**有悬停浮层，
> 用平台判断会把它们一起关掉。

### 4.4 实现期修正（与上面骨架的出入，以这里为准）

1. **位置**：组件放在 `ui/component/` 平铺（与既有组件一致），不是 `ui/component/text/` 子包。
2. **`CpEllipsisMode` 只有 `Tail / Middle / Filename` 三个值**：原设计的 `Path`
   （保留头尾目录）由 `Middle` 覆盖，不值得多一个分支。
3. **悬停检测不用 `onPointerEvent`**：它是 Compose Desktop 扩展，`commonMain` 里
   **不存在**（`WallPointerZoom` 的 KDoc 同款教训）。改用 foundation 通用的
   `hoverable(interactionSource)` + `collectIsHoveredAsState()` —— 触屏不派发 hover，
   天然降级，还不用写 expect/actual。
4. **`basicMarquee` 的参数名是 `repeatDelayMillis`**（不是 `delayMillis`）——
   compose 1.12 的签名，`javap MarqueeDefaults` 只导出 `RepeatDelayMillis` 可证。
5. **溢出处理在下一帧才生效**：检测发生在 `onTextLayout`（第一帧布局结束后）。
   默认 `Tail` 档无感知差异；`Middle` / `Filename` 是省略号位置的一帧切换。
   离屏测试必须渲染多帧。
6. **MiniPlayer 的跑马灯已于 2026-10-07 开启**（`emphasized = true`）。P0 时曾因
   两个 `Text` 挂着 `sharedBounds`、担心 `basicMarquee` 插的 layout 节点会改变共享元素
   上报的 bounds 而推迟；实测**顾虑不成立** —— `basicMarquee` 只把无限宽约束发给**它的
   子节点**，自己上报的仍是父级给的受限尺寸，且它在 `sharedBounds` **内层**，共享元素量到的
   bounds 一字不变。守卫测试 `CpTextMarqueeTest`。
7. ⚠️ **跑马灯曾经整个是死代码（2026-10-07 修）**：`CpTextReveal` 的 `Marquee` 分支只由
   `emphasized` 或显式 `reveal = Marquee` 选中，而 P0 落地时**没有任何调用点传这两个值** ——
   于是 `Auto` 永远解析成 `Hover`，长文本**依旧显示省略号**，S3 等于没做。
   修法：在「焦点位」显式开启（正在播放的 `SongItem`、`MiniPlayer` 标题/歌手、
   `PlayerScreen` 顶栏大标题、`DesktopPlayerScreen` 大标题）。
   **教训**：跑马灯这类「随时间变化」的行为，编译 + 静态出图都量不到 ——
   静态图本来就不动。必须沿时间轴 `render(nanoTime)` 多帧比对（见 `CpTextMarqueeTest`）。


---

## 5. 三条硬约束（违反就会翻车）

### 5.1 跑马灯**只允许**焦点位，且天然限流

`LazyColumn` 里若每行都在滚：一屏 15 行动画同时跑，帧率直接崩，且视觉上像屏保。
约束：`Marquee` 只在 `emphasized == true` 时生效；而 `emphasized` 在一个界面里
**天然只有 1 个**（正在播放那一首 / 当前详情页标题）⇒ 不需要额外的全局限流器。
守卫测试钉死这条（§6）。

### 5.2 `sharedBounds` 场景必须出图验证

`MiniPlayer` 的歌名/歌手带 `Modifier.sharedBounds(...)`（共享元素动画）。
外面套 `Box` + `Popup` / `basicMarquee` 后，参与动画的节点多了一层 —— **可能匹配不上或动画跳变**。
P0 阶段必须先出图比对 hover 前后与播放页切换动画。

> **2026-10-07 结论**：跑马灯节点位于 `sharedBounds` **内层**，只把无限宽约束发给子节点、
> 自身上报尺寸不变 ⇒ 共享元素 bounds 不受影响，已开启（见 §4.4.6）。悬停浮层的 `Popup`
> 是独立窗口、不参与组合树尺寸，同样安全。

### 5.3 所有新增 UI 文案走 i18n 文案层

「复制完整标题」这类新增文案必须进 `CpStrings`（中英两套），
禁止硬编码中文 —— 见 `docs/dev/I18N.md`。

---

## 6. 验收（按仓库硬约定）

| 项 | 做法 |
|---|---|
| 静态回归 | 离屏出图：中/英 × 宽/窄 × 列表/卡片/播放页，**无 hover** 与现状逐像素比对 |
| 动态验收 | 出图 hover 态浮层、跑马灯首尾帧；确认浮层不越窗口边界、不吃点击 |
| 逻辑单测 | `SmartEllipsisTest`：`fitText` 收敛（同参两次调用结果相同）、结果宽度 ≤ 上限、扩展名保留、退化为原文时不递归 |
| 策略单测 | `CpTextRevealTest`：`emphasized=false` 时不得产生 marquee；`maxLines>1` 时不走智能省略 |
| 跑马灯 | `CpTextMarqueeTest`（沿时间轴 `render(nanoTime)` 多帧）：`emphasized=true` ⇒ 帧间必变（真在滚）且末帧 ≠ 尾截形态；`emphasized=false` ⇒ 帧间**逐字节一致**（不滚）；显式 `reveal=Marquee` ⇒ 无视 emphasized 直接滚 |
| 守卫测试 | `TextOverflowGuardTest`（源码扫描）：`app/src` 下出现 `TextOverflow.Ellipsis` 的文件必须在 `CpText*` 或白名单内 ⇒ 防新增裸 Text 回潮（照 `NestedScrollGuardTest` 风格） |

---

## 7. 落地分期

| 阶段 | 内容 | 风险 |
|---|---|---|
| **P0** | 新建 `ui/component/text/`（`CpText` + `CpHoverReveal` + `fitText`）；接 4 个最痛点：`SongItem` 歌名/歌手、`PlaylistCard` 名、`MiniPlayer` 标题、`DesktopTitleBar`；出图验证 `sharedBounds` | 低 |
| **P1** | 列表卡片群：`HomeScreen`(15)、`DownloadsScreen`(7)、`BentoCards`(6)、`AlbumCard`、`ArtistCard`、`SimilarSongsPanel`、`LibraryScreen`、`MainScreen` | 低（机械替换） |
| **P2** | 弹层与详情页：`MoreOptionsSheet`、`PlayerMoreBottomSheet`、`QueueBottomSheet`、`PlaylistPickerSheet`、`DevicePickerSheet`、`AlbumDetailScreen`、`PlaylistDetailScreen`、`PlayerScreen`、`DesktopPlayerScreen` | 中（弹层内 hover 需处理层级） |
| **P3** | 剩余散点 + 桌面右键「复制完整标题」 + `TextOverflowGuardTest` + 全量出图 | 低 |
| **可选** | 设置项「长文本：截断 / 悬停展开 / 自动滚动」（`SettingsKit` 已有设置行基建） | 需你确认 |

### 关于「本来就该换行」的那一小撮（21 处 `maxLines>=2`）

设置项描述、弹层正文这类文本**放开了更好读**，不属于"省略号问题"。
建议单独列一张清单交你逐个确认 —— 它们的改动会**改变高度**，与「保留原有视觉」冲突，
所以**不进默认分期**。

---

## 8. 不做的事（明确排除）

* ❌ 不改 `maxLines` 让列表行变高（把"看不见全名"换成"少看内容"）
* ❌ 不自动缩字号（破坏既有字号体系；仅可作为设置项里的可选项）
* ❌ 不引入 `TooltipBox`（material3 alpha 线，延迟/位置不可控）
* ❌ 不在触屏列表行上挂长按揭示（`SongItem` 长按已被**多选**占用，会打架）
