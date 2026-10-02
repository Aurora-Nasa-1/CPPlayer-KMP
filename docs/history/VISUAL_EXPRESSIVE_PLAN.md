# CPPlayer-KMP 视觉优化方案 —— 对标 M3 Expressive

> 参照对象：`reference/Kazumi`（Flutter / Material 3）
> 分析基准：`material3 1.11.0-alpha07`（本项目实际编译版本，令牌值由 `javap` 从 jar 读出，非记忆）
> 分析日期：2026-10-01
>
> 本文只回答两件事：**改什么**、**为什么这么改**。每条结论都落到具体文件与具体数值。

---

## 实施状态（2026-10-01）

已落地并**通过编译 + 测试 + 离屏出图**核对：

| 项 | 文件 | 状态 |
|----|------|------|
| 形状刻度改官方 8 槽 | `ui/theme/Shape.kt` | ✅ 已改（含 `@file:OptIn`，8 参构造器是实验 API） |
| 排版字重 / 字距对齐规范 | `ui/theme/Type.kt` | ✅ 已改，并新增 `CpText`（Emphasized）与 `CpTypography.timecode` |
| 静态色板补容器色阶 | `ui/theme/Color.kt` | ✅ 已补 5 档 `surfaceContainer*` + `inverseSurface`/`scrim` |
| 纯黑模式保留极暗色阶 | `ui/theme/Theme.kt` | ✅ 已改（原先 6 角色全压黑） |
| 取色档位参数化 | `ui/theme/Theme.kt` | ✅ 已加 `paletteStyle` 参数，**默认仍 `TonalSpot`** |
| 组件级间距 + 图标尺寸 token | `ui/component/UiFoundation.kt` | ✅ 已补 `CpSpacing` 4 组 + `CpIconSize` |
| 分段列表行**按下形状变形** | `ui/component/LegacyListItem.kt` | ✅ 已实现（`rememberSegmentShape`），覆盖所有设置行/列表行 |
| 浮动工具条阴影 | `ui/component/ExpressiveKit.kt` | ✅ `shadowElevation 3 → 0` |
| 删除零引用的 `ExpressiveSectionHeader` | `ui/component/ExpressiveKit.kt` | ✅ 已删 |

**验证证据**：`:core:compileKotlinDesktop` + `:app:compileKotlinDesktop` 均 BUILD SUCCESSFUL；
`:app:desktopTest` 共 **72 项，`skipped="0" failures="0" errors="0"`**；
浅色 / 深色两套离屏出图确认卡片 28dp、`titleLarge` 为 Regular、容器色阶可辨、
按下态圆角撑到 20dp。

**第二轮：HomeScreen 逐屏核对（已完成）** —— `ui/screen/HomeScreen.kt`（2256 行）：

| 位置 | 改动 |
|------|------|
| `RankingRow` 缩略图 | `RoundedCornerShape(12.dp)` → `shapes.medium`（同值，去裸值） |
| `CompactTrackCard` 缩略图 | `RoundedCornerShape(14.dp)` → `shapes.medium`（**14 是非标度值**） |
| `CoverTile.corner` | 补 KDoc：取值必须落在刻度上（20 = `largeIncreased`，28 = `extraLarge`） |
| 标题 / 按钮 | 删 2 处与 token 同值的空操作 `fontWeight` 覆盖 |
| 4 处 `size(18.dp)` | → `CpIconSize.inline` |
| `ArrowBack` | → `Icons.AutoMirrored.Filled`（消除 deprecation 警告） |

> ✅ **核对结论：HomeScreen 的 `onSurface.copy(alpha = …)` 两处（`:1097` `contentAlpha`、
> `:1242` 禁用态）是正确的** —— 它们是「整块降透明度」的禁用/淡出语义，**不是**文字层级，
> 不应改成 `onSurfaceVariant`。该页在这一点上无需改动。

### ⚠️ 字重变更是**全仓性**的 —— 继续推进前请先看这张表

改 `Type.kt` 后，只有**没有显式覆盖 `fontWeight`** 的调用点才会真正跟着变：

| 样式 | 总用量 | 带覆盖 | **实际受影响** | 旧 → 新 |
|------|-------|--------|--------------|---------|
| `titleLarge` | 4 | 3 | **1** | SemiBold → Regular |
| `titleMedium` | 30 | 19 | **11** | SemiBold → Medium |
| `titleSmall` | 16 | 9 | **7** | SemiBold → Medium |
| `labelLarge` | 18 | 3 | **15** | SemiBold → Medium |
| `labelMedium` | 9 | 0 | **9** | SemiBold → Medium |

⇒ **共 43 处**。已离屏出图对比 600 vs 500（浅/深），结论：500 更轻但**不发灰**，
与正文仍有清晰层级，符合 M3 规范与 Kazumi（Flutter 默认字重）。

**若觉得标题偏弱，正确做法是用 `CpText.*Emphasized`，而不是把 `Type.kt` 改回 600** ——
改回去会把「标题/正文的字重对比」重新压平，那正是本次修正要解决的问题。

**第三轮：SettingsKit 逐屏核对（已完成）** —— `ui/component/SettingsKit.kt`（856 行）。
这一轮**顺带挖出一个真 bug**，比视觉改动本身更重要：

#### 🐛 修掉的 bug：`isSystemInDarkTheme()` 被当作「应用是否深色」

`SettingsKit.settingsRowContainer()` / `SongItem` / `PlayerScreen` 三处都用
`if (isSystemInDarkTheme()) … else …` 选容器色。但本应用允许用户**显式**指定浅色 / 深色
（`ThemeMode.LIGHT` / `DARK`）—— 用户选深色而系统是浅色时，`isSystemInDarkTheme()`
返回 `false`，于是取到**浅色**色板的角色色，与页面背景撞色、卡片整块糊住。

**这是离屏取像素发现的，不是读代码发现的**：预览里 `CpTheme(themeMode = LIGHT)` 而
`isSystemInDarkTheme()` 为 `true`，于是页面是 `#FBF8FF`(surface)、行内却是
`#E4E1E9`(surfaceContainerHighest) —— 同一屏两个元素读了两套色板，一眼可辨。

顺带暴露**第二个**问题：浅色分支返回 `surface`，而设置页背景**就是** `surface`
⇒ 在「浅色 + 系统浅色」（多数用户的默认）下，分组卡片**完全不可见**，只剩圆角和缝。

**修法**：

1. `Theme.kt` 新增 **`LocalIsDarkTheme`**（由 `CpTheme` 提供**已解析**的 `dark`，
   而不是 `ThemeMode` 那个原始模式）；
2. 三处改读 `LocalIsDarkTheme.current`；
3. 浅色分支 `surface` → **`surfaceContainerLow`**（M3 里「卡片浮在页面之上」的标准关系）。

实测对比（离屏取像素）：

| 主题 | 修前 | 修后 |
|------|------|------|
| 浅色 | 行内 == 页面（卡片不可见） | `#FBF8FF` vs `#F5F2FA` |
| 深色 | 撞色（取决于系统主题） | `#131318` vs `#34343A` |

> 📌 **给后续改主题的人的规矩**：需要「当前是不是深色」时**一律读 `LocalIsDarkTheme`**。
> 两者只在「跟随系统」时相等 —— 那正是这个 bug 能长期潜伏的原因。

#### 其余改动

| 位置 | 改动 |
|------|------|
| `SettingsSwitchItem` | 接上按下形状变形（`rememberSegmentShape`）—— 设置页出现频率最高的一类行 |
| `SettingsSection` 行距 | **2dp → 4dp**（`CpSpacing.listRowGap`）。形状变形需要缝宽，2dp 太窄会与邻行粘连；已同步改 KDoc（原文写「不要随手改数字」，这次是**成对参数**的刻意调整） |
| 5 处裸圆角 | `14.dp`→`shapes.medium`、`12.dp`→`shapes.medium`、`16.dp`→`shapes.large`、`20.dp`→`shapes.largeIncreased` |
| `9.dp`（分段控件内块） | **刻意保留**并注释：同心规则「内圆角 = 外框 − 内边距」（12 − 3 = 9）。改成 `shapes.small`(8) 反而破坏同心 |
| 弹层标题 | `titleLarge + fontWeight = Medium` → `CpText.titleLargeEmphasized` |
| 分组标题 | 删空操作 `fontWeight = Medium`；`letterSpacing = 0.5sp` 是**真覆盖**，保留 |

> ⚠️ **`Shapes.largeIncreased` 同样是实验 API**（带 `@ExperimentalMaterial3ExpressiveApi`，
> 而 `large` / `extraLarge` 这些老槽位不带）⇒ `SettingsFieldGroup` 加了函数级 `@OptIn`。
> 往 `AppShapes` 加新槽位、或在组件里用 `largeIncreased` / `extraLargeIncreased` /
> `extraExtraLarge`，**都要 opt-in**。

**第四轮：裸圆角普查 + 规则落库（已完成）**

用脚本对 `ui/**/*.kt` 全量普查后，得到两个**反直觉**的结论：

1. **只有 1 个文件内部同时混用 token 与裸值**（`PlaylistCard.kt`）。其余文件要么全 token、
   要么全裸值 ⇒ **"同屏两套圆角"是个别问题，不是普遍问题** —— 大规模机械迁移**没有**正当理由。
2. 但裸值总量确实可观（44 处 / 12 文件），其中 **14 处是真正落在刻度之外的**
   （`34 / 24 / 18 / 14 / 10`）。

**本轮实际修的：**

| 文件 | 改动 |
|------|------|
| `PlaylistCard.kt` | 卡片 `20.dp` → `shapes.largeIncreased`；缩略图 `14.dp`（**off-scale**）→ 12dp 常量（同时喂给 `coverFlightSource`，两处必须同值否则飞行起止圆角对不上）。并**订正了早已漂掉的 KDoc**（原文写「24dp 卡片 / 56dp 封面」，实际是 20 / 60） |
| `BentoCards.kt` | **删掉 `BentoShape` 常量**（它与 `shapes.extraLarge` 重复，是第二个真相源）→ 4 处改用 token；订正了 KDoc 里「`Shapes.extraLarge`(32dp)」这句**基于旧刻度的错值** |
| `AGENTS.md` §6 | 新增两条硬规则：①圆角必须走 `MaterialTheme.shapes.*`（含 8 槽真值、参数顺序、实验槽位需 opt-in）；②「当前是否深色」必须读 `LocalIsDarkTheme` |

### 📋 剩余裸圆角清单（**未做，按需推进**）

普查结果（已排除 `Shape.kt` 自身的令牌定义）：

| 值 | 数量 | 等价 token | 性质 |
|----|------|-----------|------|
| 34dp | 1 | — | **off-scale** |
| 32dp | 4 | `extraLargeIncreased` | 同值（实验 API，需 opt-in） |
| 28dp | 3 | `extraLarge` | 同值 |
| **24dp** | **5** | — | **off-scale**（旧 `large` 的值） |
| 20dp | 6 | `largeIncreased` | 同值（实验 API，需 opt-in） |
| 18dp | 3 | — | **off-scale** |
| 16dp | 8 | `large` | 同值 |
| 14dp | 1 | — | **off-scale** |
| 12dp | 7 | `medium` | 同值 |
| 10dp | 4 | — | **off-scale** |
| 8dp | 2 | `small` | 同值 |

**建议的推进顺序**（若要做）：

1. **先做 14 处 off-scale**（有实际视觉收益，且消除"这数从哪来的"疑问）；
2. 再做 26 处同值替换（零视觉收益，纯粹为了"改刻度时只改一处"）——
   其中 32dp / 20dp 那 10 处**需要 `@OptIn`**，成本明显更高，可以最后做；
3. 涉及文件：`DownloadsScreen` / `PlaylistDetailScreen` / `PlaylistPickerSheet` /
   `MoreOptionsSheet` / `QueueBottomSheet` / `PlayerMoreBottomSheet` / `MainScreen` /
   `DesktopPlayerScreen` / `SearchScreen` / `AboutScreen` / `LegacyScaffold`。

> ⚠️ 低于 8dp 的 5 处（1/2/3/4/6dp）是**装饰条与进度轨**，不是卡片圆角，**保持原样**。

**第五轮：off-scale 圆角清零 + 图标族归一（已完成）**

#### A. 14 处 off-scale 裸圆角 → 全部清零

| 原值 | → token | 值 | 处数 |
|------|---------|-----|------|
| 34 | `shapes.extraLarge` | 28 | 1 |
| 24 | `shapes.large` | 16 | 5 |
| 18 | `shapes.large` | 16 | 3 |
| 14 | `shapes.medium` | 12 | 1 |
| 10 | `shapes.small` | 8 | 4 |

全部映射到**非实验** token，因此**不需要任何 `@OptIn`** —— 这是选值时刻意规避的成本。

> 📌 **选值取舍**：24 那 5 处本可映射到 `largeIncreased`(20)（视觉改动更小、语义更贴），
> 但那需要 5 个文件各加一个 `@OptIn`。改映射到 `large`(16)：既省掉 opt-in，
> 又与另外 8 处已在用 `shapes.large` 的地方统一。
> **"用哪个 token"要把 opt-in 成本一起算进去**，不能只看语义最贴的那一个。

#### B. 图标族：普查后发现**只需改 3 处**

原以为要"补全 Filled/Outlined 配对"，普查后发现**大部分早就做对了**：

| 场景 | 现状 | 判定 |
|------|------|------|
| 导航三 Tab | `Filled.Home/Search/LibraryMusic` ↔ `Outlined.*` | ✅ 已正确 |
| 评论点赞（2 处） | `Filled.ThumbUp` ↔ `Outlined.ThumbUp` | ✅ 已正确 |
| 收藏 | `Filled.Favorite` ↔ `Filled.FavoriteBorder` | ✅ 已正确 —— `FavoriteBorder` **本身就是 Filled 族里的空心变体**，是 Material 的标准配对，**不要**改成 `Outlined.Favorite` |
| 播放模式 | 单族 `Filled.Repeat/RepeatOne/Shuffle` + tint | ✅ 可接受 |
| **游离的 `Rounded`** | `QueueMusic` / `Add` / `Close` | ❌ **3 处笔画粗细与 212 处 `Filled` 不一致** → 已改 `Filled` |

**最终族分布**：`Filled` 212 / `AutoMirrored.Filled` 33 / `Outlined` 5（**全部**是选中态配对）
/ **`Rounded` 0**。

> ⚠️ 顺带纠正一条容易搞反的认知（`LOG.md` 已有，这里复述）：
> **M3 Expressive 并不要求 Rounded 图标族**。`Filled` / `Outlined` 是「选中 / 未选中」的
> **语义配对**；`Rounded` 只是另一套字形，混进 Filled 为主的代码库只会让笔画粗细不统一。

**第六轮（收尾）：同值 token 替换 + 弹窗形状归一（已完成）**

- **19 处同值裸值 → token**（`28→extraLarge` / `16→large` / `12→medium` / `8→small`），
  全部为非实验槽位，零视觉变化。
- **3 处底部弹窗形状归一**：`LegacyScaffold` / `DownloadsScreen` 里各写了一遍的
  「上两角 32dp」→ 统一取 `CpShapes.sheet`。此前同一个形状在三个文件里各写一份，
  改一次要改三处。
- `ExpressiveKit` 的 4dp → `shapes.extraSmall`。

> ⚠️ **踩到的坑（值得记住）**：批量替换 `RoundedCornerShape(16.dp)` 时，有一处是
> **全限定写法** `androidx.compose.foundation.shape.RoundedCornerShape(16.dp)`，
> 替换后变成了 `androidx.compose.foundation.shape.MaterialTheme.shapes.large` —— 编译报错才发现。
> **批量替换前先 grep 全限定/变体写法**，别假设只有一种写法。

### ✅ 最终状态：非实验裸圆角已清零

全仓（除 `theme/Shape.kt` 自身的令牌定义）仅剩 **10 处**裸圆角：

| 值 | 处数 | 说明 |
|----|------|------|
| 32dp | 1 | `MoreOptionsSheet` 的均四角 32dp —— 需实验槽位 `extraLargeIncreased` |
| 20dp | 6 | 需实验槽位 `largeIncreased` |
| 1 / 2 / 3dp | 3 | **装饰条与进度轨，不是卡片圆角**，刻意保留 |

⇒ **凡是能用非实验 token 表达的圆角，已经全部 token 化。**
剩下 7 处要么需要 `@OptIn`（成本 > 收益），要么本就不该用形状 token 表达。

**真正剩余的（未做）**：

- 上述 7 处实验槽位替换（建议**不做** —— 收益是纯一致性，代价是 7 个文件各加 `@OptIn`）；
- 品牌字体评估（**必须先量安装包体积增量** —— 中文全量字重代价可观）；
- `SettingsKit` 的 `SettingsSegmentedItem` / `SettingsTextInputItem` 接入形状变形：
  **刻意不做** —— 这两类行本身不可点，不可点的行按下却变形会给出「这里能点」的错误暗示。
  （`SettingsSwitchItem` 与所有 `LegacyListItem` 系行已完成。）

**实施中新增的认知**（已回写本文对应小节）：

1. 8 参数版 `Shapes` 构造器带 `@ExperimentalMaterial3ExpressiveApi`（5 参版不带）
   ⇒ `Shape.kt` 需要文件级 opt-in。
2. 项目**已有** `LegacyListItem` + `legacySegmentShape`（外 20dp / 内 4dp 分段圆角），
   与 Kazumi 的 `SplitListRow` 结构**完全同构** —— 所以不必新造组件，
   直接给既有行加变形即可。初稿曾新建一个 `CpGroupedListRow`，因与既有组件职责重复已删除
   （本项目已有"零引用组件"的前车之鉴，见 `LOG.md`）。

---

## 0. 结论速览

| # | 发现 | 严重度 | 一句话 |
|---|------|--------|--------|
| 1 | **形状刻度整体偏大**：`AppShapes` 的 5 个槽位全部比 M3E 规范大 4–8dp | P0 | 不是"更 Expressive"，是"更圆"。25 处消费点集体受影响 |
| 2 | **`Shapes` 在 1.11 已是 8 槽**，我们只填了 5 个 | P0 | 后 3 个槽（`largeIncreased`/`extraLargeIncreased`/`extraExtraLarge`）走默认值，同屏出现两套圆角体系 |
| 3 | **排版字重整体偏重**：`title*`/`label*` 用 SemiBold(600)，规范是 400/500 | P0 | 且 `letterSpacing` 全缺、M3E 的 **Emphasized** 字阶完全没接 |
| 4 | **静态回退色板没填 `surfaceContainer*` 系列** | P1 | 色阶对比不足 ⇒ 卡片边界只能靠阴影/描边补，这是"阴影用得多"的根因 |
| 5 | 取色档位硬编码 `PaletteStyle.TonalSpot` | P2 | ~~观感偏灰~~ **已推翻**：Kazumi 用的也是 `tonalSpot`，档位不是差异来源；仅做参数化（见 §3.3） |
| 6 | 动效**架构对、消费率低** | P1 | `CpMotion` 六个令牌分 spatial/effects 是正确的；但只有 3 处 elevation、极少 shape morph，时长散落在 140–480ms 无标度 |
| 7 | 图标族其实**已经统一**（Filled 210 / Outlined 5 / Rounded 3） | P2 | **不建议全量迁移**，只补"选中/未选中"配对 |

**核心判断**：CPPlayer 已经用上了 M3E 的 API（`LinearWavyProgressIndicator` / `LoadingIndicator` / `ToggleButton` / `MaterialShapes`），反而在**刻度一致性**上落后于 Kazumi。Kazumi 看起来"Expressive"不是因为用了新 API，而是因为：

1. 全站卡片圆角**只有一个常量**（`tonalCardRadius = 24`）；
2. 分组列表的**按压形状变形**（内圆角 4 → 外圆角 24）；
3. 大封面 + 大留白 + 少边框。

---

## 1. 参照对象拆解：Kazumi 做对了什么

### 1.1 事实清单（均带文件路径）

| 维度 | Kazumi 的做法 | 出处 |
|------|--------------|------|
| 主题构造 | `ThemeData(useMaterial3: true, colorSchemeSeed: color)` —— **零自定义 Shapes/Typography** | `lib/app_widget.dart:144` |
| 卡片圆角 | 单一常量 `tonalCardRadius = 24` | `lib/bean/widget/tonal_card.dart:3` |
| 间距常量 | `cardSpace = 8` / `safeSpace = 12` / `mdRadius = 10` / `imgRadius = 12` | `lib/utils/constants.dart:8-10` |
| 分组列表 | 外圆角 24、**内圆角 4**、行间距 4；按下时内圆角**变形成外圆角 24**，250ms `easeInOutCubic` | `lib/bean/widget/split_list_row.dart:5-11` |
| 圆角分布 | 16(25) / 20(23) / 28(15) / 12(13) / 24(11) —— 集中在 12/16/20/24/28 | 全仓 `BorderRadius.circular()` 统计 |
| 动效曲线 | `easeOutCubic`(31) / `easeInOutCubicEmphasized`(9) / `easeOutBack`(5) —— **以减速为主，回弹只给少数** | 全仓 `Curves.*` 统计 |
| 字体 | 单一字重 `MI_Sans_Regular`（MiSans） | `pubspec.yaml:165` |
| 组件偏好 | `ListTile`(43) / `Switch`(40) / `Chip`(26) / `FilledButton.tonal`(13) / `NavigationBar`(11) / `NavigationRail`(7) | 全仓统计 |
| OLED 模式 | 只压 `scaffoldBackgroundColor` + `surface`，**且把 `onPrimary`/`onSecondary` 改黑** | `lib/utils/theme.dart:3-13` |
| 主题设置 | 9 个预设色 + 系统取色 | `lib/bean/settings/color_type.dart` |

### 1.2 可借鉴 vs 不可照搬

| 可借鉴 | 说明 |
|--------|------|
| **单一圆角常量** | 用一个 `tonalCardRadius` 统一全部卡片，消除"每处各写一个 dp" |
| **按压形状变形** | `SplitListRow` 的 4 → 24 变形是最高性价比的 Expressive 信号 |
| **间距常量组** | 8/12 两个基准值足够撑起全站节奏 |
| **减速为主的曲线** | `easeOutCubic` 占绝对多数，回弹（`easeOutBack`）只给 5 处 |

| 不可照搬 | 原因 |
|----------|------|
| 零自定义 Shapes | Kazumi 靠 Flutter 默认刻度 + 单一常量；我们已有 25 处消费 `MaterialTheme.shapes.*`，必须显式定义刻度 |
| 单一字重字体 | 只有一个 Regular，标题与正文靠字号区分。我们的层级已经依赖字重，不宜退回去 |
| 只压 scaffold+surface 的 OLED | 会保留 `surfaceContainerLow..Highest` 为灰紫，OLED 上大面积发光。我们已有 `withPureBlackSurfaces()`，方向是对的，只是**压得太狠**（见 §3.4） |
| `onPrimary` 也改黑 | 会让主色按钮在纯黑下失去对比，不适合我们"强调色必须可读"的取舍 |

---

## 2. 现状体检（token 级，全部为实测）

### 2.1 形状刻度 ❌ 最严重

`app/src/commonMain/kotlin/cp/player/app/ui/theme/Shape.kt:9` 用的是 **5 参数构造**，而 `material3 1.11.0-alpha07` 的 `Shapes` 已经是 **8 参数**：

```
public Shapes(extraSmall, small, medium, large, largeIncreased,
              extraLarge, extraLargeIncreased, extraExtraLarge, ...)
```

`javap` 从 `androidx.compose.material3.tokens.ShapeTokens` 读出的规范值：

| 槽位 | M3E 规范 | CPPlayer 当前 | 偏差 | 消费点数量 |
|------|---------|--------------|------|-----------|
| `extraSmall` | **4dp** | 8dp | **+4** | — |
| `small` | **8dp** | 12dp | **+4** | 1 |
| `medium` | **12dp** | 16dp | **+4** | 3 |
| `large` | **16dp** | 24dp | **+8** | 7 |
| `largeIncreased` | **20dp** | *未设 → 20dp* | 0 | 0 |
| `extraLarge` | **28dp** | 32dp | **+4** | **14** |
| `extraLargeIncreased` | **32dp** | *未设 → 32dp* | 0 | 0 |
| `extraExtraLarge` | **48dp** | *未设 → 48dp* | 0 | 0 |

**结论**：整条刻度被 **+4dp 平移**，`large` 被额外 +8dp。而 14 处卡片正在取 `extraLarge`（32dp，规范 28dp）、7 处取 `large`（24dp，规范 16dp）—— 也就是说**卡片比规范圆 4dp，次级容器比规范圆 8dp**。

`Shape.kt:17` 的 `CpShapes` 又另起了一套（`sheet = 32dp` 顶角、`miniPlayer = 28/16` 不对称），与 `MaterialTheme.shapes.*` 并存 ⇒ 同一个"卡片"在仓库里有两套圆角来源。

### 2.2 排版 ❌

`ui/theme/Type.kt` 与 `TypeScaleTokens` 逐条对照：

| 样式 | CPPlayer | M3E 规范 | 判定 |
|------|----------|---------|------|
| `displayLarge/Medium/Small` | 57/45/36, Normal | 57/45/36, **Regular(400)** | ✅ |
| `headlineLarge/Medium/Small` | 32/28/24, Normal | 32/28/24, **Regular(400)** | ✅ |
| `titleLarge` | 22, **SemiBold(600)** | 22, **Regular(400)** | ❌ 重 2 档 |
| `titleMedium` | 16, **SemiBold(600)** | 16, **Medium(500)** | ❌ 重 1 档 |
| `titleSmall` | 14, **SemiBold(600)** | 14, **Medium(500)** | ❌ 重 1 档 |
| `bodyLarge/Medium/Small` | 16/14/12, Normal | 16/14/12, **Regular(400)** | ✅ |
| `labelLarge` | 14, **SemiBold(600)** | 14, **Medium(500)** | ❌ 重 1 档 |
| `labelMedium` | 12, **SemiBold(600)** | 12, **Medium(500)** | ❌ 重 1 档 |
| `labelSmall` | 11, Medium(500) | 11, Medium(500) | ✅ |
| `letterSpacing` | **全部未设** | bodyLarge 0.5 / bodyMedium 0.2 / bodySmall 0.4 | ❌ |
| **Emphasized 字阶** | **完全缺失** | 15 档（`displayLargeEmphasized` … `labelSmallEmphasized`） | ❌ |

> 注：`titleLarge` 在 M3E 里被降为 **Regular(400)**（历史上是 Medium），因为它在 Expressive 里承担"大标题"角色，强调交给 `titleLargeEmphasized`。我们写成 SemiBold 等于**把强调和层级压在同一档**，标题重、正文轻的对比反而被拉平。

### 2.3 颜色 ⚠️

- **取色档位过于保守**：`ui/theme/Theme.kt:111` 用 `PaletteStyle.TonalSpot`。`material-kolor 5.0.0` 提供 9 档（`javap` 确认）：`TonalSpot` / `Neutral` / `Vibrant` / `Expressive` / `Rainbow` / `FruitSalad` / `Monochrome` / `Fidelity` / `Content`。`TonalSpot` 是最"去饱和"的一档，正是观感"发灰"的原因。
- **静态回退色板缺容器色阶**：`ui/theme/Color.kt` 的 `LightColors`/`DarkColors` 只填了 `primary/secondary/tertiary/error/background/surface/surfaceVariant/outline`，**没有填 `surfaceContainerLowest..Highest`**（8 个角色）。走 `lightColorScheme()` 的默认值。
  ⇒ 后果：动态取色路径（materialkolor 会给全套）与静态回退路径**观感不一致**；且纯黑模式必须靠 `withPureBlackSurfaces()` 手工覆盖 6 个角色来兜底。
- **未定义的角色**：`inverseSurface` / `inverseOnSurface` / `surfaceTint` / `scrim` 全部走默认，跨路径不一致。

### 2.4 阴影与层级 ⚠️

全仓 `elevation` 只有 8 处（数量本身说明层级**主要靠 `surfaceContainer*` 色阶**，方向正确）：

| 位置 | 当前值 | 问题 |
|------|--------|------|
| `ExpressiveKit.kt:530-531` `CpFloatingToolbar` | tonal 3 + **shadow 3** | 浮动工具条同时拉满 tonal 与 shadow，会"脏" |
| `MiniPlayer.kt:80-81` | tonal 2 + shadow 2 | 迷你播放器贴底，阴影方向朝下才合理 |
| `PlaylistDetailScreen.kt:557` | shadow 2 | 卡片内嵌，应为 0 |
| `PlaylistDetailScreen.kt:657` | shadow 8 | 浮起物，合理 |
| `PlayerScreen.kt:597` `coverElevation` | 动态 | 封面飞行，合理 |
| `SearchScreen.kt:143` | tonal 3 | 合理 |
| `CoverFlight.kt:328` | 动态 12 | 飞行中，合理 |

容器色阶使用统计（说明色阶确实是主手段）：

```
surfaceContainerHighest  29      surfaceContainer       5
surfaceContainerHigh     27      surfaceContainerLowest 1
surfaceVariant           24      inverseSurface         1
surfaceContainerLow      14      outlineVariant         9
surface                     9      outline                3
```

⚠️ **`withPureBlackSurfaces()`（`Theme.kt:154`）把 `surfaceContainerLow..Highest` 全部压成 `#000`** —— 这正是 `bentoOutline()` 描边存在的原因（`LOG.md` 已记录：纯黑下卡片与背景同色、整页糊成一块）。**用描边去补被压掉的色阶，是治标**。

### 2.5 间距 ⚠️

`ui/component/UiFoundation.kt:41` 的 `CpSpacing` 有：`pageHorizontal 20` / `pageTop 20` / `section 28` / `item 12` / `touchTarget 48` / `pageMaxWidth 1400` / `formMaxWidth 720` / `gridColumns()`。

**缺口**：只有"页面级"间距，**没有"组件内"间距**——

- 卡片内 padding（`16/20/24` 散落各文件）
- 列表行最小高度（`56dp` 是 M3 规范值，未 token 化）
- 纵向堆叠间隔（`4/8/12/16` 各写各的）

对照 Kazumi 的 `cardSpace = 8` / `safeSpace = 12`：两个常量撑起全站。

### 2.6 图标 ✅ 基本达标

```
Icons.Filled              210
Icons.AutoMirrored.Filled. 32
Icons.Outlined              5
Icons.Rounded               3
```

**结论：不需要全量迁移。** 缺口是**语义配对**——导航/收藏/播放模式目前用同一族 + tint 变化表达选中态，M3E 的规范做法是 `Filled`（选中）↔ `Outlined`（未选中）配对。

尺寸方面 `18 / 20 / 24` 混用且无 token。

### 2.7 动效 ⚠️ 架构对、消费率低

**做对的**：`ui/theme/Motion.kt` 的 `CpMotion` 把 spatial（可回弹）与 effects（不可回弹）分开，并由 `MaterialExpressiveTheme` 注入 —— 这是 `LOG.md` 里明确记录的坑，已规避。

**缺口**：

1. **消费率低** —— 只有 `CpPlayPauseButton`（圆角收缩 + 图标缩放 + 交叉淡入）、`Modifier.cpPressScale`、`CoverFlight` 三处在用。列表项、卡片、开关、导航基本没有形状/缩放反馈。
2. **时长无标度** —— 全仓 `tween(...)` 字面量：`140×4 / 160 / 200×2 / 220 / 240 / 250 / 300 / 400×2 / 480`，看不出分档意图。
3. **曲线来源不统一** —— `ExpressiveKit` 内部混用 `FastOutSlowInEasing`（手写 tween）与 `CpMotion.*`（主题规格）。

---

## 3. 优化方案

### 3.1 形状与圆角（P0）

**目标刻度 = M3E 官方值，8 槽全填。**

```kotlin
// app/src/commonMain/kotlin/cp/player/app/ui/theme/Shape.kt
package cp.player.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * M3 Expressive 形状刻度（material3 1.11.0-alpha07 的官方令牌值）。
 *
 * ⚠️ 必须用 8 参数构造。1.11 起 `Shapes` 新增了 largeIncreased / extraLargeIncreased /
 * extraExtraLarge 三槽；只填 5 个会让这三个走默认值，与自定义刻度混用 ⇒ 同屏两套圆角体系。
 *
 * 令牌值来源：`androidx.compose.material3.tokens.ShapeTokens`（javap 读取，勿凭记忆改）。
 */
val AppShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    largeIncreased = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
)
```

**分级用法表**（收敛掉 `CpShapes` 里的自定义值）：

| 用途 | 槽位 | 值 | 说明 |
|------|------|-----|------|
| 大卡片 / 封面容器 | `extraLarge` | 28 | 当前 14 处，**从 32 → 28** |
| 分组列表容器 | `large` | 16 | 当前 7 处，**从 24 → 16** |
| 卡片内嵌块 / 缩略图 | `medium` | 12 | 当前 3 处，从 16 → 12 |
| 行内小图标底 | `small` | 8 | 从 12 → 8 |
| 按钮（静态态） | `full` | 50% | |
| 按钮（按下态） | `large` | 16 | 形状变形，见 §3.7 |
| 芯片 / 胶囊 | `full` | 50% | |
| 底部弹窗顶部 | `RoundedCornerShape(topStart=28, topEnd=28)` | 28 | 用 `extraLarge` 的顶角语义，**不要另写 32** |
| 迷你播放器 | 28 / 16 不对称 | — | 保留（贴底元素，不对称是正确的） |

> ⚠️ **迁移影响面**：25 处 `MaterialTheme.shapes.*` 会集体变小 4–8dp。按 `AGENTS.md` §1 —— **改版式不能只靠编译 + 单测，必须离屏渲染出图核对**。逐屏核对清单见 §7。

### 3.2 排版（P0）

**第一步：把字重对齐规范。**

```kotlin
val AppTypography: Typography = Typography(
    displayLarge  = TextStyle(fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.Normal),
    displayMedium = TextStyle(fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = 0.sp,     fontWeight = FontWeight.Normal),
    displaySmall  = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = 0.sp,     fontWeight = FontWeight.Normal),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 0.sp,     fontWeight = FontWeight.Normal),
    headlineMedium= TextStyle(fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.sp,     fontWeight = FontWeight.Normal),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp,     fontWeight = FontWeight.Normal),
    // ↓ 关键修正：titleLarge 规范是 Regular(400)，不是 SemiBold
    titleLarge    = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp,     fontWeight = FontWeight.Normal),
    titleMedium   = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp,   fontWeight = FontWeight.Medium),
    titleSmall    = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,   fontWeight = FontWeight.Medium),
    bodyLarge     = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp,   fontWeight = FontWeight.Normal),
    bodyMedium    = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp,   fontWeight = FontWeight.Normal),
    bodySmall     = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp,   fontWeight = FontWeight.Normal),
    labelLarge    = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,   fontWeight = FontWeight.Medium),
    labelMedium   = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp,   fontWeight = FontWeight.Medium),
    labelSmall    = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp,   fontWeight = FontWeight.Medium),
)
```

⚠️ **`titleLarge` 从 SemiBold 降到 Normal 是观感变化最大的一处**。因为规范把强调交给了 `titleLargeEmphasized`，所以下一步必须**同时**引入 Emphasized 字阶，否则标题会"塌"下去。

**第二步：补 Emphasized 字阶（只做 4 档，够用）。**

`Typography` 类本身没有 Emphasized 槽位，用扩展对象提供：

```kotlin
// ui/theme/Type.kt 追加
/**
 * M3E 的「强调」字阶。
 *
 * 规范里 `titleLarge` 是 Regular(400)，页面标题想要更重的观感时**不应该**去改
 * `Typography.titleLarge`（那会连带影响所有用它的组件），而是显式取这里的样式。
 * 字重取自 TypefaceTokens.WeightBold = W700（javap 确认）。
 */
object CpText {
    val titleLargeEmphasized = TextStyle(
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Bold,
    )
    val titleMediumEmphasized = TextStyle(
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp, fontWeight = FontWeight.Bold,
    )
    val labelLargeEmphasized = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp, fontWeight = FontWeight.Bold,
    )
    val bodyLargeEmphasized = TextStyle(
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.Medium,
    )
}
```

**第三步：时间码用等宽数字（tabular figures）。**

播放器刚需 —— 秒数从 `0:09` 跳到 `0:10` 时，比例数字会让整行宽度抖动，时间行肉眼可见地"抽一下"。

```kotlin
// 时间码专用样式（在 formatTimeMs 的调用点使用）
val timecodeStyle = MaterialTheme.typography.labelMedium.copy(
    fontFeatureSettings = "tnum",  // tabular numbers
)
```

**第四步（可选，P2）：品牌字体。**

Kazumi 内置了 MiSans（`assets/fonts/MiSans-Regular.ttf`）以获得跨平台一致的汉字观感。CPPlayer 目前走系统默认 ⇒ **桌面与安卓字面不一致**，同一套排版在两端的实际观感会漂。

建议评估：内置一个中文可变/静态字体（MiSans / HarmonyOS Sans / Noto Sans SC），**只带 400 / 500 / 700 三个静态字重以控体积**（中文全量单字重约 4–6 MB，三字重需权衡；可考虑只带 400 + 500，Bold 用合成）。**上字体前必须先量安装包体积增量**，这是本方案里唯一会显著影响产物体积的改动。

### 3.3 颜色（P1）

**① 取色档位提为参数（但默认值保持 `TonalSpot` 不动）。**

> ⚠️ **本节结论在实施时被修正过。** 初稿建议"默认换成 `Vibrant`"，理由是"TonalSpot 太保守、
> 观感发灰"。核对 Kazumi 源码后**推翻**：Kazumi 用的是 Flutter 的 `colorSchemeSeed`
> （`lib/app_widget.dart:148`），而 Flutter `ColorScheme.fromSeed` 的默认变体正是
> **`tonalSpot`** —— 与我们**完全相同**。也就是说取色档位**不是** Kazumi 好看的来源，
> 调高饱和度只会让我们偏离它。**默认值保持 `TonalSpot`。**

```kotlin
// ui/theme/Theme.kt —— 把 style 提为参数（默认仍为 TonalSpot）
val generated = if (platformScheme == null && seed != null) {
    rememberDynamicColorScheme(
        seedColor = seed,
        isDark = dark,
        isAmoled = pureBlack,
        style = paletteStyle,   // ← 默认 PaletteStyle.TonalSpot
    )
} else null
```

参数化本身仍有价值：**封面取色**场景下 `Fidelity` / `Content` 能让配色更贴近封面本身，
比 TonalSpot 的"去饱和"更符合"跟随封面"的预期。若要做设置项，建议只暴露这 3 档：

| 档位 | 观感 | 建议 |
|------|------|------|
| `TonalSpot` | 低饱和、柔和 | **保持默认**（与 Flutter / Material Theme Builder 基准一致） |
| `Fidelity` | 贴近种子色本身 | 可选，适合「跟随封面」来源 |
| `Content` | 在保真与可读性间折中 | 可选，适合「跟随封面」来源 |
| `Vibrant` / `Expressive` | 高饱和、色相跨度大 | 不设默认；要暴露也只作为进阶项 |

> `Rainbow` / `FruitSalad` / `Monochrome` 建议不暴露：前者在音乐 App 里容易花，后两者与现有语义色冲突。

**② 补全静态回退色板的容器色阶。**

`Color.kt` 的 `LightColors` / `DarkColors` 必须补上 8 个 `surfaceContainer*` 角色（以及 `inverseSurface`/`inverseOnSurface`/`surfaceTint`/`scrim`），使**静态回退路径与动态取色路径的输出角色集合一致**。

浅色建议（与现有 `SurfaceLight = #FCF8FC` 同色相推导）：

| 角色 | 建议值 |
|------|--------|
| `surfaceContainerLowest` | `#FFFFFF` |
| `surfaceContainerLow` | `#F7F2FA` |
| `surfaceContainer` | `#F1ECF4` |
| `surfaceContainerHigh` | `#EBE6EF` |
| `surfaceContainerHighest` | `#E5E1EC`（= 现 `SurfaceVariantLight`） |

深色（与现有 `SurfaceDark = #131318` 同色相）：

| 角色 | 建议值 |
|------|--------|
| `surfaceContainerLowest` | `#0E0E13` |
| `surfaceContainerLow` | `#1B1B21` |
| `surfaceContainer` | `#1F1F25` |
| `surfaceContainerHigh` | `#2A2A30` |
| `surfaceContainerHighest` | `#35343B` |

**③ 纯黑模式改为「保留极暗色阶」。**

当前 `withPureBlackSurfaces()` 把 `surfaceContainerLow..Highest` 全压 `#000`，导致必须靠 `bentoOutline()` 描边补层级。建议改为：

```kotlin
/**
 * 纯黑模式的 surface 处理。
 *
 * 只压「大面积背景」与「最低一级容器」，**保留 surfaceContainerLow..Highest 的极暗色阶**。
 *
 * 理由：把五级容器全部压成 #000 后，卡片与背景同色，层级只剩「描边」一种手段
 * —— 而描边是比色阶更"重"的视觉元素，整页会显得生硬。保留 4 档极暗灰
 * （#0A0A0C → #16161A）既能让 OLED 大面积熄屏，又能维持无描边的层级。
 */
private fun ColorScheme.withPureBlackSurfaces(): ColorScheme = copy(
    surface = Color.Black,
    background = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceVariant = Color(0xFF16161A),
    surfaceContainerLow = Color(0xFF0A0A0C),
    surfaceContainer = Color(0xFF0E0E11),
    surfaceContainerHigh = Color(0xFF131316),
    surfaceContainerHighest = Color(0xFF19191D),
)
```

⚠️ 这是一处**与 Kazumi 的刻意分歧**：Kazumi 只压 `scaffold` + `surface`（`lib/utils/theme.dart`），且把 `onPrimary` 也改黑；我们的原则是"强调色必须保持可读"，所以不动 `onPrimary`。

**④ 对比度体检（需实测）。**

当前 `OutlineVariantLight = #C9C5D0` 对 `SurfaceVariantLight = #E5E1EC` 的对比度约 **1.3:1** —— 作为描边可接受，但**若被用作文本或图标色则严重不足**。需要全仓扫一遍 `outlineVariant` 的 9 处使用，确认没有承担文本职责。

### 3.4 阴影与层级（P1）

**确立原则：色阶优先，阴影只给「真正浮起」的东西。**

| 层级 | 容器色 | tonalElevation | shadowElevation | 场景 |
|------|--------|---------------|----------------|------|
| 0 页面背景 | `surface` | 0 | 0 | 页面根 |
| 1 卡片 | `surfaceContainerLow` | 0 | 0 | 首页卡片、列表卡 |
| 2 卡片内嵌 | `surfaceContainer` | 0 | 0 | 卡片里的次级块 |
| 3 浮起工具条 | `surfaceContainerHigh` | 3 | **0**（或 ≤1） | 浮动工具条、迷你播放器 |
| 4 模态 | `surfaceContainerHigh` | 3 | **6–8** | 弹窗、BottomSheet |

逐处调整：

| 文件:行 | 当前 | 建议 |
|---------|------|------|
| `ExpressiveKit.kt:530-531` | tonal 3 + shadow 3 | tonal 3 + **shadow 0** |
| `MiniPlayer.kt:80-81` | tonal 2 + shadow 2 | tonal 2 + shadow **1**（贴底，方向朝下） |
| `PlaylistDetailScreen.kt:557` | shadow 2 | **0**（卡片内嵌） |
| `PlaylistDetailScreen.kt:657` | shadow 8 | 保持 |
| `SearchScreen.kt:143` | tonal 3 | 保持 |

### 3.5 间距与排版节奏（P1）

**补齐组件级间距 token：**

```kotlin
object CpSpacing {
    // —— 已有（保持）——
    val pageHorizontal = 20.dp
    val pageTop = 20.dp
    val section = 28.dp
    val item = 12.dp
    val touchTarget = 48.dp
    val pageMaxWidth = 1400.dp
    val formMaxWidth = 720.dp

    // —— 新增：组件内间距（原先散落在各文件，值不统一）——
    /** 卡片内边距。大卡片用 comfortable，紧凑卡片用 compact。 */
    val cardPaddingCompact = 16.dp
    val cardPadding = 20.dp
    val cardPaddingComfortable = 24.dp

    /** 列表行最小高度（M3 规范值）。 */
    val listRowMinHeight = 56.dp

    /** 分组列表的行间距（借鉴 Kazumi splitListRowGap = 4）。 */
    val listRowGap = 4.dp

    /** 纵向堆叠间隔阶梯。不要再用 6 / 10 / 14 这类非标度值。 */
    val stackGapTight = 4.dp
    val stackGap = 8.dp
    val stackGapLoose = 12.dp
    val stackGapSection = 16.dp
}
```

**基准网格**：全部间距取 **4dp 的倍数**（Kazumi 用 8/12 两个基准，我们取 4/8/12/16 四档）。现状里 `9dp / 14dp / 18dp / 34dp` 这类值应归入最近档位。

### 3.6 图标（P2）

**不迁移族**（Filled 已是绝对主流，全量迁移收益低、风险高）。只做三件事：

1. **建尺寸 token**：

```kotlin
object CpIconSize {
    /** 行内图标（跟在文字旁边）。 */
    val inline = 18.dp
    /** 列表项前导图标。 */
    val list = 20.dp
    /** 操作按钮 / 导航图标。 */
    val action = 24.dp
}
```

2. **补「选中 / 未选中」配对**（M3E 语义，不是换字族）：
   - 导航栏：`Icons.Filled.*`（选中）↔ `Icons.Outlined.*`（未选中）
   - 收藏：`Icons.Filled.Favorite` ↔ `Icons.Outlined.FavoriteBorder`
   - 其余（播放模式、音量等）保持单族 + tint 变化即可

3. **收敛区块标题**：`LOG.md` 已记录 `PageHeader` / `SectionHeader` / `ExpressiveSectionHeader` **三套并存**，其中 `ExpressiveSectionHeader`（`ExpressiveKit.kt:588`）**零引用**。建议**删掉 `ExpressiveSectionHeader`**，保留 `SectionHeader`（16 处使用）作为唯一区块标题，`PageHeader` 作为页面级标题。

### 3.7 动效（P1）

**① 时长分档（替换现有 140–480ms 的散落值）：**

| 档位 | 时长 | 场景 |
|------|------|------|
| fast | 120ms | 图标切换、按压反馈、hover |
| default | 250ms | 状态切换、形状变形、颜色过渡 |
| slow | 400ms | 整页转场、大面积位移 |

**② 补齐组件级消费 —— 这是性价比最高的部分。**

**a. 列表行形状变形（借鉴 Kazumi `SplitListRow`）：**

```kotlin
/**
 * Expressive 列表行：按下时圆角从「组内圆角」变形成「容器圆角」。
 *
 * 这是 Kazumi 全站最"Expressive"的一处交互（`lib/bean/widget/split_list_row.dart`），
 * 成本极低、信号极强 —— 用户按下时能明确感到「这一行属于上面那张卡片」。
 *
 * @param containerRadius 所在分组容器的圆角（通常 MaterialTheme.shapes.large = 16dp）
 * @param rowRadius 组内行的静止圆角（4dp，M3 的 extraSmall）
 */
@Composable
fun CpGroupedListRow(
    modifier: Modifier = Modifier,
    containerRadius: Dp = MaterialTheme.shapes.large.topStart.toDp(),
    rowRadius: Dp = 4.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val radius by animateDpAsState(
        targetValue = if (pressed) containerRadius else rowRadius,
        animationSpec = CpMotion.spatial(),
        label = "cpRowRadius",
    )
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        interactionSource = interaction,
        shape = RoundedCornerShape(radius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = CpSpacing.listRowMinHeight)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
```

**b. 卡片按压反馈**（复用已有的 `Modifier.cpPressScale`，把 `pressedScale` 从 0.97 调到 0.98 并叠加圆角 +2dp）。

**c. 统一现有手写 tween** —— `ExpressiveKit` 内部的 `FastOutSlowInEasing` 手写值应改用 `CpMotion.*`，让观感跟随主题一处调、处处变。

**③ 明确禁止项**（已有 `CpMotion` 兜底，写进 `AGENTS.md` §6 的现有条目即可）：

- `effects` 系（颜色 / 透明度）**绝不能带回弹**
- 不要手写 `spring(...)`

---

## 4. 界面级调整建议

| 屏幕 | 文件 | 现状问题 | 调整项 |
|------|------|---------|--------|
| 首页 | `ui/screen/HomeScreen.kt`（1900+ 行，20+ 处 `MaterialTheme.shapes.*`） | 圆角 32（应 28）；`onSurface.copy(alpha)` 残留 2 处（`:1097` `:1242`） | 跟随 §3.1 刻度；`alpha` 文本改 `onSurfaceVariant`；卡片内 padding 换 token |
| 曲库 | `ui/screen/LibraryScreen.kt` | 列表行无按压反馈 | 用 `CpGroupedListRow` |
| 设置（主页） | `ui/screen/SettingsScreen.kt` + `ui/component/SettingsKit.kt`（856 行） | 分组列表无形状变形；行高未 token 化 | `CpGroupedListRow` + `listRowMinHeight` |
| 设置（子页） | `ui/screen/SettingsSubScreens.kt` | 与主页节奏不一致 | 统一 `formMaxWidth` 用法（`widthIn` 必须在 `fillMaxWidth` **之前**） |
| 播放页 | `ui/screen/PlayerScreen.kt` | 封面 elevation 动态（合理）；时间码宽度抖动 | 时间码用 tabular figures；`:875` 的注释与实现一致（已是 `onSurfaceVariant`）✅ |
| 桌面播放页 | `ui/screen/DesktopPlayerScreen.kt` | 与移动播放页观感需对齐 | 核对圆角与间距 token |
| 迷你播放器 | `ui/component/MiniPlayer.kt` | shadow 2 偏重；封面 `shapes.medium`（16，应 12） | shadow → 1；封面用 `medium`（新值 12） |
| 播放列表详情 | `ui/screen/PlaylistDetailScreen.kt` | `:557` shadow 2（内嵌不该有） | shadow → 0 |
| 搜索 | `ui/screen/SearchScreen.kt` | tonal 3 合理 ✅ | 仅核对圆角 |
| 歌单卡 / 歌曲项 | `ui/component/PlaylistCard.kt` / `SongItem.kt` | `extraLarge`（32 → 28）、`medium`（16 → 12） | 自动跟随刻度，需出图核对 |
| 我的（Bento） | `ui/component/BentoCards.kt` | 圆角统一 28 ✅；纯黑下依赖 `bentoOutline()` | 若采纳 §3.3③，可评估描边是否还需保留 |
| **账号页** | `ui/screen/AccountScreen.kt` | ⚠️ **别人的 untracked 在途文件，且不是合法 UTF-8** | **不要动**（见 `LOG.md`「仍在生效的现场问题」） |

---

## 5. 组件级调整建议

| 组件 | 文件:行 | 现状 | 目标 | 优先级 |
|------|---------|------|------|--------|
| `AppShapes` | `theme/Shape.kt:9` | 5 槽，刻度 +4~+8 | 8 槽，M3E 官方值 | **P0** |
| `AppTypography` | `theme/Type.kt:10` | `title*`/`label*` SemiBold | 400/500 + letterSpacing | **P0** |
| `CpText`（新增） | `theme/Type.kt` | 不存在 | Emphasized 4 档 | P0 |
| `LightColors`/`DarkColors` | `theme/Color.kt:69,84` | 缺 8 个 container 角色 | 补全至与动态路径一致 | P1 |
| `withPureBlackSurfaces` | `theme/Theme.kt:154` | 6 个角色全压黑 | 保留 4 档极暗灰 | P1 |
| `PaletteStyle` | `theme/Theme.kt:111` | 硬编码 `TonalSpot` | 提为参数，默认 `Vibrant` | P1 |
| `CpSpacing` | `component/UiFoundation.kt:41` | 只有页面级间距 | 补卡片/行高/堆叠 4 组 | P1 |
| `CpGroupedListRow`（新增） | `component/ExpressiveKit.kt` | 不存在 | 形状变形列表行 | P1 |
| `CpFloatingToolbar` | `component/ExpressiveKit.kt:522` | tonal 3 + shadow 3 | shadow → 0 | P1 |
| `ExpressiveSectionHeader` | `component/ExpressiveKit.kt:588` | **零引用** | **删除** | P1 |
| `CpIconSize`（新增） | `theme/` 或 `component/` | 不存在 | 18/20/24 三档 | P2 |
| `CpToggleChip` | `component/ExpressiveKit.kt:551` | 已用 `ToggleButton` 形状变形 ✅ | 保持 | — |
| `CpSeekBar` / `CpWavyProgress` | `component/ExpressiveKit.kt:190,121` | 已是最佳实践 ✅ | 保持 | — |
| `CpCoverPlaceholder` | `component/ExpressiveKit.kt:474` | 已统一空封面 ✅ | 保持 | — |
| `CpPlayPauseButton` | `component/ExpressiveKit.kt:386` | 三重动效叠加 ✅ | 保持 | — |

---

## 6. 落地路线图

| 阶段 | 内容 | 验证方式 |
|------|------|---------|
| **P0-a** | `Shape.kt` 改 8 槽 + 官方刻度 | `:app:compileKotlinDesktop` + **逐屏离屏出图** |
| **P0-b** | `Type.kt` 字重/letterSpacing + `CpText` Emphasized | 出图核对标题层级是否"塌" |
| **P1-a** | `Color.kt` 补容器色阶；`withPureBlackSurfaces` 改极暗灰 | 出图核对浅色/深色/纯黑三态 |
| **P1-b** | `PaletteStyle` 提参 + 设置页选择器 | 切换 4 档各出图 |
| **P1-c** | `CpSpacing` 补 token；替换散落字面量 | grep 确认无残留非标度值 |
| **P1-d** | `CpGroupedListRow` + 列表/卡片按压反馈 | 出图 + 手感确认 |
| **P1-e** | 阴影逐处调整（§3.4 表） | 出图核对层级 |
| **P2-a** | 图标尺寸 token + 选中/未选中配对 | 出图 |
| **P2-b** | 删 `ExpressiveSectionHeader`；统一区块标题 | grep 确认零引用后再删 |
| **P2-c** | 品牌字体（需先量体积） | 打包体积对比 |

**每阶段的硬性要求**（来自 `AGENTS.md`）：

- 编译跑 `:app:compileKotlinDesktop` 与 `:core:compileKotlinDesktop`；⚠️ `commonMain` 整体编译，**看第一个报错的文件**。
- 测试结论只认 `**/test-results/**/TEST-*.xml`，必须确认 `skipped="0"`。
- **改版式必须离屏渲染出图** —— 编译 + 单测量不到宽度、看不见对齐。
- 同一文件一次只发一个 `Edit`，改完 grep 复核。

---

## 7. 验收清单

**形状**
- [ ] `AppShapes` 8 槽全填，值与 `ShapeTokens` 一致
- [ ] 全仓无 `RoundedCornerShape(32.dp)` 用于卡片（`extraLarge` 语义）
- [ ] 全仓无 `RoundedCornerShape(24.dp)` 用于分组容器（`large` 语义）
- [ ] `CpShapes` 与 `MaterialTheme.shapes.*` 职责不重叠

**排版**
- [ ] `titleLarge` = Regular(400)；`titleMedium/Small` = Medium(500)；`label*` = Medium(500)
- [ ] 15 档 `letterSpacing` 全部显式设置
- [ ] 时间码使用 tabular figures，秒数跳动时无宽度抖动
- [ ] `CpText` Emphasized 在页面标题处有实际使用

**颜色**
- [ ] 静态回退色板包含全部 `surfaceContainer*` 角色
- [ ] 动态取色与静态回退两条路径的**角色集合一致**
- [ ] 纯黑模式下卡片与背景仍有可辨色阶（不依赖描边）
- [ ] `outlineVariant` 的 9 处使用均不承担文本职责

**间距**
- [ ] 全仓间距值均为 4dp 倍数
- [ ] 卡片内 padding / 列表行高走 token

**图标**
- [ ] 导航与收藏具备 Filled/Outlined 配对
- [ ] 图标尺寸只出现 18/20/24 三档
- [ ] `ExpressiveSectionHeader` 已删除，区块标题只剩一套

**动效**
- [ ] 组件内无手写 `spring(...)`
- [ ] `effects` 系动效无回弹
- [ ] `tween` 字面量只出现 120/250/400 三档（`CpMotion` 内部除外）
- [ ] 列表行具备按压形状变形

---

## 8. 风险与不做的事

| 项 | 说明 |
|----|------|
| **不动 `AccountScreen.kt`** | 别人的 untracked 在途文件，且非合法 UTF-8（67 个坏字节）。改它会让整个 `:app:compileKotlinDesktop` 挂掉 |
| **不做全量图标族迁移** | Filled 已占 210/250，迁移收益低、回归风险高 |
| **不引入自定义字体前先量体积** | 中文全量字重会显著增加包体，必须实测后再决定 |
| **形状刻度变更必须出图** | 25 处消费点集体变小 4–8dp，编译与单测都发现不了观感回归 |
| **`Rainbow`/`FruitSalad`/`Monochrome` 不暴露给用户** | 与现有语义色（error/tertiary）冲突，音乐 App 场景下容易花 |
| **保留 `CpShapes.miniPlayer` 的不对称圆角** | 贴底元素的不对称是正确的，不要为了"统一"改掉 |

---

## 附：本次分析的方法论

本方案中所有 **M3E 令牌值**均来自 `javap` 读取本项目实际编译的
`material3-desktop-1.11.0-alpha07.jar` 中的 `androidx.compose.material3.tokens.ShapeTokens`
与 `TypeScaleTokens`，**不是凭记忆或对照文档**。原因：令牌值随版本变动，且文档与实现
偶有出入（例如 `titleLarge` 在本版本是 Regular(400)，与 M3 早期文档的 Medium 不同）。

复核命令：

```bash
JAR=$(cygpath -w ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.compose.material3/material3-desktop/1.11.0-alpha07/*/material3-desktop-1.11.0-alpha07.jar)
/e/java/bin/javap.exe -c -p -classpath "$JAR" androidx.compose.material3.tokens.ShapeTokens | grep -E "double [0-9]|Field Corner"
/e/java/bin/javap.exe -c -p -classpath "$JAR" androidx.compose.material3.tokens.TypeScaleTokens | grep -E "Weight:|WeightRegular|WeightMedium|WeightBold"
```

⚠️ Windows 上 classpath 分隔符是 `;`，路径必须 `cygpath -w`（见 `.workbuddy-ai/memory/MEMORY.md`）。
