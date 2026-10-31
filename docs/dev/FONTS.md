# 字体栈（中文 / 日文 / 拉丁）

> 想改「某个字用什么字体」先读这一页。代码里的唯一声明处是
> `app/src/commonMain/.../ui/theme/FontStack.kt`，加载与下载在 `Font.kt` / `FontPacks.kt`。

## 1. 声明：三款字体，一条优先级

| 优先级 | 字体 | 负责 | 桌面端 | Android |
|---|---|---|---|---|
| 1 | **Google Sans Flex** | 拉丁字母、数字、希腊 / 西里尔、通用符号 | 内置（`composeResources`） | 内置 |
| 2 | **Noto Sans JP**（可变字体，wght 100–900） | 日文：假名 + 同码位汉字的**日式**写法 | 内置 | 下载 |
| 3 | **MiSans VF**（可变字体，wght 150–700） | 中文、全角标点、其余语言 | 内置 | 下载 |

优先级只写在 `AppFontFace.priority` 一处，别在别处再抄一遍。

## 2. ⚠️ 这不是 CSS 的 `font-family` 字体栈

**Compose 没有「按字形回退」的字体栈。** 一个 `TextStyle` 只能落到一个 typeface：

- `FontListFontFamilyTypefaceAdapter.resolve(TypefaceRequest, …)` 按字重 / 斜体在家族内挑**一个** `Font`
  返回，`TypefaceRequest` 里根本没有文本 —— 它没能力知道「这个字形缺了」；
- 桌面端 `FontSynthesis_desktopKt.synthesizeTypeface` 是**空实现**（`areturn` 原样返回），
  所以单字重字体在桌面上连合成粗体都没有。

所以「优先级」在这里落实成**按字符脚本路由**：一段文本按脚本切成若干 run，
每个 run 套对应字体（`buildAppStyledText`），等价于 CSS 那条 `font-family` 链，
但依据是脚本分类而不是字形覆盖。好处是中日共用码位的汉字（`直` / `骨` / `次`）
不会因为「谁先有那个码位」就变成对方的写法。

| 字符 | `TextScript` | 落到 |
|---|---|---|
| 假名（含 `ー` `・` 与半角片假名） | `JAPANESE` | Noto Sans JP |
| 汉字、全角标点、全角字母数字 | `CJK` | 整段有假名 → Noto Sans JP；否则 → MiSans |
| ASCII / 拉丁 / 希腊 / 西里尔 / 数字 / 符号 | `LATIN` | 不套 run，用样式的默认字体（Google Sans Flex） |
| 其余（韩文 / 泰文 / 阿拉伯文 …） | `OTHER` | MiSans（它没有的字形再走平台回退） |

「整段有没有假名」是**段落级**判定（`containsKana`）。逐句判断会让同一行里的汉字
一会儿日式一会儿中式。

## 3. 生效范围（目前的边界）

逐 run 换字体发生在**文本层**，不是字阶层 —— `Typography` 是全局共享的，它分不清内容语言。

已有接入点：

- `CpText`（全仓统一文本入口，含省略 / 悬停 / 跑马灯治理）
- `TrackArtistText`（歌手行，本来就是 `AnnotatedString`，逐段套）
- `LyricContent`（**逐 run**：经 `ScriptFontLyricsProfile` 在歌词库内部切成带 `SpanStyle` 的 layout）

### 歌词界面：为什么必须走 profile

歌词库 `KaraokeLyricsView` 对外只收一个 `TextStyle`，拿不到 `AnnotatedString`。
**改造前** `LyricContent` 因此整行 `copy(fontFamily = Noto Sans JP)` —— 后果是
一句日语歌词里的 `Tokyo` / `03:45` / `Go!` 全是 Noto 的拉丁字形。
`TextScript.LATIN` 的语义是「**不套 span**，沿用样式的默认家族」，整行换家族把那个默认值
从 Google Sans Flex 改成了 Noto，等于绕过了整张路由表。

正解是 `app/src/commonMain/.../ui/component/ScriptFontLyricsProfile.kt`：

- 实现 `LyricsProfile`，`matches` 只接**含汉字或假名**的音节（纯拉丁行仍走内置 `LatinProfile`）；
- `groups` / `wrap` / `effects` **委派给内置 `CjkProfile`**，别抄一份（ICU 词界 + 均衡折行
  是库里最复杂的部分，抄了以后每次升版本都要重新对）；
- 唯一的自有逻辑在 `prepare(line, group, measurer, style)`：用 `buildAppStyledText` 生成带
  font span 的 `AnnotatedString`，`measure` 成 layout 后再交回 `CjkProfile`。
  `DefaultLyricsProfile.draw` 是直接 `drawText(unit.layout)` 的，所以 span 能原样穿透。
- 量不出任何 span（如 Android 未下载日文字体）时**原样委派**，不凭空多量一次。

`LyricContent` 那边因此**不再换 fontFamily**，`forLyricLanguage` 只保留
`lang=ja` + `palt`（`withJapaneseTypesetting`）。

⚠️ **裸 `Text(...)` 仍是系统 CJK 字体**。想让某处也走 MiSans、Noto，把它换成 `CpText`
即可获得全部字体路由（注意 `CpText` 的 `maxLines` 默认是 1，多行文本要显式传）。

⚠️ **例外：诊断 / 健康页（`HealthScreen.kt`）整页刻意用裸 `Text`**，并且**一律不传**
`maxLines` / `overflow`。这一页读的是原始数据（音源 ID、方法名、错误串、apiMap 路由、
底层诊断键值、原始 JSON），每一条都可能超过一行；`CpText` 的默认档是单行 + 尾部省略号 +
悬停浮出全文，触屏没有 hover，等于把要排查的信息藏起来。
`HealthScreenNoTruncationPreviewTest` 用源码扫描钉住这两条（不许出现 `CpText(` / `maxLines`），
代价是这一页的中日文走平台回退字体 —— 诊断页的首要目标是「读得到」，不是观感统一。

## 4. 独立开关与平台支持（桌面内置 vs Android 下载）

每款字体均支持**独立单独开关**（设置 → 外观 → **字库**）：
- **Google Sans Flex**：开启时渲染拉丁 / 数字 / 符号并支持圆滑度调节；关闭时退回到系统默认 Typography。
- **Noto Sans JP**：开启时接管假名与日文汉字；关闭时日文文字退回到系统日文字体。
- **MiSans VF**：开启时接管中文、全角标点与其余文字；关闭时退回到系统中文字体。

桌面端三款字体随安装包内置，开关直接生效；Android 端 Noto Sans JP 与 MiSans 为控制安装包体积改为按需下载：

- 入口：设置 → 外观 → **字库**（`FontPacksSection`，两端共用同一份 UI）
- 来源：本仓库的 GitHub Release **`fonts-v1`** 的两个资源，URL 硬编码在 `AppFontPack`
- 落地：`filesDir/fonts/<fileName>`，先写 `.part` 再 rename，落地前校验**体积 + 字体魔数**
  （GitHub 出错会返回 HTML 错误页，不校验就会被当成字体）
- Android 端已下载的字体支持自由开关，亦可随时删除释放存储；删除后立即回到平台回退字体；下载完成后无需重启，主题会重组

⚠️ **换字体版本**：新建 `fonts-v2` 并**同步改 `AppFontPack` 里的 url**。不要删改已发布的
`fonts-v1` 资源 —— 老版本 App 的下载会 404。

⚠️ Android 需要 **API 26+**（`Typeface.Builder` 才能按 `wght` 实例化可变字体）。
24 / 25 上功能不可用，设置页会明说，文本继续走系统字体。

## 5. 改完怎么验

`app/src/desktopTest/.../preview/FontStackPreviewTest.kt` 是**唯一能证明路由生效**的测试
（编译与普通单测都量不到字形），三条断言：

1. 纯拉丁文本：`Text` 与 `CpText` 渲染**逐字节相同** ⇒ 拉丁没被换走；
2. 中文：两者必须**不同** ⇒ MiSans 真的生效（这条曾经抓出「只改了一个分支」的 bug：
   `CpText` 的悬停分支换了 `styled`，普通分支还在用 `shown`）；
3. 日文 vs 中文：必须不同。

另外会出一张对照图 `app/build-fonts/preview/font-stack-board.png`，肉眼核对四行文本。

### 歌词逐 run 路由的回归断言

`app/src/desktopTest/.../preview/ScriptFontRoutingPixelTest.kt`，四条：

1. 日英混排（`Tokyo の夜 03:45 Go!`）：「整行钉日文字体」与「逐 run 路由」必须**不同**
   ⇒ 拉丁真的换回 Google Sans Flex了；
2. 纯拉丁：套 span 与不套 span 必须**逐字节相同** ⇒ `TextScript.LATIN` 没有偷偷替换；
3. 出对照图 `app/build-scriptfont2/routing/routing-whole-noto.png` / `routing-per-run.png`；
4. 桌面端日文字体确实加载（否则上面几条会在「字体缺位」时静默失效）。

⚠️ 量这条**不能走 `LyricContent` 的离屏出图**：它经过歌词库的滚动 / reveal / 行时钟，
离屏单帧下当前行可能不显示，会把「渲染路径没跑起来」误判成「字体没生效」。
直接分两次 `measure` + `drawText(layout)` 比像素即可，那正是 `DefaultLyricsProfile.draw` 做的事。
