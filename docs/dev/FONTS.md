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
- `LyricContent`（歌词按**行**交给歌词库渲染，拿不到 `AnnotatedString`，所以整行换家族）

⚠️ **裸 `Text(...)` 仍是系统 CJK 字体**。想让某处也走 MiSans、Noto，把它换成 `CpText`
即可获得全部字体路由（注意 `CpText` 的 `maxLines` 默认是 1，多行文本要显式传）。

## 4. 桌面内置 vs Android 下载

Android 不内置这两款（合计约 30 MB，每个用户都要为它付包体积），改成用户显式下载：

- 入口：设置 → 外观 → **字库**（`FontPacksSection`，两端共用同一份 UI）
- 来源：本仓库的 GitHub Release **`fonts-v1`** 的两个资源，URL 硬编码在 `AppFontPack`
- 落地：`filesDir/fonts/<fileName>`，先写 `.part` 再 rename，落地前校验**体积 + 字体魔数**
  （GitHub 出错会返回 HTML 错误页，不校验就会被当成字体）
- 删除后立即回到平台回退字体；下载完成后无需重启，主题会重组

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
