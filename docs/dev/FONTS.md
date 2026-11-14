# 字体栈

> 中日拉丁字体如何路由、如何开关、改完怎么验。
> 声明处 `app/src/commonMain/.../ui/theme/FontStack.kt`；加载与下载在 `Font.kt` / `FontPacks.kt`。

## 1. 三款字体

| 优先级 | 字体 | 负责 | 桌面 | Android |
|---|---|---|---|---|
| 1 | Google Sans Flex | 拉丁字母、数字、希腊 / 西里尔、通用符号 | 内置（`composeResources`） | 内置 |
| 2 | Noto Sans JP（可变，wght 100–900） | 日文假名 + 同码位汉字的日式写法 | 内置 | 下载 |
| 3 | MiSans VF（可变，wght 150–700） | 中文、全角标点、其余语言 | 内置 | 下载 |

优先级只写在 `AppFontFace.priority` 一处。

## 2. 这不是 CSS 的字体栈

Compose 没有「按字形回退」的字体栈，一个 `TextStyle` 只能落到一个 typeface。

| 事实 | 后果 |
|---|---|
| `FontListFontFamilyTypefaceAdapter.resolve(TypefaceRequest, …)` 按字重 / 斜体在家族内挑一个 `Font` | `TypefaceRequest` 里没有文本，它不知道「这个字形缺了」 |
| 桌面 `FontSynthesis_desktopKt.synthesizeTypeface` 是空实现（`areturn` 原样返回） | 单字重字体在桌面上连合成粗体都没有 |

所以「优先级」落实成按字符脚本路由：一段文本按脚本切成若干 run，每个 run 套对应字体（`buildAppStyledText`）。等价于 CSS 的 `font-family` 链，但依据是脚本分类而不是字形覆盖。

好处：中日共用码位的汉字（`直` / `骨` / `次`）不会因为「谁先有那个码位」变成对方的写法。

| 字符 | `TextScript` | 落到 |
|---|---|---|
| 假名（含 `ー` `・` 与半角片假名） | `JAPANESE` | Noto Sans JP |
| 汉字、全角标点、全角字母数字 | `CJK` | 整段有假名 → Noto Sans JP；否则 → MiSans |
| ASCII / 拉丁 / 希腊 / 西里尔 / 数字 / 符号 | `LATIN` | 不套 run，用样式默认字体（Google Sans Flex） |
| 其余（韩文 / 泰文 / 阿拉伯文） | `OTHER` | MiSans；它没有的字形再走平台回退 |

「整段有没有假名」是段落级判定（`containsKana`）。逐句判断会让同一行里的汉字一会儿日式一会儿中式。

## 3. 生效范围

逐 run 换字体发生在文本层，不是字阶层：`Typography` 全局共享，分不清内容语言。

| 接入点 | 方式 |
|---|---|
| `CpText` | 全仓统一文本入口，含省略 / 悬停 / 跑马灯治理 |
| `TrackArtistText` | 歌手行，本就是 `AnnotatedString`，逐段套 |
| `LyricContent` | 逐 run：经 `ScriptFontLyricsProfile` 在歌词库内部切成带 `SpanStyle` 的 layout |

裸 `Text(...)` 仍是系统 CJK 字体。想让某处也走 MiSans / Noto，换成 `CpText` 即可获得全部路由。注意 `CpText` 的 `maxLines` 默认是 1，多行文本要显式传。

### 3.1 歌词界面必须走 profile

歌词库 `KaraokeLyricsView` 对外只收一个 `TextStyle`，拿不到 `AnnotatedString`。

| 阶段 | 做法 | 后果 |
|---|---|---|
| 改造前 | `LyricContent` 整行 `copy(fontFamily = Noto Sans JP)` | 一句日语歌词里的 `Tokyo` / `03:45` / `Go!` 全是 Noto 的拉丁字形 |
| 为什么错 | `TextScript.LATIN` 的语义是不套 span、沿用样式默认家族 | 整行换家族把默认值从 Google Sans Flex 改成 Noto，绕过了整张路由表 |

正解是 `app/src/commonMain/.../ui/component/ScriptFontLyricsProfile.kt`。

| 实现点 | 要求 |
|---|---|
| 接口 | `LyricsProfile`；`matches` 只接含汉字或假名的音节（纯拉丁行仍走内置 `LatinProfile`） |
| `groups` / `wrap` / `effects` | 委派给内置 `CjkProfile`，不抄：ICU 词界 + 均衡折行是最复杂的部分，抄了每次升版本都要重对 |
| 自有逻辑 | 只在 `prepare(line, group, measurer, style)`：用 `buildAppStyledText` 生成带 font span 的 `AnnotatedString`，`measure` 成 layout 后交回 `CjkProfile` |
| 为什么 span 能穿透 | `DefaultLyricsProfile.draw` 直接 `drawText(unit.layout)` |
| 分词后的汉字 | 单个 syllable 或 group（如「運命」「君」）常只含汉字无假名；按整首歌或整行是否日语（`isJapaneseLyrics` / `line.syllables.any { it.content.containsKana() }`）路由到 Noto Sans JP，避免误判成 MiSans |
| 量不出 span | 原样委派（如 Android 未下载日文字体），不凭空多量一次 |

普通行（`SyncedLine`，无逐字时间戳）在歌词库内部直接 `Text(style)` 绘制、不走 profile。因此 `LyricContent` 为普通行样式绑定 `appFonts.japanese`（日文歌词）/ `appFonts.misans`（中文歌词）。逐字行测量时会抹去西文字体干扰，保证拉丁字符仍走 Google Sans Flex。

### 3.2 诊断页的例外

`HealthScreen.kt` 整页刻意用裸 `Text`，并且一律不传 `maxLines` / `overflow`。

| 项 | 内容 |
|---|---|
| 原因 | 这一页读原始数据（音源 ID、方法名、错误串、apiMap 路由、诊断键值、原始 JSON），每条都可能超一行 |
| `CpText` 默认档 | 单行 + 尾部省略 + 悬停浮出全文；触屏没有 hover，等于把要排查的信息藏起来 |
| 约束 | `HealthScreenNoTruncationPreviewTest` 用源码扫描钉住（不许出现 `CpText(` / `maxLines`） |
| 代价 | 这一页中日文走平台回退字体；首要目标是读得到，不是观感统一 |

## 4. 开关与平台

每款字体支持独立开关：**设置 → 外观 → 字库**。

| 字体 | 开启 | 关闭 |
|---|---|---|
| Google Sans Flex | 渲染拉丁 / 数字 / 符号，支持圆滑度调节 | 退回系统默认 `Typography` |
| Noto Sans JP | 接管假名与日文汉字 | 退回系统日文字体 |
| MiSans VF | 接管中文、全角标点与其余文字 | 退回系统中文字体 |

桌面三款随安装包内置，开关直接生效。Android 为控制体积，Noto Sans JP 与 MiSans 按需下载。

| 项 | 内容 |
|---|---|
| 入口 | 设置 → 外观 → 字库（`FontPacksSection`，两端共用同一份 UI） |
| 来源 | 本仓库 GitHub Release `fonts-v1` 的两个资源；URL 硬编码在 `AppFontPack` |
| 落地路径 | `filesDir/fonts/<fileName>` |
| 写入 | 先写 `.part` 再 rename |
| 校验 | 落地前校验体积 + 字体魔数（GitHub 出错会返回 HTML 错误页，不校验就会被当成字体） |
| 已下载 | 可自由开关、随时删除；删除后立即回到平台回退字体 |
| 生效时机 | 下载完成后无需重启，主题会重组 |

| 约束 | 内容 |
|---|---|
| 换字体版本 | 新建 `fonts-v2` 并同步改 `AppFontPack` 的 url；不要删改已发布的 `fonts-v1`，老版本 App 下载会 404 |
| Android 版本 | 需 API 26+（`Typeface.Builder` 才能按 `wght` 实例化可变字体）；24 / 25 功能不可用，设置页明说，文本走系统字体 |

## 5. 验证

`app/src/desktopTest/.../preview/FontStackPreviewTest.kt` 是唯一能证明路由生效的测试，编译与普通单测量不到字形。

| 断言 | 判据 |
|---|---|
| 纯拉丁 | `Text` 与 `CpText` 渲染逐字节相同 ⇒ 拉丁没被换走 |
| 中文 | 两者必须不同 ⇒ MiSans 生效 |
| 日文 vs 中文 | 必须不同 |

同时出对照图 `app/build-fonts/preview/font-stack-board.png`，肉眼核对四行文本。

第二条曾抓出「只改了一个分支」的 bug：`CpText` 的悬停分支换了 `styled`，普通分支还在用 `shown`。

歌词逐 run 路由由 `app/src/desktopTest/.../preview/ScriptFontRoutingPixelTest.kt` 钉住。

| 断言 | 判据 |
|---|---|
| 日英混排（`Tokyo の夜 03:45 Go!`） | 「整行钉日文字体」与「逐 run 路由」必须不同 ⇒ 拉丁真换回 Google Sans Flex |
| 纯拉丁 | 套 span 与不套 span 逐字节相同 ⇒ `TextScript.LATIN` 没偷偷替换 |
| 对照图 | `app/build-scriptfont2/routing/routing-whole-noto.png`、`routing-per-run.png` |
| 环境 | 桌面端日文字体确实加载，否则前几条会在字体缺位时静默失效 |

量这条不能走 `LyricContent` 的离屏出图：它经过歌词库的滚动 / reveal / 行时钟，离屏单帧下当前行可能不显示，会把「渲染路径没跑起来」误判成「字体没生效」。直接分两次 `measure` + `drawText(layout)` 比像素，那正是 `DefaultLyricsProfile.draw` 做的事。

## 相关

- UI 规范：[CONTRIBUTING 第 3 节](../../CONTRIBUTING.md#3-compose--ui)
- 字库设置在设置页的归属：[USER_GUIDE 第 3 节](../USER_GUIDE.md#3-功能与入口)
