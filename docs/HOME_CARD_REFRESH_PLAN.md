# 首页卡片重复度改造方案

> 状态：**已全部实施（桌面端）**。§0「今日速览」删除 + §2 四层权重 L1–L4 全部落地。
> 实施结果与验收数据见 §7。
>
> 诊断依据：按 `AGENTS.md` §2 的并行安全流程，临时把 `HomeActions` / `DesktopHomeLayout`
> 提为 `internal`，用 `ImageComposeScene` 以 1440×1400 离屏渲染桌面首页（浅色 / 深色各一张）。
> 渲染完已还原可见性改动、删除临时测试与 init script。

---

## 0. 已实施：「今日速览」删除（2026-10-02）

### 它为什么算重复

`DailySummaryCard`（`HomeScreen.kt`，原第 1172–1251 行）是**只在 `state.banners.isEmpty()`
时**顶替焦点图的替代卡，占 2/3 首屏宽。它内部是两块 `SummaryAction`：

| 子块 | 内容 | 点击去向 |
|---|---|---|
| 每日推荐 | `"${dailyCount} 首"` | `onOpenDaily` |
| 最近播放 | `"${recentCount} 首"` | `onOpenRecentPlays` |

**两块都只承载「计数 + 跳转」**，而真正的列表（`DailyMixCard` / 最近播放区块）
就在**同一屏的下方**。也就是说：同一个目的地在首屏出现了两次，其中一次
占的是最贵的版面（焦点图位）。

更矛盾的是触发场景 —— 无焦点图恰恰对应**未登录 / 新用户**，此时两个计数都是 `0`，
给「我的数据统计」毫无意义。

### 改法

- **删除 `DailySummaryCard` 与 `SummaryAction`**（改后即成死代码，已确认零引用后删除）。
- 焦点区改为：有焦点图 → `BannerCarousel` + `HeroQuickPanel`（与改前一致）；
  无焦点图 → **「快捷电台」提升为常规区块**（走 `HomeSectionCard`，
  与下方区块同规格、左边缘对齐），用可操作内容填版面。

### 为什么不是「让快捷电台摊平成一整条」

第一版实现直接复用了移动端的 `HeroQuickRow`（三张等宽裸卡），离屏渲染后发现
三张孤立卡片悬在页首、与下方「每日推荐」的左边缘对不齐，比原方案更碎。
故改为包进 `HomeSectionCard`，让它成为内容流里正常的一块。

### 对照参考

移动端 `MobileHomeLayout` **从一开始就没有**「今日速览」，无焦点图时直接不留空框。
这证明这个位置空着是可接受的 —— 桌面端不必硬填。

---

## 1. 问题：不是「卡片太多」，是「卡片长得一样」

实测截图里 7 个大区块用的是**同一套 recipe**：

```
Surface(shape = MaterialTheme.shapes.extraLarge, color = surfaceContainerLow / High)
```

| 区块 | 字段卡数 | 容器外形 | 内容排布 | 与其他区块的差异 |
|---|---|---|---|---|
| 每日推荐 | 10 | extraLarge | 两列曲目卡 | 基准 |
| 最近播放 | 12 | extraLarge | 两列曲目卡 | **仅标题不同** |
| 发现歌单 | 14 | extraLarge | 7 列封面卡 | 仅卡片形状不同 |
| 排行榜 | 5 | extraLarge | 1 列列表行 | 仅卡片形状不同 |
| 新碟上架 | 6 | extraLarge | 3×2 封面卡 | 仅卡片形状不同 |
| 热门歌手 | 7 | extraLarge | 7 列圆头像 | 仅卡片形状不同 |
| 新歌速递 | 8 | extraLarge | 两列曲目卡 | **与「每日推荐」同构** |

由此产生三个**可量化**的重复：

1. **视觉节奏平坦** —— 7 个同圆角、同深浅的容器纵向排列，一屏内看不到主次，
   只有一条条等宽色带。`IntrinsicSize.Max` 的底部对齐只修掉了参差边缘，
   顺带把「两块巨卡等高」这件事固化了下来。
2. **曲目列表重复 3 次** —— 每日推荐 10 + 最近播放 12 + 新歌速递 8 = **30 行同构的
   「封面 + 歌名 + 歌手 + 更多」**。
3. **版面分配与使用频率倒挂** —— 桌面端「最近播放」与「每日推荐」是并排
   `weight(1.15f) : weight(1f)` 的**等高巨卡**。日推是每日核心内容，最近播放是
   低频回溯操作，两者却拿到接近相等的面积。

---

## 2. 改法：四层视觉权重

目标不是减内容，而是**让每一层看起来就不是同一层**。

### L1　焦点图 + 右侧面板改「继续收听」

- 焦点图高度 `224dp` → `260dp`（`DesktopBannerHeight`），给 Hero 应有的分量。
- 右侧 `HeroQuickPanel` 由「三张固定电台入口」改为**「继续收听」**：
  - 有正在播放的曲目 → 显示封面 + 曲名 + 歌手 + 进度，点击回到播放页；
  - 没有在播 → 退回现有的三张电台入口（私人 FM / 心动模式 / 相似歌曲）。
- **动机**：当前首屏回答不了「我在听什么 / 接着听什么」，而这恰恰是音乐应用
  打开时最想知道的。三张电台入口是持续性低频操作，不该占用 Hero 的黄金位置。

### L2　新增「继续收听」横条，替代原「最近播放」巨卡

- 形态：横向 4–6 张封面卡，高度约 `150dp`，不再渲染 12 行列表。
- 底色用更浅一档的 `surfaceContainer`，与后方区块拉开差异。
- 「更多 →」跳转现有的 `RecentPlaysScreen()`（完整列表仍可达，功能不缩水）。
- **动机**：把「12 条同构曲目行」从最占地方的位置挪走，同时消除第 2 类重复的最大来源。

### L3　每日推荐独占整行，升为主角

- 不再与「最近播放」并排，改为独占一行、横向 `4 列 × 3 行 = 12 首`。
- 保留 `IntrinsicSize.Max` 的底部对齐思路 —— 与同一行右侧的「发现歌单」齐平。
- 相关的 `DESKTOP_RECENT_COUNT = 12` 与 `PLAYLIST_ROWS = 2` 是**互相绑定**的常量
  （代码注释已声明「改一个就要回头看另一个」），本次改动会打破这层绑定，
  需要一并重新推导高度关系。

### L4　中间四个区块去掉外层容器

- 发现歌单 / 排行榜 / 新碟上架 / 热门歌手：**去掉外层的 `extraLarge` 大容器**，
  只保留 `SectionHeader` + 内容，卡片直接落在页面背景上。
- 桌面端仍是栅格（信息密度优先），但视觉上从「7 个一样的盒子」变成**一个连续内容流**。
- ⚠️ **去掉容器后必须给卡片保留轮廓**：卡片自身的底色要加深一档，或走
  `bentoOutline()` 描边。否则「纯黑」主题下（`CpTheme` 会把中性容器压成 `#000`）
  卡片与背景同色，整块消失。这条是本仓库已踩过的坑。

---

## 3. 可复用资产：别再手写第 N 个 Surface

仓库里**已经有** `app/src/commonMain/kotlin/cp/player/app/ui/component/BentoCards.kt`：

| 组件 | 用途 |
|---|---|
| `BentoCard` | 基础卡，带按下微缩与 `bentoOutline()` 描边 |
| `BentoHeroCard` | 大面积强调色主行动卡 |
| `BentoActionCard` | 图标左上 + 箭头右上 + 标题压底 |
| `BentoStatCard` | 顶部说明 + 底部大号数字 |
| `BentoMiniTile` | 小方格入口 |

这族已经处理了**纯黑模式下的描边兜底**。但首页**一个都没用**，全部是手写 `Surface`。
本次改造应优先复用它，把首页从「各自拼容器」收敛到统一卡片族。

---

## 4. 移动端

首页桌面与移动是**两套独立的 Composable**（`DesktopHomeLayout` / `MobileHomeLayout`），
且两边区块顺序本来就不同（桌面焦点图与快捷电台并排，移动是上下两块）。

移动端目前**没有** L2 那类问题（最近播放是 `take(5)` 的列表，不是巨卡），
但 L1 的「继续收听」与 L4 的「去容器」同样适用。建议两边分提交，便于单独回滚。

---

## 5. 验收方式

按 `AGENTS.md` §1：**改版式不能只靠编译 + 单测**。

1. `:app:compileKotlinDesktop` 与 `:core:compileKotlinDesktop` 通过。
2. 离屏渲染出图核对（浅色 / 深色各一张）——重点看：
   - 是否还存在「两个同形大容器相邻」；
   - 纯黑模式下卡片轮廓是否还在；
   - 首屏（不需要滚动的那一屏）能否回答「在听什么 / 今天推荐什么」。
3. 测试结论从 `**/test-results/**/TEST-*.xml` 读，确认 `skipped="0"`。
4. 用完删除临时预览测试与 init script（`build-preview-*/`、`.gradle-*verify*`、
   `*.init.gradle`）——留着会**永久跳过被 exclude 的测试**。

---

## 6. 建议的落地顺序

改版式影响面大，建议分三步，每步可独立验收、独立回滚：

| 步骤 | 内容 | 风险 |
|---|---|---|
| ① | L4 去容器（纯视觉，不动布局结构） | 低 |
| ② | L2 + L3 版面重排（最近播放改横条、日推独占） | 中，涉及两个绑定常量 |
| ③ | L1 右侧面板改「继续收听」（引入播放态读取） | 中高，需接播放状态 |

如果只想先要「看起来不重复」的效果，做 ① 就能拿到八成收益。

---

## 7. 实施结果（2026-10-02，桌面端）

四层全部落地，集中在 `HomeScreen.kt`（桌面 `DesktopHomeLayout` 一侧）。

| 层 | 落地内容 | 关键改动 |
|---|---|---|
| §0 | 删除「今日速览」 | 删 `DailySummaryCard` + `SummaryAction`，净减 73 行；无焦点图时「快捷电台」提升为常规区块 |
| L4 | 中间区块去容器 | `HomeSectionCard` 加 `contained` 参数，抽出 `SectionTitleRow`；发现歌单 / 排行榜 / 新碟上架 / 热门歌手 / 新歌速递传 `contained = false` |
| L3 | 日推独占整行 | `DailyMixCard` 由 `weight(1.15f)` 改 `fillMaxWidth()`，`compact = true`（4 列 × 3 行 = 12 首） |
| L2 | 最近播放改横条 | `RecentPlaysSection` → `RecentPlaysRail` + `RecentPlayCard`（宽 160dp 横滑，1:1 封面 + 右上角 `MoreVert`） |
| L1 | 右侧面板改「继续收听」 | `HeroQuickPanel` 两态：有在播 → `HeroNowPlaying`（封面 + 曲名 + 进度条），无 → 原三行电台；`DesktopBannerHeight` 224 → 260dp |

### 过程中修正的三处

1. **无焦点图分支的「快捷电台」第一版更碎** —— 复用 `HeroQuickRow`（三张等宽裸卡）导致
   三张孤立卡悬在页首、与下方左边缘不齐。改为包进 `HomeSectionCard`（见 §0）。
2. **`HeroNowPlaying` 不能在组件内读 `AppModel.playback`** —— 离屏渲染时直接
   `IllegalStateException: MusicBackend.init() not called`。
   **这不只是测试问题**：`AppModel.playback` 的 getter 会去取 `MusicBackend` 单例，
   未 init 时抛异常。改为 `positionMs` / `durationMs` 由参数传入，订阅点收回到
   `HomeScreenContent`。
3. **位置轮询会让整页每秒重组 5 次** —— 引擎的 `positionMs` 每 200ms 变一次。
   在订阅处节流到秒并 `distinctUntilChanged`（`NowPlayingSnapshot` 的 KDoc 已写明），
   首页只画一条进度条，秒级精度足够。

### 验收数据

- `:app:compileKotlinDesktop` + `:core:compileKotlinDesktop` 通过。
- 测试 **362 个全过，skipped=0、failures=0、errors=0**（app 95 + core 267，
  从 `**/test-results/**/TEST-*.xml` 读）。测试类覆盖完整：app 13 个源文件 13 个类全跑，
  core 29 个测试文件（另有 `ManualDispatcher.kt` 为辅助文件）29 个类全跑。
  ⚠️ 核对时注意 XML 的 `name` 是**简单类名 + `[desktop]`**，不是全限定名 —— 按 FQ 名比对
  会误报「全部没跑」。
- 离屏渲染 5 张（浅/深 × 有/无焦点图 × 有/无在播）逐张核对。
- 可见性已还原 `private`，临时测试与 init script 已删；另清掉 5 个仅由删除遗留的
  死 import（`Album` / `AutoGraph` / `ChevronLeft` / `LibraryMusic` / `Person`）。
- 文件编码已验证：合法 UTF-8、无 BOM、无 GBK 损坏特征（`AGENTS.md` §4）。

### 未做

- **移动端 `MobileHomeLayout` 未动**（按 §4 建议，桌面 / 移动分提交便于单独回滚）。
  L1 的「继续收听」与 L4 的「去容器」对移动端同样适用，是下一批。
- §3 的 `BentoCards` 复用未做 —— 本次只去掉了容器，卡片本身仍是手写 `Surface`。
  若要再往深收一层，这才是下一刀。
