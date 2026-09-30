# AGENTS.md — AI 协作硬约定（CPPlayer-KMP）

> **任何 AI 在本仓库动手前先读这一页。** 这里只写「不遵守就会出事」的规则，以及
> 「细节去哪找」。架构 / 模块 / 构建命令见 [`README.md`](README.md)；
> 集成契约、Provider 开发、重构计划见 [`docs/`](docs/)。
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

## 6. Compose / UI 规则

- **不要直接在页面里调 material3 的 Expressive 实验 API**
  （`LinearWavyProgressIndicator` / `LoadingIndicator` / `ToggleButton` / `MaterialShapes` …）：
  一律走 `app/src/commonMain/.../ui/component/ExpressiveKit.kt`。理由：省 opt-in、
  统一尺寸约束（波形进度条默认容器高度远大于普通进度条）、观感一处调处处变。
- **动效不要手写 `spring(...)`**：从 `CpMotion` 取。`spatial`（位移 / 尺寸，可回弹）
  与 `effects`（颜色 / 透明度，**不可回弹**）混用会「看着不对」。
- **页面宽度只能取 `CpSpacing.pageMaxWidth`**；栅格列数取
  `CpSpacing.gridColumns(内容宽度)`。
  ⚠️ 写 `Modifier.fillMaxWidth().widthIn(max = …)` **`widthIn` 是空操作**
  （约束已被钉死），必须 `widthIn(...).fillMaxWidth()`。
- **压在图上的白字必须有 `overImage` 兜底分支**：没有封面时回落到 `onSurface` /
  `onSurfaceVariant` + 浅色容器。浅色主题下 `surfaceContainerLow` 近乎白色，
  写死 `Color.White` 会直接糊掉（本仓库已踩过三次）。
- **`Brush.verticalGradient(colors, startY = …)` 别用绝对像素**：`startY` 超过元素高度时
  方向翻转、整块被 clamp 成末色。写分数 colorStops。

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
