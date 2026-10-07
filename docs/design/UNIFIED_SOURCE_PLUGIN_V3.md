# 统一来源系统 —— v3 音源 API × 内置歌词源插件融合方案

> **状态：** 方案（v1，未落地代码）
> **日期：** 2026-10-07
> **范围：** `core`（provider / lyricsplugin / playback / api）+ `app`（设置页 / 播放页）
>
> 本文只回答一个问题：**怎么把「旧歌词管线（AMLL）」与「音源插件系统」收敛成同一套
> 可扩展、可排序、用户可感知的来源体系**，并顺带把音源 manifest 推进到 v3。

---

## 0. 结论摘要

| 项 | 结论 | 依据 |
|---|---|---|
| 目录结构现状 | **三套并存**：内容源（Provider）/ JS 插件（Lyrico）/ 旧管线（AMLL） | §1 |
| 能否直接融合 | **可以**，三者已经共享同一个出口 `SyncedLyricLine` | §1.2 |
| 最大的结构缺口 | **音源 manifest 没有 `apiVersion` / `capabilities`** | §1.3 |
| 最大的体验缺口 | 歌词来源是**「模式下拉 + 插件开关」两个独立控件**，无法表达优先级 | §2.1 |
| v3 的实质 | 不是给 API 加字段，而是**把「来源」提升为一等公民**（有序、可启用、可组合） | §3 |
| 破坏性风险 | 音源 manifest 从「无 apiVersion」到「有 apiVersion」必须**向后兼容老模块** | §6.2 |

---

## 1. 现状盘点（已逐文件核实）

### 1.1 三条链路

```
① 旧歌词管线（AMLL / 官方词库）
   AmllTtmlClient ──> TtmlParser.parse ──┐
   SidecarLyrics（本地 .lrc/.ttml/.elrc）─┤
   LyricsParser（音源 lyric/new）────────┼──> List<SyncedLyricLine>
   LyricsPluginJsonParser（JS 插件）─────┘

② 歌词源插件（Lyrico Plugin API，Rhino 执行）
   LyricsPluginService（commonMain 契约）
     └─ LyricoLyricsPluginService（jvmMain）
          ├─ FileLyricsPluginStore（dataDir/lyrics-plugins/）
          └─ RhinoPluginRuntime + JvmPluginHostApi（Platform.* 宿主面）

③ 音源 Provider（HTTP / Binary / JNI）
   BackendProvider + ModuleManifest + ModuleManager（~/.cpplayer/modules/）
     └─ ProviderManager（apiMap 映射）──> MusicApiService（全应用的数据入口）
```

### 1.2 已经存在的融合基础（**不要重造**）

- **统一出口已存在**：所有解析器都产出 `List<SyncedLyricLine>`
  （`core/src/commonMain/kotlin/cp/player/core/playback/SyncedLyricLine.kt`）。
  任何新来源**只要产出 TTML / LRC / 增强 LRC 三种文本之一**，就能复用现有解析器接入。
- **回退链已集中**：`PlaybackControllerImpl.fetchLyricsFor()`
  （`core/.../playback/PlaybackControllerImpl.kt` L1055-1088）是唯一取词入口，
  顺序为 **边车 → AMLL → 音源 API → 插件**，插入点非常干净。
- **契约分层已有先例**：`LyricsPluginService` 接口在 commonMain、实现走
  `expect fun createLyricsPluginService(...)` —— 与 `LocalMediaSource` / `BackendProvider`
  同一套模式，扩展时照抄即可。
- **providerId 映射已有先例**：`amllPlatformFor(providerId)`（`api/AmllTtmlClient.kt` L50-59）
  已示范「受控音源标识 → 外部平台参数」的映射写法。

### 1.3 关键结构差异（**这是 v3 要动的地方**）

| 维度 | 音源 Provider | 歌词源插件 | 结论 |
|---|---|---|---|
| manifest 版本协商 | ❌ **无 `apiVersion`** | ✅ `apiVersion` / `minHostApiVersion` | 音源需补齐 |
| 能力声明 | `apiMap` 里的 `"unsupported"` 标记 | ✅ `capabilities: Set<PluginCapability>` | 机制重复，需统一 |
| 安装目录 | `~/.cpplayer/modules/` | `dataDir/lyrics-plugins/` | 可统一为一个 `plugins/` 根 |
| 持久化 | 各模块 own state | 单个 `state.json`（启用 + 配置） | 插件模式更值得推广 |
| 执行体 | 进程/so/http | Rhino JS | 不统一（各有必要） |
| 用户可见入口 | `providers`（音源管理页） | `lyrics_plugins`（歌词源插件页） | 同组不同页，可并列 |

### 1.4 当前体验的真实问题

```
设置 → 播放与音质 → 歌词来源：[仅音源 API ▾]    ← 控件 A：模式枚举
设置 → 账号与音源 → 歌词源插件：[开关][开关]      ← 控件 B：各插件独立开关
```

- **两个控件无法组合表达意图**：用户想「AMLL 优先，失败后用插件 X，再失败用插件 Y」，
  当前 UI **没有任何办法表达**（模式是枚举，插件只有布尔量，顺序由 `loadSources()` 的目录名决定）。
- **插件顺序不可控**：`fetchLyrics` 遍历顺序 = `store.loadSources()` 的 **目录名字典序**
  （`LyricoLyricsPluginService.kt` L96），用户看不见、改不了。
- **`providerId` 没有透传给插件**：`fetchFromPlugins`（L1096-1114）只传 `title/artist/album`，
  插件无法做「精确 ID 取词」，只能模糊搜索 —— 命中率和误配都比 AMLL 那条路差。
- **来源不可见**：`lyricsInfo.source` 只在「更多菜单」里显示一行，播放页看不到当前这句词来自哪。

---

## 2. 设计目标

1. **来源是一等公民**：AMLL、边车、音源 API、每个插件，都成为同一个 `LyricsSource`
   模型的一个实例 —— 有 id、有名称、有能力、有**顺序**、有启用状态、有配置。
2. **单一事实源**：删掉「模式枚举」与「插件开关」两个独立控件，合并成一个
   **可拖拽排序的来源列表**。用户能一眼看到「这首歌的歌词会按什么顺序去找」。
3. **音源 manifest 补 v3 语义**：新增 `apiVersion` + `capabilities`，且**老模块继续能加载**。
4. **内置歌词源插件**：AMLL / 边车 / 音源 API 三条路径**降级为内置插件条目**，
   与第三方插件走**完全相同的取词接口**（区别只有 `bundled = true` 且不可删）。
5. **零破坏迁移**：老配置文件、老插件包、老音源模块都不需要用户手动改。

---

## 3. 目标模型

### 3.1 `SourceKind` —— 三种来源角色

```kotlin
enum class SourceKind { PROVIDER, LYRICS, LOCAL }
```

| Kind | 语义 | 主要能力 | 现有实现 |
|---|---|---|---|
| `PROVIDER` | 内容源：搜歌、取流、取详情 | `search` / `songUrl` / `trackDetail` / `lyrics` | `BackendProvider`（http/binary/jni） |
| `LYRICS` | 歌词源，**可含内置** | `searchSongs` / `getLyrics` | 内置 3 条 + Lyrico JS 插件 |
| `LOCAL` | 本地文件 / 边车 | `sidecarLyrics` | `SidecarLyrics` |

> **关键决策**：`LYRICS` 来源**不再只由 JS 插件构成**。
> AMLL、边车歌词、音源自带歌词都被注册为内置 `LyricsSource`，
> 只是它们的「执行体」是 Kotlin 函数而不是 Rhino 脚本。

### 3.2 `LyricsSource` —— 统一歌词源

```kotlin
/** 一条歌词来源（内置或第三方，一视同仁）。 */
interface LyricsSource {
    val id: String                 // "builtin.amll" / "builtin.sidecar" / "builtin.provider" / 插件 id
    val name: String
    val bundled: Boolean           // true = 随宿主分发，不可删
    val capabilities: Set<LyricsCapability>
    /** 取词：拿到统一模型，或 null 表示未命中（交给下一条来源）。 */
    suspend fun fetch(request: LyricsRequest): LyricsSourceResult?
}

data class LyricsRequest(
    val mediaId: CPMediaId?,       // ⚠️ 关键新增：把 providerId/resourceId 透传给所有来源
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val isLocal: Boolean,
)
```

**内置来源（`bundled = true`）三条**：

| id | 名称 | 对应现有代码 | 备注 |
|---|---|---|---|
| `builtin.sidecar` | 本地边车歌词 | `SidecarLyrics.load()` | 仅本地曲命中 |
| `builtin.amll` | AMLL 官方词库 | `AmllTtmlClient` + `TtmlParser` | 需要 `providerId` 精确取词 |
| `builtin.provider` | 音源自带歌词 | `api.getLyric()` + `LyricsParser` | 依赖当前音源 |

> 「音源 API」作为**内置歌词源**而不是 Provider 的能力 —— 这是本次融合最核心的一步：
> 它把「模式枚举」里那两档（`PROVIDER_ONLY` / `AMLL_FIRST`）自然表达成
> **列表顺序**（把 `builtin.amll` 拖到最前 = AMLL 优先；把它关掉 = 仅音源）。

### 3.3 `LyricsSourceRegistry` —— 排序与启用

```kotlin
interface LyricsSourceRegistry {
    /** 按用户顺序返回已启用来源（内置 + 第三方统一排序）。 */
    suspend fun orderedSources(): List<LyricsSource>
    suspend fun setOrder(orderedIds: List<String>)
    suspend fun setEnabled(id: String, enabled: Boolean)
    /** 新增/变化后重新扫描（导入插件、换音源时）。 */
    suspend fun reload()
}
```

**持久化（单一事实源）**：一个 `sources.json`，替代现在的
`lyrics_source_mode` + `state.json.enabledIds` 两处：

```json
{
  "version": 3,
  "order": ["builtin.sidecar", "builtin.amll", "lunabeat-ttml-hub", "builtin.provider"],
  "disabled": ["builtin.provider"],
  "configs": { "<pluginId>": { "<key>": "<value>" } }
}
```

> ⚠️ **迁移规则（必须）**：读不到 `sources.json` 时，从旧值合成初始顺序 ——
> `lyrics_source_mode = amll_first` → `[sidecar, amll, provider]`；
> `provider_only` → `[sidecar, provider]`；`amll_only` → `[sidecar, amll]`；
> 旧 `state.json.enabledIds` 里**未列出的插件**保持停用。迁移只做一次，不回写旧键。

---

## 4. 取词主流程（改造后）

```
PlaybackControllerImpl.refreshLyrics()
  └─ LyricsEngine.resolve(request)                 ← 新：取代 fetchLyricsFor 的手写分支
       for (source in registry.orderedSources()) {  // 用户排序，内置与插件混排
           val result = source.fetch(request)       // null = 未命中，继续下一条
           if (result != null) return result        // 首个命中即胜出
       }
       return NoLyrics
```

**与现有 `fetchLyricsFor` 的差异**：

| | 现状 | 改造后 |
|---|---|---|
| 顺序 | 硬编码 边车→AMLL→音源→插件 | 用户可排序列表 |
| 模式 | `LyricsSourceMode` 三分支 + 特判 | 无模式，顺序即语义 |
| 插件参数 | 只有 title/artist/album | 透传 `CPMediaId`（精确 ID 取词） |
| 命中记录 | `lyricsInfo.source` 字符串 | 结构化的「是谁命中的」+ 候选来源列表 |

> ⚠️ **保留现有语义**：`AMLL_ONLY` 模式下插件仍可兜底这一行为，改造后表达为
> 「把插件拖到最前并关掉其余来源」，语义等价且更直观 —— 但**迁移映射要写清**（§3.3）。

---

## 5. 音源 manifest v3

### 5.1 新增字段（全部可选，向后兼容）

```jsonc
// manifest.json
{
  "id": "my-provider",
  "name": "My Provider",
  "version": "2.1.0",          // 语义化版本，保留
  "type": "http",               // 保留

  // ⭐ v3 新增
  "apiVersion": 3,              // 不写 = 按 v1 老模块处理（无协商）
  "minHostApiVersion": 3,       // 宿主低于此值则拒绝加载并给出可读原因
  "capabilities": [             // 取代 apiMap 里散落的 "unsupported"
    "search", "songUrl", "trackDetail", "lyrics",
    "playlist", "album", "artist", "comment"
  ],

  "apiMap": { "song/url/v1": "/song/url/v1" }
}
```

### 5.2 版本语义

| apiVersion | 含义 | 宿主行为 |
|---|---|---|
| 缺省 | v1 老模块：只有 `apiMap` | 全部能力假定可用（现状行为），加载时**不**报兼容警告 |
| `2` | 引入 `capabilities` | 宿主按声明裁剪功能入口 |
| `3` | 引入 `minHostApiVersion` + `LyricsCapability` 对齐 + 统一来源模型 | 宿主校验区间 |

> ⚠️ **不要**把「加字段」当成 v3 的全部。v3 真正的新增是
> **音源也能声明「提供哪些歌词能力」**，从而进入 §3 的统一来源列表。

### 5.3 `capabilities` 与 `apiMap."unsupported"` 的关系

- 现有 `apiMap` 里的 `"unsupported"` **继续生效**（老模块不能坏）；
- 新模块推荐用 `capabilities` 白名单表达；
- 两者冲突时**以更严格者为准**（声明了 capabilities 就只认它，忽略 apiMap 的 unsupported 猜测）；
- 缺失 capabilities 且无 apiMap 标记 = 按「全部可用」处理（等价 v1）。

---

## 6. UI 设计

### 6.1 新的「歌词来源」页（取代两处旧控件）

```
设置 → 账号与音源 → 歌词来源                       [Extension]
┌──────────────────────────────────────────────────┐
│ ⓘ 歌词按下面的顺序查找，第一个命中的来源生效。      │
│   拖动手柄调整顺序；关闭的来源不参与查找。          │
├──────────────────────────────────────────────────┤
│ ≡  本地边车歌词        [内置]        [开关]        │
│    同目录 .lrc / .ttml / .elrc                    │
├──────────────────────────────────────────────────┤
│ ≡  AMLL 官方词库       [内置]        [开关]        │
│    逐字 TTML · api.amll.dev                       │
├──────────────────────────────────────────────────┤
│ ≡  LunaBeat TTML Hub  作者 · v1.2 · 获取歌词  [开关]│
│    （第三方插件，可展开配置、可删除）               │
├──────────────────────────────────────────────────┤
│ ≡  音源自带歌词        [内置]         [关]         │
│    网易云 lyric/new · 逐字 YRC                    │
├──────────────────────────────────────────────────┤
│ [ 导入歌词源插件（zip） ]                          │
└──────────────────────────────────────────────────┘
```

**要点**：
- **拖拽排序**是核心交互（桌面用鼠标，Android 用长按拖动）；
- **`内置` 徽章**复用现有 `LyricsPluginStrings.bundledBadge`；
- **停用的来源**在列表里**保留可见**（灰显）而不是移出列表 —— 用户要能看出「我关了什么」；
- 三方插件行可展开：显示作者 / 版本 / 能力 / 配置表单（`PluginConfigField` 已支持，见 §7.2）；
- 导入 zip 入口保留（与现状一致）。

### 6.2 `PlaybackSettingsScreen` 的收尾

- **删掉** `§ sectionLyrics` 里的三档下拉（`LyricsSourceMode`）；
- 该段保留**一行跳转**：「歌词来源 →」指向新页（保持「播放设置」里的可达性，
  避免用户找不到 —— 迁移旧入口，不是删入口）；
- `LyricsSourceMode` 枚举保留为**迁移期只读**（读旧键用），UI 不再暴露。

### 6.3 播放页「当前歌词来源」

- 播放页歌词区角落显示当前命中来源（`sourceName` 一行灰字）；
- 无歌词时该位变为**可点入口**：「未找到歌词 · 换个来源 →」，直接跳 §6.1 页面；
- 依据：`LyricsPluginStrings` 的 KDoc 已预告「将来『播放页无歌词 → 一键换源』也会读同一组」，
  本方案兑现它。

### 6.4 音源管理页同步

`ProviderManagementScreen` 增加**能力标签行**（读 v3 `capabilities`），
让用户在装音源时就知道「这个源给不给歌词、给不给评论」，与歌词来源页的说法一致。

---

## 7. 内置歌词源插件

### 7.1 三条内置来源的实现形态

内置来源**不是** JS 文件，而是宿主内的 Kotlin 实现，但**走完全相同的接口**：

```kotlin
// core/src/commonMain/kotlin/cp/player/core/lyrics/BuiltinLyricsSources.kt
internal class AmllLyricsSource(
    private val client: AmllTtmlClient,
) : LyricsSource {
    override val id = "builtin.amll"
    override val name = "AMLL TTML"
    override val bundled = true
    override val capabilities = setOf(LyricsCapability.GET_LYRICS, LyricsCapability.SEARCH_SONGS)

    override suspend fun fetch(request: LyricsRequest): LyricsSourceResult? {
        val ttml = client.fetchLyricsTtml(
            providerId = request.mediaId?.providerId?.takeIf { !request.isLocal },
            songId = request.mediaId?.resourceId,
            name = request.title, artist = request.artist, album = request.album,
        ) ?: return null
        val lines = TtmlParser.parse(ttml).ifEmpty { return null }
        return LyricsSourceResult(lines = lines, hasWordLevel = lines.any { it.words.isNotEmpty() })
    }
}
```

### 7.2 第三方 JS 插件如何接入

`LyricoLyricsPluginService` **改造成 `LyricsSource` 的适配器**：

- 现在：`fetchLyrics(title, artist, album, durationMs)` —— 一个「全部插件扫一遍」的方法；
- 改造：每个插件暴露为一个 `LyricsSource`，`fetch()` 内部走
  `searchSongs(keyword) → 选时长最接近 → getLyrics`；
- **新增透传**：`LyricsRequest.mediaId` 传入后，若能映射到插件认识的平台 ID
  （复用 `amllPlatformFor` 思路，插件 manifest 可声明 `platformIdField`），
  优先用 ID 精确取词，失败再回退关键词搜索。

> ⚠️ **能力门槛不能丢**：只有声明了 `getLyrics` 的插件才进列表（现状已如此，`LyricoLyricsPluginService` L101），
> 否则用户会看到「打开开关但永远不生效」的插件。

### 7.3 插件配置表单

`PluginConfigField`（`PluginManifest.kt` L52-98）已支持 7 种字段类型 + 分组 + 默认值，
**当前 UI 根本没有渲染它**（`LyricsPluginSettingsScreen` 只做了开关 + 删除）。
本方案在列表行展开区渲染配置表单 —— 这是现存能力的兑现，不是新造。

---

## 8. 目录与持久化统一

```
<dataDir>/
  ├── modules/              # 内容源（Provider，保留现状路径，不动）
  └── lyrics-sources/       # 歌词源（原 lyrics-plugins/ 改名）
       ├── sources.json     # ⭐ 新：顺序 + 启用 + 配置（唯一事实源）
       └── <pluginId>/      # 第三方插件文件
```

> **迁移**：首次启动时若存在旧 `lyrics-plugins/`，原地读 `state.json` 合成 `sources.json`，
> **不删旧目录**（回滚安全）。旧 `state.json` 之后不再写入。

---

## 9. 落地路线（建议分四步，每步可独立验证）

| 步 | 内容 | 验证 |
|---|---|---|
| **S1** | 引入 `LyricsSource` / `LyricsRequest` / `LyricsSourceRegistry` 抽象；三条内置来源包一层，**行为与现状逐字节等价** | `LyricsEngineTest`：用假来源钉死「首个命中即胜出」「全落空→NoLyrics」 |
| **S2** | `sources.json` + 旧键迁移；`LyricsEngine` 取代 `fetchLyricsFor`；删 `LyricsSourceMode` 的 UI 用法 | 迁移测试：三种旧模式各自映射出的顺序正确；旧键缺失时用默认顺序 |
| **S3** | 新「歌词来源」页（拖拽排序 + 开关 + 配置表单 + 导入）；`PlaybackSettingsScreen` 收尾 | 出图（`SettingsI18nPreviewTest` 补新页，中英 × 宽窄）；排序后取词顺序断言 |
| **S4** | 音源 manifest v3（`apiVersion` / `capabilities`）；`ProviderManagementScreen` 能力标签；播放页来源指示 | 老模块加载不回退；新模块能力裁剪生效；`assembleRelease`（R8） |

### 9.1 每步的守卫测试（按仓库约定）

- 测试结论只看 `**/test-results/**/TEST-*.xml` 且 **`skipped="0"`**；
- 改版式**必须离屏出图**（§6 的新页 + 播放页来源行）；
- 文案**中英各一遍**（只测中文会漏译全绿）；
- 带数字/名单的句子写成**文案函数**（`fun x(n: Int): String`），不拼字符串；
- **禁用 `startsWith`/`contains`/`== "中文"` 决定行为** —— 来源命中/失败必须走
  sealed 类型 + `val failed`，不能靠比较文案（`docs/dev/I18N.md` §5.5）。

---

## 10. 风险与取舍

| 风险 | 说明 | 对策 |
|---|---|---|
| **拖拽排序在 Android 上体验差** | 触屏长按拖动误触率高 | 桌面用 DnD，Android 用「上移/下移」按钮（同一模型，两套壳） |
| **老音源模块被 v3 判为不兼容** | 用户升级后发现音源没了 | `apiVersion` 缺省 = v1 **恒可加载**；`minHostApiVersion` 只在显式声明时才校验 |
| **`LyricsSourceMode` 语义丢失** | 用户习惯「三档」心智 | 迁移把三档映射成固定顺序，并在新页顶部说明「顺序即优先」 |
| **插件顺序变化导致取词结果变化** | 用户升级后同样一首歌换了来源 | 首次迁移**保持现有实际顺序**（AMLL 系在前、插件在后），不重新排序 |
| **Rhino 仍是单线程** | 多来源并发检索受限 | `LyricsEngine` 保持顺序求值（首个命中即止），**不做并发** —— 与现状一致，也避免 JS 运行时竞态 |
| **内置来源也走 Rhino 吗** | 若强行统一成 JS 会引入无谓开销 | ❌ 不。内置来源是 Kotlin 实现，只统一**接口**，不统一**执行体** |

---

## 11. 明确不做

- ❌ **不把内置歌词源写成 JS 插件**：Rhino 求值有成本，且内置源要访问 Ktor / 平台 API，
  包成 JS 只会让调试更难。
- ❌ **不做来源并发竞速**（谁快用谁）：结果不确定、流量翻倍，且 Rhino 非线程安全。
- ❌ **不动 `modules/` 的目录结构与 `sha256` 校验**：音源安装链路稳定，只加 manifest 字段。
- ❌ **不引入 compose-resources**：文案继续走自建 `CpStrings` 树（1.12.1 做不到应用内切换）。
- ❌ **不删 `LyricsSourceMode` 枚举**：保留为迁移读取用，避免老配置文件被判脏。

---

## 12. 待用户拍板的三点

1. **顺序模型**：采用「单一有序列表」还是「分组 + 组内有序」（分组 = 本地/在线/插件）？
   —— 本方案取前者（更简单，且能表达任意优先级）。
2. **音源是否也进这个列表**：本方案把「音源自带歌词」做成内置歌词源条目；
   若希望「音源整体」作为一条可排序项（而不是拆出它的歌词能力），结构会不同。
3. **v3 的版本号门面**：`apiVersion: 3` 是否要与 `PROVIDER_DEV_GUIDE.md` 的文档版本
   （当前标 `3.0.0`）对齐命名？—— 注意文档版本号与 manifest 版本号是**两个独立概念**，
   本方案建议 manifest 从 `1` 起算并在指南里写清区分。

---

## 附：涉及的关键文件

| 文件 | 改动 |
|---|---|
| `core/.../lyricsplugin/LyricsPluginService.kt` | 拆出 `LyricsSource` 抽象；保留旧接口做兼容层 |
| `core/.../lyricsplugin/LyricoLyricsPluginService.kt` | 改为 `LyricsSource` 集合的提供者 |
| `core/.../playback/PlaybackControllerImpl.kt` | `fetchLyricsFor` → `LyricsEngine.resolve` |
| `core/.../api/AmllTtmlClient.kt` | 抽出 `AmllLyricsSource`；`LyricsSourceMode` 降级为迁移用 |
| `core/.../provider/ModuleManifest.kt` | 新增 `apiVersion` / `minHostApiVersion` / `capabilities` |
| `core/.../provider/ModuleManager.kt` | 加载时做 v3 版本校验（缺省按 v1） |
| `app/.../ui/screen/LyricsPluginSettingsScreen.kt` | 重写为「歌词来源」页（排序 + 配置） |
| `app/.../ui/screen/PlaybackSettingsScreen.kt` | 三档下拉 → 一行跳转 |
| `app/.../ui/screen/SettingsRegistry.kt` | `lyrics_plugins` 条目改名/改图标语义 |
| `app/.../i18n/LyricsPluginStrings.kt` | 新增排序/来源/配置相关文案（中英同步） |
| `docs/dev/PROVIDER_DEV_GUIDE.md` | 补 §4 的 v3 字段与 §10 兼容性表 |
