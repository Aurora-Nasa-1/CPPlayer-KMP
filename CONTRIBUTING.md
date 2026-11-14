# 贡献指南

> 构建、规范、提交与文档流程。适用读者：所有提交代码或文档的人。

环境与任务清单见 [BUILD](docs/dev/BUILD.md)。AI 协作的额外硬约定见 [AGENTS](AGENTS.md)。

## 1. 提交前检查

| 步骤 | 命令 / 判据 |
|---|---|
| 编译 | `./gradlew :app:compileKotlinDesktop :core:compileKotlinDesktop` |
| 测试 | 只认 `**/test-results/**/TEST-*.xml`；`skipped="0"` 才算跑过 |
| 链路回归 | `pwsh -File scripts/verify-provider-chain.ps1`（仅 Windows） |
| 契约 | 改过 SDK 才需要：`node provider-contract/generate.mjs --check` |
| 版式 | 改 UI 版式必须离屏出图核对，编译与单测都量不到宽度 |

不要用「应该能过」交差。构建成功不等于测试跑过；端到端测试 `assumeTrue` 跳过时同样 BUILD SUCCESSFUL。

## 2. Kotlin / KMP

| 规则 | 原因 / 做法 |
|---|---|
| `expect` / `actual` 可见性一致 | 默认值只能写在 `expect` 声明里 |
| 给 `core` 的接口加成员必须带默认实现 | 且默认值不能写 `get() = MutableSharedFlow()`：每次读属性都新建对象，订阅方永远收不到；用模块级单例，`by delegate` 自动继承 |
| 给已有函数加参数时，主 lambda 参数留在最后 | 否则尾随 lambda 静默改绑，报错指向调用点 |
| 重命名 composable 要改隐式 lambda 标签 | `Column { … return@Column }` 换成 `ScrollColumn` 后改 `return@ScrollColumn` |
| 批量改名先查子串 | 旧名可能是别的标识符的子串（`IntegrationRoutes` ⊂ `createIntegrationRoutes`）；`sed` 还会改写文档里的历史陈述 |
| 搜带点标识符用词边界 | `grep -rn 'AppModel\.api\b'`，否则漏掉「作为参数传出去」的调用点 |
| 死代码的粒度是声明不是文件 | 删声明前先查引用 |
| 服务端一律 Ktor CIO | Android 运行时没有 `com.sun.net.httpserver` |
| KDoc 不写 `xxx/*` | 星号紧跟斜杠会被当成嵌套块注释开头，报 `Unclosed comment` 且指向文件最后一行；写 `/api/v1/...`、`msg/...` |
| 字符串模板里函数调用写 `${f()}` | 否则插值的是函数引用 |
| `fun x() = runBlocking { … }` 用块体 | 末句若是有返回值的调用（如 `assertFailsWith`），方法非 void，JUnit 报 `InvalidTestClassError` 且不指行 |
| 嵌套类默认不是 `inner` | 需要外部实例时显式加 |
| 版本目录别名的访问路径把 `-` 换成 `.` | `accompanist-lyrics-ui` ⇒ `libs.accompanist.lyrics.ui`；按原名 grep 永远 0 命中 |
| `rememberScreenModel` 只能在 `Screen` 子类成员里调 | 它是 `Screen` 的扩展函数；放进顶层 `private @Composable fun` 会报 `Unresolved reference` 并连带下游几十行 unresolved |
| 不用 `kotlinx.datetime` 做运行时日期换算 | desktop 运行时是 0.7.x、编译期 0.6.x，`Instant` 已成 typealias ⇒ `NoClassDefFoundError`，包在 `runCatching` 里会静默退化成 null；用 `cp.player.core.util.localDateTimeOf(ms)` |

`rememberScreenModel` 的正确写法：在 `Screen.Content()` 里 `rememberScreenModel { … }`，把模型当参数传下去（`PlaylistDetailScreen` 即此写法）。

## 3. Compose / UI

### 3.1 外壳与导航

| 场景 | 唯一入口 |
|---|---|
| 返回键 | `CpBackButton` |
| 路由页外壳 | `CpRouteScaffold` |
| 双栏 | `CpTwoPane` |
| 二次确认 | `CpConfirmHost` + `rememberConfirmState()` |

| 规则 | 内容 |
|---|---|
| 桌面返回入口 | 只有窗口标题栏一个，页面不自绘；判据是 `LocalWindowChromeActive`（槽位是否被注入），不是平台 |
| 是否双栏右栏 | 由 `LocalEmbeddedInPane` 声明；不要用 `LocalIsExpanded` 猜，直接 push 到宽屏时它同样为真 |
| 破坏性操作 | 删除歌单 / 音源 / 账号、清空队列 / 历史必须先二次确认；一个页面的多个删除入口接到同一个 `CpConfirmState` |
| `CpConfirmHost` 位置 | 放页面组合末尾，与其余弹层同级；执行动作由 `state.confirm()` 先关窗再跑 |
| 可恢复操作 | 取消收藏、清缓存传 `destructive = false` |

### 3.2 尺寸、形状、动效

| 项 | 规则 |
|---|---|
| Expressive 实验 API | 不直接调 `LinearWavyProgressIndicator` / `LoadingIndicator` / `ToggleButton` / `MaterialShapes`，走 `ui/component/ExpressiveKit.kt` |
| 动效 | 从 `CpMotion` 取；`spatial`（位移 / 尺寸，可回弹）与 `effects`（颜色 / 透明度，不可回弹）不能混用 |
| 圆角 | 取 `MaterialTheme.shapes.*`，不写裸 `RoundedCornerShape(24.dp)` |
| 形状刻度 | 真值是 `ShapeTokens` 的 8 槽：`4 / 8 / 12 / 16 / 20 / 28 / 32 / 48`；改刻度时裸值不会跟着改 |
| 8 参数构造器 | `largeIncreased` / `extraLargeIncreased` / `extraExtraLarge` 三个槽位带 `@ExperimentalMaterial3ExpressiveApi`，用到要 `@OptIn` |
| 构造器顺序 | `extraSmall, small, medium, large, extraLarge, largeIncreased, extraLargeIncreased, extraExtraLarge`；必须用具名参数 |
| 必须写死时 | 注释注明它等于哪个槽位（百分比圆角、单边圆角、要喂给 `Dp` 参数的场景） |
| 渐变 | `Brush.verticalGradient` 不用绝对像素 `startY`：超过元素高度会方向翻转并 clamp 成末色；写分数 colorStops |

### 3.3 页面宽度与间距

| 页面类型 | 宽度上限 | 水平内边距 | 页容器 |
|---|---|---|---|
| 栅格 / 卡片页（首页、曲库、搜索、下载） | `pageMaxWidth` 1400 | `pageHorizontal` 20 | 自拼 `ScrollColumn` / `LazyScrollColumn` |
| 表单页（设置、账号、集成、诊断） | `formMaxWidth` 720 | `formHorizontal` 16 | 只认 `SettingsPage` / `SettingsLazyPage` |

| 规则 | 内容 |
|---|---|
| 栅格列数 | `CpSpacing.gridColumns(内容宽度)` |
| 修饰符顺序 | `Modifier.fillMaxWidth().widthIn(max = …)` 里 `widthIn` 是空操作，必须 `widthIn(...).fillMaxWidth()` |
| 表单页宽度 | 已收进 `Modifier.settingsContentWidth()`，页面不自拼 |
| 懒加载 | 长列表用 `SettingsLazyPage`，与 `SettingsPage` 同宽同距 |
| 表单行度量 | `formRowMinHeight` / `formRowHorizontal` / `formRowVertical` / `formRowGap` / `listRowGap` 只从 `CpSpacing` 取 |
| 入库判据 | 这个值会不会在第二个页面出现？会就必须进 `CpSpacing`；组件内部微调（图标与文字间 4 / 6 / 8dp）可写裸值 |
| 历史 | 重构前 11 个设置页里 4 个自拼容器，同一套设置四种边距 |

### 3.4 颜色与主题

| 场景 | 规则 |
|---|---|
| 设置页「当前生效 / 选中」行 | 只认 `SettingsClickItem(selected = true)`，不自传 `containerColor = primaryContainer` |
| 行尾有独立控件 | `mergeSemantics = false`，否则语义合并会把子节点点击并进父节点，读屏用户点不到 |
| 设置行底色 | 只认 `settingsRowContainer()`；直接用 `LegacyListItem` 默认值会深一档 |
| 非设置项的行 | 日志条目、歌曲行可直接用 `LegacyListItem`，但必须显式传底色 |
| 图上白字 | 必须有 `overImage` 兜底分支，无封面时回落 `onSurface` / `onSurfaceVariant` + 浅色容器 |
| 判断深色 | 读 `LocalIsDarkTheme`，不读 `isSystemInDarkTheme()`：用户可显式指定浅色 / 深色，两者只在「跟随系统」时相等 |
| 浅色容器 | 设置行 / 歌曲行的浅色容器不能是 `surface`，否则分组卡片与页面背景同色不可见，用 `surfaceContainerLow` |

### 3.5 加载态

| 规则 | 内容 |
|---|---|
| 列表 / 详情页加载中 | 用骨架屏，不要居中加载器：整屏空白后整屏跳出，用户感知的等待更长 |
| 挂载条件 | `loading && 数据为空`；无限动画常驻就是白烧帧 |
| 形状 | 圆角与边长照抄真组件 |
| 通用块 | `ui/component/CpSkeletons.kt`；页面级组合在 `ui/screen/HomeSkeleton.kt`；不要手拼灰条 |
| 结构 | 骨架摆错结构比居中加载器更糟：搜索结果里专辑 / 歌单 / 歌手都是卡片行，不是封面栅格；歌单详情 hero 只有动作胶囊、没有封面 |
| 卡片行配色 | 卡片 `surfaceContainerLow`、块 `surfaceContainerHighest`（`CpShimmerBox(base = …, highlight = surfaceBright)`）；两者同为 `High` 时出图是一整条纯色 |
| 验证 | 这类问题只有离屏出图能发现，探针见 `app/src/desktopTest/.../SkeletonVisualTest.kt` |

### 3.6 资源

Compose 资源（字体 / 图片）要在 Android 生效，`:app` 必须 `androidResources { enable = true }`。

| 项 | 内容 |
|---|---|
| 原因 | `com.android.kotlin.multiplatform.library` 默认关掉 Android 资源处理，AGP 一个 assets 任务都不创建，`copyAndroidMainComposeResourcesToAndroidAssets` 拿不到 `outputDirectory` |
| 症状 | 编译、单测、桌面全绿，AAR / APK 里没有一个资源文件，真机静默回退系统字体 |
| 验收 | `unzip -l app-android/build/outputs/apk/debug/*.apk \| grep composeResources` |
| 包内路径 | 由 `compose.resources.packageOfResClass` 决定（本项目 `cp.player.app.resources`），改包名要同步改预期 |

## 4. 后端 / 桌面

| 规则 | 内容 |
|---|---|
| 持久化路径 | 统一走 `core/.../util/DesktopDataDir.kt`（`~/.cpplayer/`）；必须在 `jvmMain`，`desktopMain` 的类看不到它 |
| `defaultSettingsStorage(namespace)` | 不是单例：构造时读全文件、每次写入全量回写；两实例写同一 namespace 会互相覆盖，新场景换新 namespace |
| `MusicBackend.backendScope` | 用 `Dispatchers.Main`，状态读写天然串行；改 `Default` 会立刻变成真数据竞争 |
| 例外 | `AudioPlayerImpl` 的位置轮询刻意用 `Default`：桌面 Main 是 Swing EDT，每 200ms 经 JNI 阻塞一次会破坏帧节奏 |
| 编译失败 | 先确认报错文件是不是自己的；在途包会让整个 `:core` 编译不过。判定 WIP：`git grep -n "<符号>" HEAD` 在 HEAD 也找不到即是在途 |

## 5. 文档

| 场景 | 做法 |
|---|---|
| 改 SDK 契约 | 结构改 `provider-contract/schema.mjs`，语义改 `documentation.mjs`，再跑 `node provider-contract/generate.mjs` |
| 生成物 | `SDK_OPERATIONS.md`、`SDK_TYPES.md`、Kotlin / TS 声明与派发代码都不可手改 |
| 校验 | `node provider-contract/generate.mjs --check`；`--check` 同时检查字段参考、操作参考、注释与分发代码 |
| 新增能力 | 不改既有字段顺序、类型与操作 tag；新方法提供 `Unsupported` 默认实现 |
| 文档站 | 源文档就是 `docs/` 的 Markdown；`npm --prefix docs run dev` 本地预览、`run build` 出产物到 `docs/.vitepress/dist`；导航与侧边栏在 `docs/.vitepress/config.mts` |
| 站点校验 | `node scripts/check-docs-links.mjs` 校验产物里的站内链接与锚点（VitePress 自身只查页面存在，不查锚点） |
| 链接 | `node --test provider-contract/documentation.test.mjs` 会校验全部文档的相对链接；改路径必须同步 |

## 6. 编码完整性

本仓库发生过整文件被毁，症状判定与修复顺序固定。

| 项 | 内容 |
|---|---|
| 症状 | 出现 `[E0-EF][80-BF]3F`：CJK 尾部字节被换成 `0x3F`，有时连同紧跟的 `"` / 换行 / 空格被吃掉 |
| 成因 | 整条字节流被按 GBK 解码、再按 UTF-8 编码（`errors='replace'`） |
| 判定 | ASCII 与 CJK 首字节（≥0xC0）不被动；2 字节 / 4 字节字符不受害 |
| 修复底本 | 用改动前编译出的 `.class`：CJK 字符串常量按 UTF-8 存放，可逐条核对 |
| 无法核对的部分 | KDoc 注释与裸字符串的缩进不在 `.class` 里，只能靠上下文与同作者兄弟文件判断，并标注为推断 |
| 验收 | ① 结果是合法 UTF-8；② 每个 CJK 字面量都能在 `.class` oracle 里找到 |
| 存盘 | 一律 UTF-8；别用会改编码的编辑器或工具批量处理 `.kt` |

## 7. Git

| 场景 | 做法 |
|---|---|
| `git mv` 后要分离「功能」与「结构」两个提交 | 先 `git reset` 清空索引再按路径 `git add`，或用 `git commit -- <paths>` |
| 处理生成物 | `git rm -r --cached <dir>` 只移出索引，磁盘文件不动 |
| 子仓库 `reference/netease-module-rust` | 先 push 子仓库再 bump gitlink；bump 前 `git ls-remote origin` 确认可达；不在里面跑 `stash` / `reset --hard` / `checkout` |

## 8. 提交与评审

| 项 | 要求 |
|---|---|
| 提交粒度 | 一个提交一件事；重构与功能分开 |
| 提交信息 | 说明「为什么」，不是「改了什么文件」 |
| 新功能 | 同一提交内带文档；破坏性变更带迁移说明 |
| 评审 | 需要能复现的验证证据（测试 XML、出图、命令输出） |

## 相关

- 构建与验证：[BUILD](docs/dev/BUILD.md)
- 架构：[ARCHITECTURE](docs/dev/ARCHITECTURE.md)
- AI 协作硬约定：[AGENTS](AGENTS.md)
- 文档索引：[docs/index](docs/index.md)
