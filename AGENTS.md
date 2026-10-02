# AGENTS.md — AI 协作硬约定（CPPlayer-KMP）

> **任何 AI 在本仓库动手前先读这一页。** 这里只写「不遵守就会出事」的规则，以及
> 「细节去哪找」。架构 / 模块 / 构建命令见 [`README.md`](README.md)；
> 集成契约、Provider 开发见 [`docs/dev/`](docs/dev/)，历史方案归档见 [`docs/history/`](docs/history/)。
>
> 领域细则（渲染后端 / seek 管线 / 缓存 / JNI / 构建工具链 / 平台集成）见
> `.workbuddy-ai/memory/TOPICS.md`，决策来历见 `.workbuddy-ai/memory/LOG.md`
> （该目录**不进版本库**，是本机工作副本）。
>
> 每条规则都对应一次真实踩坑。**它失效了就来改这一页，别照抄。**

---

## 1. 交付前必须验证（别用「应该能过」交差）

- **编译**：改完跑 `:app:compileKotlinDesktop` 与 `:core:compileKotlinDesktop`。
  ⚠️ `commonMain` 是**整个源集一起编译**的 —— 一个文件坏了，源集里**所有**文件一起报错。
  报错列表里绝大多数文件都不是凶手：**看第一个报错的文件，别「谁报错谁错」**。
- **测试结论只认 `**/test-results/**/TEST-*.xml`**，不要 `| head` 截 Gradle 输出。
  ⚠️ 端到端测试用 `assumeTrue` 跳过时**同样 BUILD SUCCESSFUL** —— 必须确认 `skipped="0"`。
- **改版式不能只靠编译 + 单测**：两者都量不到宽度、看不见对齐，要**离屏渲染出图**核对。
- 「某模块编译不过」这类结论，必须落到**具体文件 + 具体错误行**再下，
  别从失败日志的尾部猜是哪个模块。

## 2. 并行会话安全（本仓库常有多个 AI 同时改同一工作区）

- **同一文件一次只发一个 `Edit`**：同消息内多个 `Edit` 会互相覆盖，
  **被覆盖的那个仍然报成功**。改完 grep 复核。跨文件并行安全。
- **绝不删改别人的在途（untracked）文件。** 在途工作不受 git 保护。
- **在途工作要么 commit，要么 `git stash -u`**：untracked 挡不住并发会话的
  `git clean` / `reset --hard`（本仓库整包被删过一次）。
- **`git grep` 只搜「索引里的文件」⇒ 看不见 untracked 在途文件**，会静默返回空、
  看起来像「这个符号不存在」。**判定引用关系用 Grep 工具（走磁盘）**；
  只有判定「它是否在 HEAD 也不存在」时才用 `git grep`。
- 诊断命令用 `;` 串联，**不用 `&&`** —— 前面失败会静默跳过后续，误判成「已经跑过」。

## 3. 构建 / Gradle

- 根 `.gradle/9.4.1/fileHashes/fileHashes.lock` 常被**并行会话**占用（Gradle 不等待，
  直接报拒绝访问）。绕开：`--project-cache-dir=<会话专属>` + **Groovy** init script
  把 `layout.buildDirectory` 换到 `build-verify-<后缀>/<module>`。
  ⚠️ **必须 Groovy**（`.kts` 里 `allprojects { p -> … }` 会挑错重载）；
  ⚠️ 光加 `--project-cache-dir` **不够**；⚠️ 共享的 verify 目录**本身也会被占**。
- ⚠️ **别 `rm -rf` 构建目录**：会撞上安全删除闸（>50 文件被拦），命令静默失败。
  要强制重编译用 `./gradlew <task> --rerun`。
- ⚠️ **用完删掉脚手架**（`build-verify*/`、`.gradle-verify*/`、init script）——
  留着会**永久跳过被 exclude 的测试**。
- ⚠️ init script 里排除在途破损的测试文件，**必须写在 `afterEvaluate` /
  `projectsEvaluated`**：挂在 `plugins.withId(…multiplatform)` 上时源集还没创建
  ⇒ **静默不生效**（脚本不报错，报错一个不少）。加 `println` 确认真的跑了。
- init script 求值阶段 `gradle.rootProject` 不可用，在 `allprojects { p -> … }` 里用
  `p.rootProject` / `p.rootDir`。
- 正确任务名：`:core:compileKotlinDesktop`（**不是** `compileDesktopKotlin`）、
  `:core:compileAndroidMain`（**不是** `compileKotlinAndroid`，后者不存在）。
- 根项目 `name` 是 `CPPlayer`，不是目录名。
- 判断「构建坏了」要**分开看 main / test 源集**：`desktopRun` 只编译 main 源集、
  从不编译测试 —— 「应用能跑」与「测试编译不过」可以同时成立。
- 沙箱设了 `http_proxy=127.0.0.1:64222`，**原生 HTTP（rodio / reqwest）也会走它**，
  访问 `127.0.0.1` 会拿到 502。本地探针要 `env -u http_proxy -u https_proxy …`；
  测试 JVM 要设 `no_proxy=127.0.0.1,localhost`。
- 后台起的 HTTP server 会**随父 shell 一起被杀**：server 和客户端放进**同一条命令**。
- ⚠️ **`nativeDistributions.description` 只能 ASCII**（vendor 同；copyright 里的 `©`
  没事，它在 cp1252 里且只进 exe 版本资源）。jpackage 把它原样写进 MSI 的
  `Package/@Description`，而 MSI 数据库 codepage 被 jpackage 内置的
  `MsiInstallerStrings_en.wxl` 钉死在 1252 ⇒ light.exe 报 **LGHT0311**，
  jpackage 只甩一句 `exited with 311 code`，**日志里找不到原因**（要 `--verbose` 才看得到）。
  `app/build.gradle.kts` 里已加 `require` 断言拦一道。打进包的文件名也不能有非 ASCII
  （同一个坑，见 JDK-8290471）。

## 4. 文本编码完整性（本仓库有过整文件被毁）

- **症状**：文件里出现 `[E0-EF][80-BF]3F` —— CJK 尾部字节被换成 `0x3F`，
  有时连紧跟的后一个字节（`"` / 换行 / 空格）一起被吃掉。
  成因是**整条字节流被按 GBK 解码、再按 UTF-8 编码**（`errors='replace'`）。
  ASCII 与 CJK 首字节（≥0xC0）不会被动；2 字节 / 4 字节字符也不受害。
- **不要靠猜**。修之前先建 oracle：**同文件改动前编译出的 `.class`** 里，
  CJK 字符串常量按 UTF-8 存放，可当权威底本逐条核对。
  ⚠️ KDoc 注释与裸字符串的缩进**不在** `.class` 里 —— 那部分只能靠上下文 +
  同作者兄弟文件的风格判断，并如实标注为「推断」。
- **改完必须验证**：① 结果是合法 UTF-8；② 每个 CJK 字面量都能在 `.class` oracle 里找到。
- 存文件一律 UTF-8。**别用会改编码的编辑器 / 工具批量处理 `.kt`**。

## 5. Kotlin / KMP 代码规则

- `expect` / `actual` 可见性必须一致；**默认值只能写在 `expect` 声明里**。
- **给 `core` 的接口加成员必须带默认实现**，且默认值**不能**写成
  `get() = MutableSharedFlow()`（每次读属性都新建对象，订阅方永远收不到东西）——
  用模块级单例；用 `by delegate` 的实现自动继承。
- **给已有函数加参数时，主 lambda 型参数必须留在参数表最后**（否则尾随 lambda
  静默改绑，报错指向调用点）。
- **重命名 composable 会改隐式 lambda 标签**：`Column { … return@Column }` 换成
  `ScrollColumn` 后必须同步改成 `return@ScrollColumn`。
- 批量改名时**旧名可能是别的标识符的子串**（`IntegrationRoutes` ⊂
  `createIntegrationRoutes`）；批量 `sed` 还会改写文档里的**历史陈述**。
- 搜索带点的标识符要用**词边界**（`grep -rn 'AppModel\.api\b'`），否则会漏掉
  「作为参数传出去」的调用点。
- 判断「死代码」的粒度是**声明不是文件**；删声明前先查引用。
- Android 运行时没有 `com.sun.net.httpserver`，服务端一律用 Ktor CIO。
- KDoc 里写 `/api/v1/*` 会被当成嵌套块注释开头 ⇒ `Unclosed comment`，写成 `/api/v1/...`。
- Kotlin 字符串模板里函数调用必须写 `${f()}`。
- `fun x() = runBlocking { … }` 的最后一句决定返回类型：末尾若是 `assertFailsWith` 这类
  **有返回值**的调用 ⇒ 方法非 void ⇒ JUnit 报 `InvalidTestClassError` 且不说是哪一行。
  用块体 `{ }`。
- Kotlin 嵌套类默认**不是** `inner`。
- 版本目录里带连字符的别名要把 `-` 换成 `.` 才是访问路径：
  `accompanist-lyrics-ui` ⇒ `libs.accompanist.lyrics.ui`。按原名 grep **永远 0 命中**。
- **`rememberScreenModel` 是 `Screen` 上的扩展函数，只能在 Screen 子类的成员里调用。**
  Voyager 1.1.0-beta03 的签名（`javap` 核实）是
  `rememberScreenModel(Screen, String, Function0<T>, Composer, …)` —— 第一个参数就是接收者。
  放进顶层的 `private @Composable fun XxxContent(...)` 里会报
  `Unresolved reference 'rememberScreenModel'`，**并连带把下游几十行都标成 unresolved**
  （`by` 代理、`state.xxx` 全崩），看起来像「整个文件都坏了」，实际只有一处。
  正确写法：在 `Screen.Content()` 里 `rememberScreenModel { … }`，把模型**当参数传下去**
  （`PlaylistDetailScreen` 就是这么写的）。
- KDoc / 注释里写 `xxx/*`（星号紧跟在斜杠后）会被当成**嵌套块注释**的开头，
  报 `Syntax error: Unclosed comment.` 且**指向文件最后一行**。
  本仓库踩过两次：`/api/v1/*`（旧）与 `msg/*`（2026-10-01）。写成 `/api/v1/...` / `msg/...`。
- **别用 `kotlinx.datetime` 做运行时日期换算** —— 它在 desktop 运行时类路径上解析到的是
  **0.7.x**（`java.class.path` 里是 `kotlinx-datetime-jvm-0.7.1.jar`），而编译期是 0.6.x。
  0.7 起 `kotlinx.datetime.Instant` 已改成指向 `kotlin.time.Instant` 的 **typealias**
  （不再生成类文件）⇒ 运行到那一行就是 `NoClassDefFoundError: kotlinx/datetime/Instant`。
  包在 `runCatching` 里的写法会**静默退化成 null / 空串**，看起来像「数据本来就没有」——
  专辑发行年份整整一栏就是这么丢的。
  要日期分量走 `cp.player.core.util.localDateTimeOf(ms)`（expect/actual，jvm 侧用 `java.time`），
  与早已存在的 `currentTimeMillis()` 同一套做法（那个的 KDoc 里也写着同一句话）。
  ~~⚠️ 例外：`PlaylistDetailScreen.formatPublishDate` 仍在用 kotlinx-datetime~~
  （2026-10-02 已修，同批还清掉了 `HealthScreen` 的 runCatching 静默退化隐患；
  目前 app 源码已无 kotlinx-datetime 运行时引用，新代码别再引入）。

## 6. Compose / UI 规则

- **返回键只认 `CpBackButton`，路由页外壳只认 `CpRouteScaffold`，双栏只认 `CpTwoPane`。**
  （均在 `app/src/commonMain/.../ui/component/`）
  - 别再手写 `IconButton { Icon(ArrowBack) }` —— 收敛前仓里同时存在**四套**返回键外观
    （`AppScaffold` 的填充圆钮 / 10 处裸 `IconButton` / 歌单详情宽屏又抄了一遍填充圆钮 /
    `HomeScreen` 里私有的 `PageTitleBar`），同一屏就能看出差别。
  - 别再写 `if (expanded) body(Modifier.fillMaxWidth()) else LegacyPageScaffold(…)`。
    那个分支的**宽屏侧整页没有返回入口**（桌面默认窗口 1320×860、最小 900×640 都 ≥840 断点），
    而当时 Esc 也没有任何处理器 —— 从标题栏点「账号」进去之后**退不出来**。
  - **桌面端（`LocalWindowChromeActive`）返回入口只有窗口标题栏一个**，页面一律不自绘
    （`AppScaffold` 与 `CpRouteScaffold` 内部已判）。判据用 `LocalWindowChromeActive`
    （槽位是否被注入）而不是平台，理由见它的 KDoc。
  - 页面**是不是双栏的右栏**由 `LocalEmbeddedInPane` 声明，**不要**拿 `LocalIsExpanded` 去猜：
    直接 push 到宽屏时后者同样为真，会把「需要返回键的整页」误判成「右栏」。
- **不要直接在页面里调 material3 的 Expressive 实验 API**
  （`LinearWavyProgressIndicator` / `LoadingIndicator` / `ToggleButton` / `MaterialShapes` …）：
  一律走 `app/src/commonMain/.../ui/component/ExpressiveKit.kt`。理由：省 opt-in、
  统一尺寸约束（波形进度条默认容器高度远大于普通进度条）、观感一处调处处变。
- **动效不要手写 `spring(...)`**：从 `CpMotion` 取。`spatial`（位移 / 尺寸，可回弹）
  与 `effects`（颜色 / 透明度，**不可回弹**）混用会「看着不对」。
- **页面宽度与边距只能取 `CpSpacing`**，分两类；两套各自成立，**混用才是错的**：

  | 页面类型 | 宽度上限 | 水平内边距 | 页容器 |
  |---|---|---|---|
  | 栅格 / 卡片页（首页、曲库、搜索、下载 …） | `pageMaxWidth` 1400 | `pageHorizontal` 20 | 自己拼 `ScrollColumn` / `LazyScrollColumn` |
  | 表单页（设置、账号、集成、诊断 …） | `formMaxWidth` 720 | `formHorizontal` 16 | **只认 `SettingsPage` / `SettingsLazyPage`** |

  栅格列数取 `CpSpacing.gridColumns(内容宽度)`。
  - ⚠️ 写 `Modifier.fillMaxWidth().widthIn(max = …)` **`widthIn` 是空操作**
    （约束已被钉死），必须 `widthIn(...).fillMaxWidth()`。表单页这条已收进
    `Modifier.settingsContentWidth()`，页面不要再自己拼。
  - ⚠️ **懒加载不是「可以另写一套边距」的理由**：长列表用 `SettingsLazyPage`，
    与 `SettingsPage` 同宽同距。表单行的度量（`formRowMinHeight` / `formRowHorizontal` /
    `formRowVertical` / `formRowGap`）与组内行距（`listRowGap`）同样只从 `CpSpacing` 取。
  - 判据：**这个值会不会在第二个页面出现**？会 ⇒ 必须进 `CpSpacing`。
    组件内部的微调（图标与文字之间 4/6/8dp）允许写裸值。
  - 历史：重构前 11 个设置页里有 **4 个自己拼容器**（根页没有宽度上限、关于页 16dp 四边等距 +
    4dp 行距、音源管理 8dp 页边距、诊断页 20/12/48 混着）—— 同一套设置四种边距。
- **设置页的「当前生效 / 选中」行只认 `SettingsClickItem(selected = true)`**，
  不要自己传 `containerColor = primaryContainer` —— 收敛前「左栏选中 / 当前账号 / 当前音源」
  三个地方三种颜色（`surfaceContainerHigh` / alpha 0.5 / alpha 0.45），两个 alpha 纯属巧合。
  - 行尾有**独立可操作控件**（删除 / 移除图标按钮）时必须 `mergeSemantics = false`：
    语义合并会把子节点的点击动作并进父节点，读屏用户就再也点不到那个按钮。
- **设置行的底色只认 `settingsRowContainer()`**（深色 `surfaceContainerHighest` /
  浅色 `surfaceContainerLow`）。直接吃 `LegacyListItem` 的**默认值**（`surfaceContainerHigh`）
  会让这一页比其余设置页深一档 —— 关于页 / 音源管理 / 诊断页三处都踩过。
  `SettingsClickItem` / `SettingsButtonItem` 已代为处理，只有「不是设置项的行」
  （日志条目、歌曲行）才直接用 `LegacyListItem`，那时**必须显式传底色**。
- **压在图上的白字必须有 `overImage` 兜底分支**：没有封面时回落到 `onSurface` /
  `onSurfaceVariant` + 浅色容器。浅色主题下 `surfaceContainerLow` 近乎白色，
  写死 `Color.White` 会直接糊掉（本仓库已踩过三次）。
- **`Brush.verticalGradient(colors, startY = …)` 别用绝对像素**：`startY` 超过元素高度时
  方向翻转、整块被 clamp 成末色。写分数 colorStops。
- **圆角一律取 `MaterialTheme.shapes.*`，不要写裸 `RoundedCornerShape(24.dp)`**。
  刻度真值 = `androidx.compose.material3.tokens.ShapeTokens`（1.11 起是 **8 槽**：
  `4 / 8 / 12 / 16 / 20 / 28 / 32 / 48`）。**改刻度时裸值不会被跟着改**，
  于是同一屏出现两套圆角 —— 这正是本仓库 2026-10-01 那次视觉改版要修的问题
  （当时 `AppShapes` 只填了 5 槽、整条刻度 +4dp，25 处消费点集体偏圆）。
  - ⚠️ `Shapes` 的 **8 参数构造器**、以及 `largeIncreased` / `extraLargeIncreased` /
    `extraExtraLarge` 三个新槽位**都带 `@ExperimentalMaterial3ExpressiveApi`**
    （`extraSmall`…`extraLarge` 这五个老槽位**不**带）⇒ 用到就要 `@OptIn`。
  - ⚠️ 构造器参数顺序是 `extraSmall, small, medium, large, extraLarge, largeIncreased,
    extraLargeIncreased, extraExtraLarge`（`extraLarge` 在 `largeIncreased` **之前**）。
    **必须用具名参数** —— 位置参数写错不报错、只静默给错圆角。
  - 少数确实表达不了的（百分比圆角、单边圆角、需要同时喂给 `Dp` 参数的）
    才写死，并**在注释里注明它等于哪个槽位**。
- **需要「当前是不是深色」时读 `LocalIsDarkTheme`，不要读 `isSystemInDarkTheme()`**。
  应用允许用户显式指定浅色 / 深色（`ThemeMode.LIGHT` / `DARK`），两者**只在「跟随系统」时相等**
  —— 读错的后果是去取**另一套色板**的角色色，典型症状是「卡片与页面背景同色、整块糊住」。
  （`SettingsKit.settingsRowContainer()` / `SongItem` / `PlayerScreen` 三处都踩过。）
  另：**设置行 / 歌曲行的浅色容器色不能是 `surface`** —— 设置页背景本身就是 `surface`，
  两者相同则分组卡片完全不可见（离屏取像素实测过），要用 `surfaceContainerLow`。

- **Compose 资源（字体 / 图片）要在安卓上生效，`:app` 必须 `androidResources { enable = true }`。**
  `com.android.kotlin.multiplatform.library` 默认关掉 Android 资源处理，连带 AGP **一个 assets
  任务都不创建** ⇒ Compose 插件注册的 `copyAndroidMainComposeResourcesToAndroidAssets` 拿不到
  `outputDirectory`，永远不进任务图。症状极隐蔽：**编译、单测、桌面端全绿，AAR / APK 里却一个
  资源文件都没有**，只有安卓真机运行时静默回退到系统字体。
  验收命令：`unzip -l app-android/build/outputs/apk/debug/*.apk | grep composeResources`。
  ⚠️ 资源在包内的路径由 `compose.resources.packageOfResClass` 决定
  （本项目 = `cp.player.app.resources`），**改包名必须同步改这条路径的预期**。

## 7. 后端 / 桌面

- 桌面持久化统一走 `core/.../util/DesktopDataDir.kt`（`~/.cpplayer/`）。
  **新增桌面路径必须走它**，且它必须在 `jvmMain`（`desktopMain` 的类看不到它）。
- ⚠️ `defaultSettingsStorage(namespace)` **不是单例**：构造时把整个文件读进内存、
  每次写入**全量回写**。两个实例写同一 namespace 会互相覆盖。
  **给新场景加键就换个新 namespace。**
- `MusicBackend.backendScope` 用 `Dispatchers.Main`（Android 主线程 / 桌面 EDT），
  状态读写天然串行。**改成 `Default` 会立刻变成真数据竞争。**
  例外：`AudioPlayerImpl` 的位置轮询**刻意用 `Default`**（桌面 Main 就是 Swing EDT，
  每 200ms 经 JNI 阻塞一次出帧会破坏帧节奏）。
- 编译失败先确认报错文件是不是自己的：在途包会让**整个 `:core` 编译不过**且反复变化。
  判定某符号是否属于在途 WIP：`git grep -n "<符号>" HEAD` 在 HEAD 也找不到 ⇒ 是在途。

## 8. Git

- `git mv` 的暂存状态会被后续 `git commit` 一并带走。要分离「功能」与「结构」两个提交：
  先 `git reset` 清空索引再按路径 `git add`，或用 `git commit -- <paths>`。
- `git rm -r --cached <dir>` 只移出索引，**磁盘文件不动** —— 处理生成物优先用它。
- `reference/netease-module-rust` 是**独立 git 仓库**：必须先 push 子仓库再 bump gitlink，
  bump 前用 `git ls-remote origin` 确认可达。
  **不要在里面跑会重写索引的 git 命令**（`stash` / `reset --hard` / `checkout`）。
