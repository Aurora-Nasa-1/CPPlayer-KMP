# 设置界面重构方案

> 诊断于 2026-09-30，基于**当时工作区**的实际状态（含并行会话的在途改动）。
> 本文只描述**目标状态与迁移路径**，不含已落地的代码改动。
>
> 关联文档：[`ARCHITECTURE.md`](ARCHITECTURE.md)、[`INTEGRATION_API.md`](INTEGRATION_API.md)、
> [`DEAD_CODE_AUDIT.md`](DEAD_CODE_AUDIT.md)。

---

## 0. 结论摘要

设置页目前的问题不是「不好看」，而是**三件事同时发生**：

1. **信息架构是平的、且有重复。** 13 个一级入口平铺，日常项（外观 / 播放）与开发者项
   （调试 / 渲染后端）与纯动作（赞助 / 新手引导）混在一起；「播放」与「交互逻辑」是两个页面
   却写着同一个设置项。
2. **有两条数据正确性问题在设置页被放大。** `AppModel.settings` 每次访问都新建实例，
   而 `DesktopRenderTuning` 又持有同 namespace 的第二个实例 —— 两者都会「全量回写」同一个
   properties 文件，**后写的会用陈旧快照覆盖先写的**。这是会丢用户设置的真 bug。
3. **保存模型缺失。** 开关是即时写，文本框是**每敲一个字符就写一次并重启服务器**，
   破坏性操作无二次确认，且没有任何「已保存 / 未保存 / 保存失败」的反馈通道。

本方案给出：**4 组 / 9 个一级入口**的新 IA、一套**默认值策略**、一套**三档提交语义**
（即时提交 / 显式提交 / 二次确认）、一份**文案规范**、以及响应式与可访问性的硬性要求。

**核心功能一个不减**；被删掉的只有「本来就不生效的开关」「不可达的死页面」「重复的第二份 UI」。

---

## 0.1 实施记录（2026-09-30）

本节记录**已落地**的部分与**与方案的偏差**。§1 起的正文保留原样，便于对照。

### 已落地

| 阶段 | 内容 |
|------|------|
| P0 · 数据丢失 bug | `defaultSettingsStorage()` 改为按 **(数据目录, namespace)** 返回共享实例，`AppModel.settings` 改 `by lazy`。原先 `cp_player_prefs` 上有**三个**互相覆盖的写者（`AppModel` 每次访问新建实例 / `MusicBackend` 注入的实例 / `DesktopRenderTuning` 的 lazy 实例），现在共用一个。 |
| P0 · 输入提交 | 新增 `SettingsTextInputItem`（草稿 → 校验 → 显式提交 → 未保存徽标）；端口加范围校验，接收端地址加 URL 校验。**输入 `8080` 不再逐字符写盘并重绑服务器。** |
| P0 · 令牌 | 改用 `SettingsConfirmItem` 二次确认；令牌从「不可选中的说明文字」改为可选中（可复制）的独立区块。 |
| P0 · 无效开关 | 删除 `play_immediately`（全仓库无消费点）；隐藏 `allow_remote_control`（端点未实现）。 |
| P0 · 死页面 | 删除 `SettingsDetailScreen.kt`（257 行，不可达）。 |
| P1 · 信息架构 | 新增 `SettingsRegistry.kt` 单一声明，取代原先四份互相漂移的平行结构；13 个平铺入口 → **4 组 11 项**；删除 `SettingsCategoryRail` 与三栏布局；宽屏默认选中第一项（不再有空态占位）。 |
| P1 · 合并/拆分 | 删「交互逻辑」「音源隔离」「赞助」；「本地服务器」拆为「本地流输出」+「外部推送与集成」。 |
| P2 · 组件 | 新增 `SettingsSegmentedItem` / `SettingsTextInputItem` / `SettingsConfirmItem`；`SettingsNote` 加 `INFO/WARNING/ERROR` 三档语义。 |
| P2 · 睡眠定时 | 收敛为单个入口行，打开**播放页同一个** `SleepTimerDialog`；对话框补上 90 分钟（原仅存在于设置页的下拉里）。 |
| P3 · 响应式 | 新增 `CpSpacing.formMaxWidth = 720.dp` 收口设置页宽度；新增 `CpBreakpoints`，替换内联 `840.dp`。 |
| P3 · 可访问性 | 全量语义：`mergeDescendants`、`Role.Switch` + `toggleable`（消除开关行的双焦点）、`heading()`、`selectableGroup` + `Role.RadioButton`、`liveRegion`、标题不再用 `maxLines` 截断。 |
| 默认值 | `external_push_enabled` 默认 **true → false**。 |
| 测试 | 新增 `SettingsRegistryTest`：id 唯一、分组连续、无重复目的地、IA 快照。 |

### 与方案的偏差（均为有意）

1. **「其他」组没有套一层「高级」页。** 方案 §2.2 画的是「高级 → 诊断 + 渲染后端」，
   落地改成 4 个直接入口。为一个 2 项集合多一层跳转不划算，分组标题本身已经完成了
   「把开发者项和日常项分开」这件事。
2. **`exposeStream` 默认值保持 `true`。** §3.2 建议改 `false`，但媒体面是本功能的由来 ——
   默认关掉会让「启用本地流输出」变成什么都不做。它本来就被 `enabled = false` 挡着，
   安全边界已经存在。
3. **缓存占用大小未做**（§1.2 R8 的一半）。需要新增 `expect fun imageCacheSizeBytes()`
   跨三端实现；本轮先修**误导性破坏性配色**（那才是当时更实际的问题）。
4. **令牌未做打码 + 复制按钮。** 只修了真正的缺陷（不可选中）。打码会给常用路径加一次点击，
   而本地工具的令牌本来就以明文存在 `integration.json` 里。
5. **`ProviderIsolationScreen.kt` 未删除。** 它是 **untracked** 文件（并行会话的在途工作），
   按 `AGENTS.md` 的约定不动。其内容已全部并入「账号与登录」，
   **已无人引用，提交前可安全删除**。
6. **设置搜索 / 分组折叠 / 恢复默认未做**（原 P4）。注册表已经带上了 `keywords` 字段，
   搜索只差一个过滤 UI。

---

## 1. 现状诊断

### 1.1 信息架构

| # | 问题 | 位置 |
|---|------|------|
| A1 | **13 个一级入口平铺**，无分组、无层级。日常项与开发者项同级。 | `SettingsScreen.kt:298-395` |
| A2 | **「播放」与「交互逻辑」重复**：`play_immediately` 同时出现在两个页面，两者副标题都写「播放行为」。 | `PlaybackSettingsScreen.kt:69-81`、`SettingsSubScreens.kt:114-149` |
| A3 | **「交互逻辑」只有 1 个设置项**，却占了一个一级入口和一个整页。 | `SettingsSubScreens.kt:122-137` |
| A4 | **`SettingsDetailScreen` 是不可达的死页面**，内容与「外观 + 播放 + 存储」三页重叠。全仓库无任何 push 点。 | `SettingsDetailScreen.kt`（257 行） |
| A5 | **三栏布局的分类导航是坏的**：硬编码 4 个标签、永远高亮 index 0、映射关系（0→外观 / 1→播放 / 2→交互逻辑 / 其余→音源管理）与实际列表完全脱节。 | `SettingsScreen.kt:54-68`、`139-144` |
| A6 | **「账号与登录」「音源管理」「音源隔离」三个入口同属一个域**，且互相跳转成环：账号页 → 音源管理；音源隔离页 → 账号 + 音源管理 + 存储。 | `AccountScreen.kt:308`、`ProviderIsolationScreen.kt:140` |
| A7 | **「本地服务器」是巨石页**：5 个分组 + 8 条说明，把「出站推送」「入站服务」「数据面开关」「只读地址展示」混在一页。 | `LocalServerSettingsScreen.kt:70-321` |
| A8 | **「赞助」与「新手引导」是动作，不是设置分类**，却和设置页同级。 | `SettingsScreen.kt:379-394` |
| A9 | **同一设置项在多个入口可达**：曲库首页的快捷卡片直接 push `StorageSettingsScreen` / `AppearanceSettingsScreen` / `PlaybackSettingsScreen`，绕过设置根页。 | `LibraryScreen.kt:162-166` |

### 1.2 功能与实现

| # | 问题 | 位置 |
|---|------|------|
| B1 | **`play_immediately` 没有任何消费点** —— 全仓库 grep 只有「两个设置页自己读写」，播放链路从不读它。**这个开关目前是纯装饰。** | `PlaybackSettingsScreen.kt:132-139` |
| B2 | **端口每敲一个字符就提交**：`input.toIntOrNull()?.let(AppModel::setLocalServerStreamPort)`。输入 `8080` 会依次提交 `8 / 80 / 808 / 8080`，每次触发一次全量文件回写 + 服务器重绑。 | `LocalServerSettingsScreen.kt:132-137` |
| B3 | **接收端地址同样是每敲一字符就写**，且没有 URL 合法性校验。 | `LocalServerSettingsScreen.kt:236-240` |
| B4 | **「重新生成访问令牌」一键即生效、无二次确认**：一次误触会让所有已连接设备立刻失效。 | `LocalServerSettingsScreen.kt:160-174` |
| B5 | **「允许远程播控」是一个明确无效的开关** —— 页面自己写着「端点尚未实现，打开它暂时不会产生任何效果」。 | `LocalServerSettingsScreen.kt:206-214`、`217` |
| B6 | **令牌以明文写在说明文字里**，且该说明**没有** `SelectionContainer`（同一页里另一处地址有），用户没法复制。 | `LocalServerSettingsScreen.kt:182-185` vs `218-224` |
| B7 | **「清理图片缓存」被涂成破坏性配色**（`errorContainer`），但它是非破坏性操作 —— 误导性的视觉信号。且不显示缓存占用大小，用户无法判断「值不值得清」。 | `SettingsSubScreens.kt:181-192` |
| B8 | **`AppModel.settings` 每次访问都新建实例**（`get()` 属性），而桌面实现**构造时读全文件、每次写全量回写**。 | `AppModel.kt:61`、`DesktopSettingsStorage.kt:17-40` |
| B9 | **⚠️ 数据丢失 bug**：`DesktopRenderTuning` 用 `by lazy { defaultSettingsStorage() }` 持有**同一个 `cp_player_prefs` namespace 的第二个实例**，带着自己那份陈旧快照。用户在渲染后端页改一次 Vsync，就会把此前写入的 `theme_mode` / `pure_black` / `playback_quality` 全部**覆盖回旧值**。 | `DesktopRenderTuning.kt:158` |
| B10 | **默认值散落在 20+ 处**，写成 `getString(...)?.toBooleanStrictOrNull() ?: true` 这种字面量，没有单一事实源。 | `AppModel.kt:134/152/167/178/194/302` 等 |
| B11 | **页面用 `remember { mutableStateOf(AppModel.xxx()) }` 复制一份持久值**，外部变更（重置、封面取色、其他页面改同一项）不会同步到这个副本，UI 会显示陈旧值。 | `SettingsSubScreens.kt:46-48`、`PlaybackSettingsScreen.kt:41` |
| B12 | **睡眠定时有两套控制**：设置页一个开关 + 一个下拉，播放页还有一个对话框，三者操作同一份运行时状态，语义重叠且剩余时间无法反选回原预设。 | `PlaybackSettingsScreen.kt:83-116`、`PlayerScreen.kt:191-196` |

### 1.3 状态反馈与保存机制

| # | 问题 |
|---|------|
| C1 | **没有统一的保存模型。** 开关即时写、文本框每字符写、按钮动作直接执行，三者混在一起，用户无法预期「改完要不要点保存」。 |
| C2 | **没有「已保存」反馈。** 唯一有反馈的是下载目录（`UiEvents.notify`），其余全部静默。 |
| C3 | **没有「保存失败」通道。** 桌面端 `DesktopSettingsStorage` 的持久化异常被 `catch (_: Exception)` 静默吞掉（`DesktopSettingsStorage.kt:22/33`），磁盘写失败时用户看到的仍是「已开启」。 |
| C4 | **没有「未保存」状态**，也没有离开页面时的脏数据提示。 |
| C5 | **状态文本混在说明里**：服务器运行状态、推送结果、令牌都是 `SettingsNote`（`bodySmall` + `onSurfaceVariant`），与普通提示同等视觉权重，且不随状态变化播报。 |

### 1.4 响应式

| # | 问题 | 位置 |
|---|------|------|
| D1 | **设置详情页不收敛宽度**：详情面板直接用 `Modifier.fillMaxWidth()`，在 2K/4K 屏上一行横跨整屏。**违反仓库自己的硬规则**（页面宽度必须取 `CpSpacing.pageMaxWidth`）。 | `SettingsSubScreens.kt:103`、`140`、`198` 等 |
| D2 | **三条互不相同的布局/导航路径**：`embedded && expanded`（两栏）、`expanded`（三栏 + 坏的分类导航）、compact（列表 + push）。同一份内容三套壳。 | `SettingsScreen.kt:100-190` |
| D3 | **两套 Scaffold 混用**：根页用 `AppScaffold(onBackPressed = pop)`，详情页用 `LegacyPageScaffold(onBackPressed = null)` + 自绘返回按钮。桌面端根页会显示一个多余的返回箭头。 | `SettingsScreen.kt:132-136`、`SettingsSubScreens.kt:105-109` |
| D4 | **详情面板的空态是「选择中间菜单查看设置内容」** —— 用户进来看到一句让人困惑的占位文案，而不是默认打开第一项。 | `SettingsScreen.kt:119-126` |
| D5 | **断点 `840.dp` 是内联魔法数**，没有命名常量，三处各写一遍。 | `MainScreen.kt:181`、`SettingsScreen.kt` 等 |

### 1.5 可访问性

| # | 问题 | 位置 |
|---|------|------|
| E1 | **开关行有两个可聚焦目标**：整行 `onClick` + 内层 `Switch(onCheckedChange)`。读屏会为一设置项念两遍，且行本身没有 `Role.Switch`。 | `SettingsKit.kt:203-253` |
| E2 | **下拉行不告知「可展开」**：行无 `role = Role.Button`、无 `onClickLabel`，尾部 `ArrowDropDown` 的 `contentDescription = null`。 | `SettingsKit.kt:281-322` |
| E3 | **分组标题不是 heading**：`SettingsSection` 只是普通 `Text`，无 `semantics { heading() }`，读屏无法按标题跳转分组。 | `SettingsKit.kt:59-75` |
| E4 | **行内元素语义不合并**：图标 + 标题 + 副标题 + 控件是 4 个独立节点，读屏会逐条念碎片。 | `SettingsKit.kt:163-182` |
| E5 | **关键信息用最低对比度呈现**：令牌、局域网警告、「远程播控尚未实现」全部是 `bodySmall` + `onSurfaceVariant`。浅色主题下这一组合接近临界值。 | `SettingsKit.kt:84-91` |
| E6 | **状态变化不播报**：服务器状态、推送结果、保存失败都是静态文本，无 `liveRegion`。 | `LocalServerSettingsScreen.kt:122-126`、`281-288` |
| E7 | **大字体下截断**：标题 `maxLines = 2`、副标题 `maxLines = 3` + `Ellipsis`，200% 字号时设置项含义会被吃掉。 | `SettingsKit.kt:149-161` |
| E8 | **触摸目标**：`CpSpacing.touchTarget = 48.dp` 已定义，但设置行内的尾部图标按钮未显式声明，实际只有 24dp。 | `UiFoundation.kt:41`、`SettingsKit.kt` 各行 |

### 1.6 命名与文案

| # | 问题 | 例 |
|---|------|-----|
| F1 | **同一概念四个名字** | 「播放」/「播放设置」/「交互逻辑」/「偏好设置」 |
| F2 | **音源领域三个名字** | 「音源管理」/「切换音乐源」/`Provider` |
| F3 | **选项名不自解释** | 取色来源的「默认」——默认什么的默认？ |
| F4 | **同类选项命名不一致** | 主题模式用「跟随系统」，取色来源却用「系统」 |
| F5 | **副标题重复标题** | 「调试」→ 页面标题却是「API 健康监控」 |
| F6 | **术语过重** | 「数据面」「媒体面」「VRR / 刷新率抖动」 |
| F7 | **承诺与实现不符** | 「立即播放」的副标题描述了一个未实现的「仅加入队列」行为 |
| F8 | **同一设置项两套副标题** | 纯黑模式：`SettingsSubScreens.kt:88` vs `SettingsDetailScreen.kt:139` |

---

## 2. 目标信息架构

### 2.1 设计原则

1. **先场景，后领域。** 一级分组按用户「想干什么」划分（我要改播放/我要连别的软件/我要管账号），
   不按代码模块划分。
2. **一级入口 ≤ 9。** 超过 9 项必须再分组或下沉。当前 13 项 → 目标 9 项。
3. **日常在前，高级在后。** 开发者/诊断类收进「高级」组，置于列表最末，不做隐藏（本项目是
   开发者友好的开源项目，隐藏会增加排查成本）。
4. **动作不占分类位。** 「重看新手引导」「恢复默认」这类动作放在所属分组内，不做一级入口。
5. **一个设置项只出现在一个地方。** 不允许任何 key 被两个页面读写。
6. **页面标题即分类名。** 一级入口的标题 = 详情页的标题，不出现两个名字。

### 2.2 新 IA 树

```
设置
├─ 通用
│   ├─ 外观与主题          ← 主题模式 · 取色来源 · 纯黑模式
│   ├─ 播放与音质          ← 默认音质 · 立即播放 · 睡眠定时
│   └─ 下载与存储          ← 下载目录 · 缓存占用与清理
│
├─ 账号与音源
│   ├─ 账号与登录          ← 音源切换 · 多账号 · 登录/登出 · 隔离开关 · 清除登录数据
│   └─ 音源管理            ← 导入 / 切换 / 移除音源模块
│
├─ 连接与集成
│   ├─ 本地流输出          ← 启用 · 音频输出目标 · 端口 · 绑定范围 · 访问令牌
│   └─ 外部推送与集成      ← 接收端地址 · 自动推送 · 测试/推送动作 · 数据面与媒体面开关
│
└─ 其他
    ├─ 关于与支持          ← 版本 · 更新 · 项目主页 · 赞助入口
    ├─ 高级                ← 诊断（原「调试」）· 渲染后端（仅桌面）
    └─ 重看新手引导        ← 动作，点击即进入引导，不是页面
```

**变化点：**

- 13 → 9 个一级入口，按 4 组归类。
- 「交互逻辑」**删除**，`play_immediately` 归入「播放与音质」。
- 「音源隔离」**删除**：唯一的真设置项（`isolation_switch_account`）移入「账号与登录」，
  破坏性操作（清除登录态）移入「账号与登录」的危险区，三个循环跳转链接全部取消。
- 「本地服务器」**拆成两页**：「本地流输出」（我对外提供服务）与「外部推送与集成」
  （我把内容推给别人 + 允许别人读我）。
- 「赞助」**并入**「关于与支持」的一个分组。
- 「新手引导」**降级为动作**，不再占一级入口。
- 「调试」**改名**「诊断」。
- `SettingsDetailScreen` **删除**（不可达死页面）。

### 2.3 新旧映射表

| 旧入口 | 去向 |
|--------|------|
| 账号与登录 | → 「账号与登录」（保留页面，新增隔离开关 + 危险区） |
| 外观 | → 「外观与主题」（仅改名） |
| 播放 | → 「播放与音质」（保留，接收 `play_immediately`） |
| 交互逻辑 | ❌ 删除，内容并入「播放与音质」 |
| 存储与下载 | → 「下载与存储」 |
| 本地服务器 | ✂️ 拆为「本地流输出」+「外部推送与集成」 |
| 音源管理 | → 「音源管理」（保留） |
| 音源隔离 | ❌ 删除，1 个开关并入账号页，1 个破坏性动作并入账号页 |
| 调试 | → 「高级」组下的「诊断」 |
| 关于 | → 「关于与支持」 |
| 赞助 | → 「关于与支持」内的「支持项目」分组 |
| 新手引导 | → 「其他」组内的动作行 |
| 渲染后端（桌面） | → 「高级」组 |
| `SettingsDetailScreen`（不可达） | ❌ 删除整个文件 |

### 2.4 各页条目清单（目标状态）

**通用 › 外观与主题**
| 条目 | 控件 | 说明 |
|------|------|------|
| 主题模式 | 分段控件（跟随系统 / 浅色 / 深色） | 3 个选项，用分段控件替代「下拉 + 底部弹层」，0 次额外点击 |
| 取色来源 | 分段控件（跟随系统 / 跟随封面 / 固定配色） | 不可用项**隐藏**而非置灰（置灰的选项等于噪声） |
| 纯黑模式 | 开关 | 副标题统一为「深色主题下使用纯黑背景，OLED 屏幕更省电」 |

**通用 › 播放与音质**
| 条目 | 控件 | 说明 |
|------|------|------|
| 默认音质 | 下拉（标准 / 较高 / 无损 / Hi-Res） | 保留下拉（4 项 + 后续可能扩展） |
| 立即播放 | 开关 | **见 §6 决策点 D1**：接线或删除，不允许保留空开关 |
| 定时关闭 | 入口行 | 打开**播放页同一套**睡眠定时对话框（单一事实源），行尾显示剩余时间 |

**通用 › 下载与存储**
| 条目 | 控件 | 说明 |
|------|------|------|
| 下载目录 | 入口行（桌面） / 只读说明（Android） | 保留 |
| 图片缓存 | 按钮行，显示占用大小 | 改中性配色；文案「清理 128 MB 缓存」；无占用时禁用 |
| 数据与缓存总占用 | 只读行 | 新增：让「清理」有参照物 |

**账号与音源 › 账号与登录**
| 条目 | 控件 | 说明 |
|------|------|------|
| 当前音源 | 入口行 → 音源管理 | 保留 |
| 已保存账号 | 列表行 | 保留（切换 / 移除） |
| 添加账号 / 登录表单 | 表单 | 保留 |
| 切换音源时刷新账号资料 | 开关 | 从「音源隔离」迁入 |
| ⚠️ 清除本音源的登录数据 | 危险按钮行 | 从「音源隔离」迁入，二次确认 |

**连接与集成 › 本地流输出**
| 条目 | 控件 | 说明 |
|------|------|------|
| 启用本地流输出 | 开关 | 保留 |
| 音频输出 | 分段控件（本机播放 / 仅对外提供） | 2 项 → 分段控件 |
| 流输出端口 | 文本输入（草稿 + 显式提交） | 见 §4.2 |
| 绑定范围 | 分段控件（仅本机 / 局域网） | 2 项 → 分段控件；选局域网时内联警告 |
| 访问令牌 | 折叠区（打码 + 显示/复制 + 重新生成） | 见 §6 |
| 服务状态 | 状态行（含 `liveRegion`） | 从说明文字提升为独立状态行 |

**连接与集成 › 外部推送与集成**
| 条目 | 控件 | 说明 |
|------|------|------|
| 接收端地址 | 文本输入（草稿 + 显式提交 + 校验） | 见 §4.2 |
| 曲目变化时自动推送 | 开关 | 默认值改 false（见 §3） |
| 测试连接 | 动作行，结果内联显示 | 结果就地展示 ✓/✗ + 原因，不再甩到页尾 |
| 推送当前曲目 / 推送当前队列 | 动作行 | 保留 |
| 允许第三方读取音源数据 | 开关 | 原「开放数据面」 |
| 允许第三方拉取音频流 | 开关 | 原「开放媒体面」 |
| 允许第三方控制播放 | 开关 | 原「允许远程播控」——**端点未实现前不显示** |
| 接口地址（只读） | 折叠区 | 保留，含复制按钮 |

**其他 › 关于与支持**：版本 · 提交哈希 · 检查更新 · 项目主页 · 维护者 · 赞助入口
**其他 › 高级**：诊断（原调试）· 渲染后端（仅桌面）
**其他**：重看新手引导（动作行）

---

## 3. 默认值策略

### 3.1 四条规则

1. **最小权限。** 任何「把数据发到本机之外」或「打开一个网络端口」的开关，默认**关闭**。
2. **可逆优先。** 有不确定性的场景，默认选那个「改错了也不心疼」的值。
3. **系统一致。** 涉及外观的，默认跟随系统，而不是替用户做决定。
4. **单一事实源。** 所有默认值集中在一个对象里，不允许散落的 `?: "exhigh"` 字面量。

### 3.2 默认值变更（关键项）

| 设置项 | 现值 | 建议值 | 理由 |
|--------|------|--------|------|
| `external_push_enabled`（自动推送） | **true** | **false** | 用户从未配置过接收端地址时，默认开启自动推送是越权的：会向一个用户没设置的地址发请求。 |
| `local_server_expose_stream`（媒体面） | **true** | **false** | 与「最小权限」冲突。当前默认开着，只因为 `local_server_enabled` 默认关所以没暴露；但两个开关的默认值语义应该一致。 |
| `local_server_expose_data_api`（数据面） | false | false ✅ | 已正确。 |
| `local_server_enabled` | false | false ✅ | 已正确。 |
| `theme_mode` | SYSTEM | SYSTEM ✅ | 已正确。 |
| `color_source` | FIXED | FIXED ✅ | 固定配色是确定性的，比「跟随封面」可预测。 |
| `pure_black` | false | false ✅ | 已正确。 |
| `playback_quality` | `exhigh` | ⚠️ 决策点 | 见 §6 决策点 D2。 |
| `isolation_switch_account` | true | true ✅ | 保持资料新鲜，符合预期；但需在文案里说明会产生一次网络请求。 |
| `play_immediately` | true | ⚠️ 决策点 | 见 §6 决策点 D1。 |
| `desktop_render_api` | AUTO | AUTO ✅ | 跟随 Skiko 默认最安全。 |
| `desktop_vsync_override` | 不干预 | 不干预 ✅ | 已正确。 |

### 3.3 落地形式

新增集中定义（`core` 或 `app` 的 `SettingsDefaults`）：

```kotlin
object SettingsDefaults {
    const val THEME_MODE = ThemeMode.SYSTEM
    const val COLOR_SOURCE = ColorSource.FIXED
    const val PURE_BLACK = false
    const val PLAYBACK_QUALITY = "exhigh"
    const val AUTO_PUSH = false          // ← 变更
    const val EXPOSE_STREAM = false      // ← 变更
    const val EXPOSE_DATA_API = false
    const val ALLOW_REMOTE_CONTROL = false
    const val ISOLATION_SWITCH_ACCOUNT = true
    // ...
}
```

并引入**类型化读写门面**，消灭 `getString(...)?.toBooleanStrictOrNull() ?: true` 这种模式：

```kotlin
class AppSettings(private val storage: SettingsStorage) {
    fun bool(key: String, default: Boolean): Boolean
    fun putBool(key: String, value: Boolean)
    fun int(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun <T : Enum<T>> enum(key: String, default: T): T
    fun putEnum(key: String, value: Enum<*>)
}
```

**迁移**：保留全部旧 key 名（不做重命名，避免用户设置丢失）；新增 `settings_version: Int`
用于未来迁移；`dynamic_color` 的 legacy 迁移逻辑保留一个版本后删除。

---

## 4. 交互流程与保存机制

### 4.1 三档提交语义

设置项按「误操作的代价」分三档，**每档的交互契约不同**：

| 档 | 适用 | 提交时机 | 反馈 |
|----|------|----------|------|
| **A · 即时提交** | 开关、分段控件、下拉、选项组 | 操作即写盘 | 控件自身状态即反馈；**不弹提示**。仅当副作用较重（重绑服务器、重生成令牌）时给一次 Snackbar。 |
| **B · 显式提交** | 文本输入（端口、URL、路径） | 点「应用」/ IME 完成 / 失焦校验通过 | 未提交时行尾显示「未保存」徽标；非法值 `isError` + 就地说明；提交成功 Snackbar「已应用」。 |
| **C · 二次确认** | 重新生成令牌、清除登录数据、清理缓存、恢复默认 | 弹 `AlertDialog` 说明后果 | 成功后 Snackbar + 可撤销项提供「撤销」。 |

**为什么 A 档不弹提示**：一个开关弹一次 Snackbar 会让设置页变成提示轰炸机，
用户会开始无视所有提示 —— 那才是真正的反馈失效。**A 档的反馈就是控件本身。**

**为什么 B 档必须显式提交**：当前实现（`LocalServerSettingsScreen.kt:132-137`）在
输入 `8080` 的过程中提交了 `8 / 80 / 808` 三个非法或非预期值，每次都触发
「全量文件回写 + 服务器重绑」。这是**功能缺陷**，不是风格问题。

### 4.2 文本输入的交互契约

```
进入 → 读取持久值填入草稿 → 用户编辑（不写盘）
     → 校验（防抖 300ms）
        ├─ 合法 → 启用「应用」按钮，行尾「未保存」徽标
        └─ 非法 → isError + supportingText 说明原因，禁用「应用」
     → 提交：写盘 → 徽标消失 → Snackbar「已应用」
     → 离开页面且有未提交草稿 → AlertDialog「有未保存的修改」
```

**端口校验**：`1024–65535`；若端口被占用，提示「8080 已被占用，是否改用 8081？」
并给出自动探测的空闲端口（一次点击即可接受）。

**URL 校验**：必须为 `http://` 或 `https://` + 主机；端口缺省时按 scheme 补默认值；
校验通过后「测试连接」按钮才可用。

### 4.3 状态反馈矩阵

| 场景 | 通道 | 是否播报（a11y） |
|------|------|------------------|
| A 档即时设置变更 | 无（控件状态） | 控件自身状态变化 |
| 副作用较大的即时变更（重绑、令牌） | Snackbar | `liveRegion = Polite` |
| B 档未保存 / 非法 | 行内徽标 / 行内错误 | `liveRegion = Polite` |
| B 档提交成功 | Snackbar | `liveRegion = Polite` |
| 保存失败（写盘异常） | **行内错误 + Snackbar**（当前被静默吞掉） | `liveRegion = Assertive` |
| 破坏性操作 | 先 `AlertDialog`，后 Snackbar | 对话框本身可聚焦 |
| 服务状态 / 推送结果 | 页内状态行（不再混在说明里） | `liveRegion = Polite` |
| 需要重启才生效 | 行尾「需重启」徽标 | 徽标文本可读 |

### 4.4 设置根页的交互

- **搜索框**（Phase 2）：置于列表顶部，按标题 + 副标题 + 关键词索引过滤。
  每个设置项在注册表里带 `keywords`（例：纯黑模式 → `oled / 省电 / amoled / 黑色`）。
- **分组标题可折叠**（Phase 2，仅 compact）：默认展开，记住折叠状态。
- **恢复默认**：每个分组尾部一个「恢复本组默认值」（二次确认）；
  「高级」组内提供全局「恢复全部设置」（二次确认，且**明确声明不会清除账号与令牌**）。
- **桌面 2 栏布局默认选中第一项**，消灭「选择中间菜单查看设置内容」的空态。

---

## 5. 文案规范

### 5.1 术语表（强制统一）

| 概念 | 唯一用词 | 禁止 |
|------|----------|------|
| 可插拔的音源模块 | **音源** | 音乐源、Provider（UI 文案内） |
| 把音频流推给接收端 | **推送** | 投送、发送 |
| 接收推送的软件 | **接收端** | 客户端、播放端 |
| 对外提供数据接口 | **接口** | API、数据面 |
| 状态检查页 | **诊断** | 调试、健康监控 |

### 5.2 命名规则

- **标题**：名词短语，2–6 字，**不带「设置」后缀**（页面已经在设置里）。
- **副标题**：一行，回答「它会做什么 / 什么时候用得上」，**不重复标题**。
  不写实现细节（「监听 0.0.0.0」→ 移到高级信息里）。
- **开关文案**：动词开头，描述「开启后发生什么」。例：「曲目变化时自动推送」而非「自动推送」。
- **选项文案**：自解释，同类保持一致。
- **危险项**：标题前加警示图标，文案直说后果（「所有已连接设备将立即失效」）。

### 5.3 逐条文案修订

| 位置 | 现文案 | 新文案 |
|------|--------|--------|
| 取色来源选项 | 系统 / 封面 / 默认 | 跟随系统 / 跟随封面 / 固定配色 |
| 纯黑模式副标题 | （两处不一致） | 深色主题下使用纯黑背景，OLED 屏幕更省电 |
| 播放页标题 | 播放设置 | 播放与音质 |
| 交互逻辑页标题 | 交互逻辑 | ❌ 删除 |
| 调试 | 调试 / API 健康监控 | 诊断 |
| 开放数据面 | 开放数据面 | 允许第三方读取音源数据 |
| 开放媒体面 | 开放媒体面 | 允许第三方拉取音频流 |
| 允许远程播控 | 允许远程播控 | ❌ 未实现前不显示 |
| 绑定范围选项 | 仅本机 / 局域网 | 仅本机 / 局域网（同网段设备可访问） |
| 音频输出选项 | 本机声卡 / 只做服务器 | 本机播放 / 仅对外提供 |
| 重新生成令牌 | 重新生成访问令牌 | 重新生成访问令牌（所有已连接设备将失效） |
| 清理图片缓存 | 清理图片缓存 | 清理图片缓存（128 MB） |
| 渲染后端副标题 | …（VRR / 刷新率抖动相关） | 显示后端与垂直同步；画面撕裂或卡顿时可调整 |
| 推送测试 | 测试连接 | 测试连接（请求接收端 /api/health）→ 结果内联 |
| 立即播放 | 点击歌曲后立即开始播放，而不是只加入队列 | 见 §6 决策点 D1 |

---

## 6. 响应式规范

### 6.1 断点与布局

| 断点 | 宽度 | 布局 | 导航 |
|------|------|------|------|
| **Compact** | < 840dp | 单栏 | 根列表 → push 详情页；顶栏返回 |
| **Medium** | 840–1199dp | 两栏（左 300dp 分类 + 右详情） | 右栏内联切换，无 push |
| **Expanded** | ≥ 1200dp | 两栏（左 300dp + 右详情，居中） | 同上 |

**删除三栏布局与 `SettingsCategoryRail`**：两栏已经足够，第三栏的分类导航是冗余且当前是坏的。

**断点常量必须命名**：新增 `CpBreakpoints.compact / .medium / .expanded`，
替换 `MainScreen.kt:181` 等处内联的 `840.dp`。

### 6.2 内容宽度

- 设置详情页的内容列宽取**新 token `CpSpacing.formMaxWidth = 720.dp`**，并用
  **`Modifier.widthIn(max = ...).fillMaxWidth()`** 的顺序（顺序反了 `widthIn` 是空操作 —— 本仓库踩过）。
- **不直接用 `pageMaxWidth = 1400.dp`**：设置页是表单/列表，1400dp 的行宽会让
  「标题—控件」之间的视线距离过远，是最典型的「大屏显得乱」。
  720dp 是表单类内容的舒适上限。
- 左右两侧留白由居中产生，不写死 padding。

### 6.3 其他

- **详情面板默认选中第一项**（Medium/Expanded），无空态。
- **单一 Scaffold**：新增 `SettingsScaffold(title)`，内部依据 `LocalIsExpanded` 与是否
  embedded 决定「是否显示顶栏 / 是否显示返回按钮」，详情页不再各自拼 Scaffold。
- **`embedded` 参数与 `SettingsDetail` 枚举解耦**：改由注册表驱动（见 §8）。

---

## 7. 可访问性规范

| # | 要求 | 落地 |
|---|------|------|
| A1 | **一行一个语义节点** | 所有设置行加 `Modifier.semantics(mergeDescendants = true)`，读屏念「默认音质，较高，按钮」而非 4 段碎片 |
| A2 | **开关行只有一个可聚焦目标** | 行用 `Modifier.toggleable(value, onValueChange, role = Role.Switch)`；内层 `Switch(onCheckedChange = null)` |
| A3 | **下拉/入口行声明角色与动作** | `role = Role.Button` + `onClickLabel = "打开选项"`；尾部图标给有意义的 `contentDescription` |
| A4 | **分组标题是 heading** | `SettingsSection` 加 `Modifier.semantics { heading() }` |
| A5 | **选项组是 selectableGroup** | 分段控件/下拉弹层内的选项容器加 `Modifier.selectableGroup()`，选项用 `Modifier.selectable(role = Role.RadioButton)` |
| A6 | **状态变化可播报** | 服务状态、推送结果、保存失败、未保存徽标：`liveRegion = Polite`（失败用 `Assertive`） |
| A7 | **不靠颜色传达状态** | 开关保留 Check 图标；分段控件保留选中态文字；错误除红色外必须有文案 |
| A8 | **对比度** | 关键信息（令牌、局域网警告、失败原因）不得用 `bodySmall + onSurfaceVariant`；改用 `onSurface` 或带容器的强调条（`errorContainer` / `tertiaryContainer` + 图标） |
| A9 | **大字体不截断** | 标题 `maxLines` 放开（或 3）；副标题放开为 4；行高 `heightIn(min = 68.dp)` 允许增长，不加固定高度 |
| A10 | **触摸目标 ≥ 48dp** | 所有行内图标按钮显式 `size(CpSpacing.touchTarget)` 或 `minimumInteractiveComponentSize()` |
| A11 | **桌面键盘可达** | 所有行可 Tab 聚焦（`clickable` 已提供），Enter/Space 激活，Esc 关闭弹层；焦点环可见 |
| A12 | **尊重减弱动效** | 新组件一律从 `CpMotion` 取动效；不新增手写 `spring` |

**验证方式**：改版式不能只靠编译 + 单测 —— 用离屏渲染出图，分别核对
① 浅色 / 深色；② Compact / Medium / Expanded；③ 100% / 200% 字号。
做法见 `cpplayer-kmp-verify` skill。

---

## 8. 功能与实现的重写清单

| # | 项 | 现状 | 目标 |
|---|----|------|------|
| R1 | **设置存储实例** | `AppModel.settings` 是 `get()` 属性，每次访问新建实例（每次读都重读全文件） | 改为 `by lazy` 单例；所有读写走它 |
| R2 | **⚠️ 数据丢失 bug** | `DesktopRenderTuning` 持有同 namespace 的第二个实例，会覆盖其他设置 | 渲染后端改走 `AppModel.settings`，或迁到独立 namespace `cp_player_render`。**必须在 P0 修** |
| R3 | **默认值** | 散落 20+ 处字面量 | 集中到 `SettingsDefaults` + `AppSettings` 类型化门面 |
| R4 | **状态副本** | 页面用 `remember { mutableStateOf(AppModel.xxx()) }` 复制持久值，会陈旧 | 从 `AppModel` 的 `StateFlow` 单向读取，写只写 Flow |
| R5 | **端口输入** | 每字符提交 + 重绑 | 草稿 + 校验 + 显式提交 + 占用检测与建议端口 |
| R6 | **接收端地址** | 每字符提交，无校验 | 草稿 + URL 校验 + 显式提交 |
| R7 | **令牌** | 明文写在说明里、不可复制、一键重生成无确认 | 打码 + 显示/复制 + 重生成二次确认 + 后果说明 |
| R8 | **缓存清理** | `errorContainer` 破坏性配色，无大小 | 中性配色 + 显示占用 + 无占用时禁用 + 结果反馈 |
| R9 | **`play_immediately`** | 无消费点，纯装饰 | 接线到歌曲点击处理，或删除 UI 保留 key（见 §10 决策点 D1） |
| R10 | **`allow_remote_control`** | 明确无效的开关 | 端点实现前不显示 |
| R11 | **睡眠定时** | 设置页 2 个控件 + 播放页 1 个对话框，语义重叠 | 设置页只留一个入口行，打开播放页**同一个**对话框（单一事实源） |
| R12 | **服务器状态 / 推送结果** | 混在 `SettingsNote` 里 | 提升为独立状态行 + `liveRegion` |
| R13 | **保存失败** | `catch (_: Exception)` 静默吞掉 | 向上返回结果，UI 显示失败（至少 Snackbar） |
| R14 | **死页面** | `SettingsDetailScreen.kt`（257 行）不可达 | 删除 |
| R15 | **`SettingsCategoryRail`** | 硬编码 4 标签、永远高亮 index 0、映射错误 | 删除（两栏布局不需要它） |
| R16 | **曲库快捷卡片** | 直接 push 各设置子页，绕过根页 | 统一 push `SettingsScreen`，或保留直达但保证返回栈一致（见 §10 决策点 D3） |

---

## 9. 组件与代码结构改造

### 9.1 新增/改造的组件（`SettingsKit.kt`）

| 组件 | 作用 |
|------|------|
| `SettingsScaffold(title)` | 唯一的页面壳，按 `LocalIsExpanded` / embedded 决定顶栏与返回 |
| `SettingsSegmentedItem(...)` | 2–4 项的选项行（替代「下拉 + 底部弹层」） |
| `SettingsTextInputItem(...)` | 草稿 + 校验 + 显式提交 + 未保存徽标（端口/URL 用） |
| `SettingsConfirmActionItem(...)` | 破坏性动作行 + 内置二次确认对话框 |
| `SettingsStatusRow(...)` | 状态行（含 `liveRegion`，区分 info / warning / error） |
| `SettingsNote(emphasis)` | 说明文字，带 `INFO / WARNING / ERROR` 三档语义与配色 |
| `SettingsFoldoutItem(...)` | 折叠区（令牌、接口地址等高级信息） |

### 9.2 注册表驱动

新增 `SettingsRegistry.kt`：**一份声明式的设置项清单**，同时驱动
① 根列表渲染、② 搜索索引、③ 桌面左栏、④ `SettingsDetail → Screen` 映射。

```kotlin
data class SettingsEntry(
    val id: String,               // 稳定 id，用于高亮/搜索/深链
    val group: SettingsGroup,     // 通用 / 账号与音源 / 连接与集成 / 其他
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val accent: SettingsAccent,
    val keywords: List<String>,   // 搜索同义词
    val platform: PlatformScope,  // ALL / DESKTOP_ONLY
    val destination: SettingsDestination,
)
```

好处：现在 `SettingsDetail` 枚举 + `settingsEntries()` 列表 + `toScreen()` 映射 +
`DesktopSettingsDetail()` 映射 **四份平行结构**（改一处要同步四处，已经漂移），
收敛成一份。同时**用一个单测**保证「每个 entry 的 destination 都能解析到 Screen、id 唯一」。

### 9.3 文件级改动

| 文件 | 动作 |
|------|------|
| `SettingsScreen.kt` | 重写：注册表驱动 + 4 组分组 + 删除 `SettingsCategoryRail` + 删除三栏分支 |
| `SettingsSubScreens.kt` | 拆分：`AppearanceSettingsScreen` 保留；`UiLogicSettingsScreen` 删除；`StorageSettingsScreen` 改造；`SponsorScreen` 删除（并入 About） |
| `PlaybackSettingsScreen.kt` | 改造：接收 `play_immediately`；睡眠定时改为入口行；删除 `PLAY_IMMEDIATELY_KEY` 的本地定义（移入 AppModel） |
| `LocalServerSettingsScreen.kt` | 拆为 `StreamOutputSettingsScreen.kt` + `IntegrationSettingsScreen.kt` |
| `ProviderIsolationScreen.kt` | 删除 |
| `SettingsDetailScreen.kt` | 删除 |
| `SettingsKit.kt` | 扩展：新增 7 个组件 + 全量 a11y 语义 |
| `SettingsRegistry.kt` | 新增 |
| `AppModel.kt` | `settings` 改单例；新增类型化读写；`SettingsDefaults` 接入 |
| `DesktopRenderTuning.kt` | 改走共享实例或独立 namespace（R2） |
| `UiFoundation.kt` | 新增 `CpBreakpoints`、`CpSpacing.formMaxWidth` |
| `MainScreen.kt` | 断点改用 `CpBreakpoints` |
| `AboutScreen.kt` | 并入赞助入口 |
| `AccountScreen.kt` | 新增隔离开关 + 危险区 |
| `LibraryScreen.kt` | 快捷卡片改指设置根页（R16） |

---

## 10. 分期实施计划

> ⚠️ **当前工作区有大量并行会话的在途改动**（`SettingsScreen.kt` / `SettingsSubScreens.kt` /
> `PlaybackSettingsScreen.kt` / `LocalServerSettingsScreen.kt` / `MainScreen.kt` 均为已修改未提交）。
> **本方案的实施必须在一个干净分支上、或等这些在途工作提交后进行**，
> 否则会与并行会话互相覆盖（本仓库已经因此踩过坑）。
> 实施前先 `git stash -u` 或 `git commit` 收拢在途工作。

| 阶段 | 内容 | 构建风险 | 可独立验收 |
|------|------|----------|------------|
| **P0 · 正确性** | R1 设置单例、**R2 数据丢失 bug**、R5 端口草稿提交、R6 URL 草稿提交、R7 令牌二次确认、R10 隐藏无效开关、R13 保存失败上报、R14 删死页面 | 低 | ✅ 行为可测 |
| **P1 · 信息架构** | 4 组分组、注册表、删 `SettingsCategoryRail`、删「交互逻辑」、删「音源隔离」、拆「本地服务器」、合并「赞助」、术语与文案修订 | 中 | ✅ 结构可验 |
| **P2 · 组件与交互** | `SettingsScaffold` 统一壳、分段控件、状态行、折叠区、缓存占用显示、睡眠定时单一入口、R4 状态单一来源 | 中 | ✅ |
| **P3 · 响应式与 a11y** | `CpSpacing.formMaxWidth` 收口、断点常量、A1–A12 全量语义、大字体适配 | 中 | ✅ 离屏出图核对 |
| **P4 · 增强** | 设置搜索、分组折叠、恢复默认（分组 / 全局）、关键词索引 | 低 | ✅ |

**P0 必须先做**：R2 是会丢用户设置的真 bug，且它就在设置页的入口上。

---

## 11. 验收标准

1. **构建**：`:core:compileKotlinDesktop` 与 `:app:compileKotlinDesktop` 均 0 error。
   ⚠️ `commonMain` 是整个源集一起编译，报错列表里**第一个**文件才是凶手。
2. **单测**：新增 `SettingsRegistryTest`（id 唯一、destination 全部可解析、platform 门控正确）；
   `SettingsDefaultsTest`（默认值快照，防止无意改动）。
3. **持久化**：改完任一设置后重启，值保持；**并额外验证 R2 的回归**——
   依次修改「主题模式」→「渲染后端 Vsync」→ 重启，确认主题模式**没有**被回退。
4. **输入类**：在端口框输入 `8080` 的全过程中，`~/.cpplayer/cp_player_prefs.properties`
   的 mtime **不应变化**（证明没有逐字符写盘）。
5. **a11y**：TalkBack / VoiceOver 下每个设置项**只念一次**；分组标题可跳转；
   状态变化有播报。
6. **响应式**：Compact / Medium / Expanded 三档 + 100% / 200% 字号，离屏出图逐一核对，
   无截断、无横向溢出、无空态占位。
7. **零功能损失**：现有 16 个持久化 key 全部仍有可达的 UI 入口（或明确标注为已废弃）。
8. **清理**：实施完成后删除脚手架目录与 init script（`build-verify*/`、`.gradle-verify*/`），
   否则会永久跳过被 exclude 的测试。

---

## 12. 待确认决策点

| # | 决策点 | 选项 | 建议 |
|---|--------|------|------|
| **D1** | `play_immediately`（当前无消费点） | ① 接线到歌曲点击处理，实现「点击只入队」；② 从 UI 删除，保留 key 兼容 | **②** —— 「点击只入队」不是本产品的既有行为，为它新增一条交互路径属于新增功能，超出「保持核心功能不变」的范围。若要保留开关，必须先实现行为。 |
| **D2** | 默认音质 `exhigh` | ① 保持 `exhigh`；② 降为 `standard` | **①**，但把它写进 `SettingsDefaults` 显式声明为产品决策。降级会改变既有用户的主观音质感受，属于行为变更。 |
| **D3** | 曲库快捷卡片是否保留直达设置子页 | ① 保留直达；② 统一改为进设置根页 | **① 保留直达**（少点两次），但需保证返回栈一致（从子页返回回到曲库，而不是设置根页）。 |
| **D4** | 「高级」组是否需要显式解锁 | ① 始终可见；② 需要连续点击版本号解锁 | **①** —— 本项目面向开发者，隐藏会增加排查成本；「高级」分组标签已经足够。 |
| **D5** | 桌面渲染后端是否迁到独立 namespace | ① 迁到 `cp_player_render`；② 共用 `AppModel.settings` 单例 | **②** —— 单例化后共用是安全的，且少一个文件。若后续渲染后端要独立读写，再迁 namespace。 |

---

## 附：设置项全量清单（目标状态）

| 分组 | 页面 | 设置项 | key | 默认值 | 控件 | 提交档 |
|------|------|--------|-----|--------|------|--------|
| 通用 | 外观与主题 | 主题模式 | `theme_mode` | SYSTEM | 分段 | A |
| 通用 | 外观与主题 | 取色来源 | `color_source` | FIXED | 分段 | A |
| 通用 | 外观与主题 | 纯黑模式 | `pure_black` | false | 开关 | A |
| 通用 | 播放与音质 | 默认音质 | `playback_quality` | exhigh | 下拉 | A |
| 通用 | 播放与音质 | 立即播放 | `play_immediately` | 待定 | 开关 | A |
| 通用 | 播放与音质 | 定时关闭 | （运行时） | 关闭 | 入口行 | A |
| 通用 | 下载与存储 | 下载目录 | `download_root_dir` | 平台默认 | 入口行 | B |
| 通用 | 下载与存储 | 清理图片缓存 | （动作） | — | 按钮行 | C |
| 账号与音源 | 账号与登录 | 切换音源时刷新资料 | `isolation_switch_account` | true | 开关 | A |
| 账号与音源 | 账号与登录 | 清除登录数据 | （动作） | — | 危险行 | C |
| 连接与集成 | 本地流输出 | 启用 | `local_server_enabled` | false | 开关 | A |
| 连接与集成 | 本地流输出 | 音频输出 | `local_server_output_mode` | SPEAKER | 分段 | A |
| 连接与集成 | 本地流输出 | 流输出端口 | `local_server_stream_port` | 8080 | 文本 | B |
| 连接与集成 | 本地流输出 | 绑定范围 | `local_server_bind` | 127.0.0.1 | 分段 | A |
| 连接与集成 | 本地流输出 | 访问令牌 | `local_server_token` | 自动生成 | 折叠区 | C |
| 连接与集成 | 外部推送与集成 | 接收端地址 | `external_push_receiver_url` | 127.0.0.1:8420 | 文本 | B |
| 连接与集成 | 外部推送与集成 | 自动推送 | `external_push_enabled` | **false**（改） | 开关 | A |
| 连接与集成 | 外部推送与集成 | 开放媒体面 | `local_server_expose_stream` | **false**（改） | 开关 | A |
| 连接与集成 | 外部推送与集成 | 开放数据面 | `local_server_expose_data_api` | false | 开关 | A |
| 连接与集成 | 外部推送与集成 | 远程播控 | `local_server_allow_remote_control` | false | **暂不显示** | — |
| 其他 | 高级 | 渲染后端 | `desktop_render_api` | AUTO | 下拉 | A（需重启） |
| 其他 | 高级 | 垂直同步 | `desktop_vsync_override` | 不干预 | 下拉 | A（需重启） |
| 其他 | 关于与支持 | （无设置项） | — | — | — | — |
| — | 非设置（保持现状） | 搜索历史 | `search_history` | 空 | 搜索页 | — |
| — | 非设置（保持现状） | 最近播放 | `recent_tracks` | 空 | 自动 | — |
| — | 非设置（保持现状） | 已保存账号 | `saved_accounts_*` | — | 账号页 | — |
| — | 非设置（保持现状） | 当前账号 | `active_account_*` | — | 账号页 | — |
| — | 非设置（保持现状） | 登录 Cookie | `cookie_*` | — | 账号页 | — |
| — | 非设置（保持现状） | 上次音源 | `last_active_provider_id` | — | 自动 | — |
| — | 非设置（保持现状） | 引导已完成 | `onboarding_done` | false | 引导页 | — |
| — | 非设置（保持现状） | 窗口尺寸 | `desktop.window.size` | 平台默认 | 自动（独立 namespace） | — |
