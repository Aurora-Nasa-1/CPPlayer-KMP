# 桌面端打包：换用 JBR（JetBrains Runtime）

> 目标：让 `WindowDraggableArea` 从 **软件移动**（`StandardMoveHandler`）升级到
> **原生 `WindowMove`**（`JbrMoveHandler`），换来 **贴边吸附 / 拖出还原 / 拖动跟手**。
>
> 结论先说：**这不是「换个 JAVA_HOME 就完事」**。换 JBR 只是拿到原生拖动的**必要条件**，
> 但项目里有两处代码**按 Zulu 的非 JBR 行为写的**，换完之后必须复查，否则会出现
> 「拖得动了，但拖拽区和控件边界对不上」这类只有在真机上才暴露的问题。§4 逐条列了。

---

## 1. 为什么不直接 `javaHome = file("/path/to/jbr")`

`compose.desktop.application.javaHome` 只是 jpackage 的 `--runtime-image` 来源。
直接指向本机某个 JBR 目录能跑通，但会把「JBR 在哪」变成**每台机器各自的秘密**：

- 新同事 clone 下来 `packageMsi` 直接失败，报的还是 jpackage 那种只有 exit code 的错；
- CI 上 runner 只有 temurin，没有 JBR（见 §3），构建要么红要么悄悄退回 JBR 缺失；
- 「本机能打，CI 不能打」和「CI 能打，本机不能打」两种事故都会出现。

所以方案是：**把 JBR 当成和 `gradle-wrapper.jar` 同级的、版本钉死的构建输入** ——
按需下载到仓库内固定目录、校验 sha256、路径由构建脚本算出来，
`javaHome` 只是这个算出来的路径的**消费方**。

---

## 2. 版本与来源（已实测，非推测）

### 2.1 选 21 而不是 25

`desktop` 的 `jvmTarget` 是 **JVM_21**（`app/build.gradle.kts` 的 `jvm("desktop")`）。
用 JBR 25 打出的运行时是 25，class 文件版本 65（Java 21）在上面能跑，但：

- JDK 24+ 对 **FFM 原生访问**改了规则（`--enable-native-access=ALL-UNNAMED`
  已在 `jvmArgs` 里，这倒是有）；
- `kotlinx-datetime` / `skiko` 这类带 JNI 的库在更新的运行时上要重新验证一遍；
- **Zulu 25 换成 JBR 25 只是换厂商，换 JBR 21 则是降主版本** —— 后者风险小得多，
  因为项目的目标版本本来就是 21。

⇒ **钉 21**。要升 25 是独立的一件事，别和「换运行时」捆在一起。

### 2.2 来源用 JetBrains 官方 cache-redirector，不用 GitHub Releases

这是 IntelliJ 自己用的分发渠道（`cache-redirector.jetbrains.com/intellij-jbr/`），
**实测 200 且给 bytes**；`JetBrains/JetBrainsRuntime` 的 GitHub Releases 侧
**`assets: 0`**（全量扫过 3 页 100/页，一个带资产的都没有），别往那写。

| 平台 | 文件 | 实测大小 |
|---|---|---|
| Windows x64 | `jbrsdk-21.0.8-windows-x64-b1163.62.zip` | ≈270 MB |
| Linux x64 | `jbrsdk-21.0.8-linux-x64-b1163.62.tar.gz` | ≈240 MB |

> 注意：Linux 侧 **`.zip` 是 403**，只有 `.tar.gz`；Windows 侧两者都有。
> 校验和文件（`.sha256`）也是 403 —— 官方不提供，所以 sha256 必须**我们自己记**。

### 2.2.0 ⚠️ 必须用 `jbrsdk-` 变体，不能用裸 `jbr-`（踩过一次）

本文件旧版曾写「jbrsdk 不要用，打包只运行不编译」——**这个结论是错的**：

- `compose.desktop.application.javaHome` 不只是 jpackage 的输入，Compose 插件的
  `checkRuntime` 任务会先校验 javaHome 是**完整 JDK**（要有 `bin/jlink` 和
  `bin/jpackage`），因为它要**自己跑 `jlink` 从 jmods 裁出运行时镜像**再交给 jpackage。
- 裸 `jbr-` 包是 JRE（`java -version` 输出里的 `-nomod` 后缀就是「无 jmods」的意思），
  只有 `bin/java`。CI 上直接失败：
  `Execution failed for task ':app:checkRuntime' > Failed to check JDK distribution:
  'jlink', 'jpackage' are missing`。
- **代价**：SDK 包 ~240–270MB（构建期磁盘），但**打进安装包的是 jlink 裁剪后的产物，
  体积不变**。

### 2.2.1 压缩包内部结构（踩过一次）

解出来**不是** `jbr/` 一层，而是**带版本号的目录**：

```
jbrsdk-21.0.8-windows-x64-b1163.62/
  ├── bin/java.exe      ← SDK 变体里还有 jlink / jpackage / jmods/
  ├── lib/
  └── release            ← javaHome 必须正好指向含这个文件的那层
```

⇒ 解压后**必须拍平单层目录**，而且判据**不能硬编码 `jbr`**（第一版就写死成
`it.name == "jbr"`，实测不匹配 ⇒ 报「找不到 release 文件」）。
正确判据是「顶层只有一个条目、它是目录、且它自己没有 `release`」。

### 2.3 实测 sha256（本仓库记录值）

```
jbrsdk-21.0.8-windows-x64-b1163.62.zip
  sha256 = 432d0f9bdc687a6c8e2e13e22be83cdb0d9460b6b15752e84bd97165e13e9f5f

jbrsdk-21.0.8-linux-x64-b1163.62.tar.gz
  sha256 = 482b63da8ac63b8f108878d1bd8a23df15fd78cc9dfd23f3b824fbfe2512c81f
```

⚠️ 这两个值是从 `cache-redirector` **实际下载后 `sha256sum` 算出来的**，
不是从别处抄的。升级 JBR 版本时**必须重新下载重算**，不能沿用。
（旧值 `2270…938` / `34a7…fa8e` 对应裸 `jbr-` JRE 包，因 §2.2.0 的原因已弃用。）

---

## 3. 落地（**已实现**，见本文件末尾的「实际改动」）

### 3.1 `app/build.gradle.kts`：解析 + 下载 + 校验

实现在 `appDescription` 之后、`compose.desktop { }` 之前，共三个部分：

- `resolveJbrHome()` —— 按 **`-Pcp.jbrHome` → `CP_JBR_HOME` 环境变量 → 仓库内
  `.jbr/<platform>-x64/`** 的顺序解析，命中的判据统一是「该目录下有 `release` 文件」；
- `ensureJbrDownloaded()` —— 仅当 `-Pcpcp.jbrDownload=true` 且没解析到时才跑；
- `compose.desktop.application` 里 `resolvedJbrHome?.let { javaHome = it.absolutePath }`，
  两种情况**都打一行 lifecycle 日志**，让「到底换没换」在输出里一眼可见。

⚠️ **默认关（`cp.jbrDownload` 不设 = 不下载）**：本地不带开关时构建完全不受影响，
不会突然卡 90MB 下载。CI 走的是「gradlew 之前独立 step 下载好」那条路。

### 3.2 三个只有真跑起来才会暴露的 Kotlin DSL 坑

这三处都是**实测报错后改的**，不是预防性写法，改的时候别退回去：

| 写法 | 报错 | 正确做法 |
|---|---|---|
| `private const val JbrVersion = …` | `Const 'val' is only allowed on top level, in named objects, or in companion objects` | 用 `private val` —— Kotlin 脚本体的顶层是**语句块**，不是类/文件顶层 |
| `exec { commandLine(…) }` / `project.exec { }` | `Unresolved reference 'exec'` / `'commandLine'` | **别 shell 出去**：改用 Gradle 运行时已带的 `commons-compress` 纯 JVM 解压 |
| `project.extensions.getByType(ExecOperations::class.java)` | `Extension of type 'ExecOperations' does not exist` | `ExecOperations` 只能**构造器注入**（需 inner class），不能当 extension 取 |

顺带：`TarArchiveInputStream` 解 tar 时必须**自己防 tar-slip** ——
校验 `out.canonicalPath` 仍在 `targetDir` 之内。压缩包来自网络，这一步不能省。

### 3.3 CI（`.github/workflows/desktop-release.yml`）—— 已实现

- `env` 加 `JBR_VERSION` / `JBR_BUILD` / `JBR_URL_BASE`；
- `matrix` 每腿加 `jbrExt`（windows=`zip` / linux=`tar.gz`）与 `jbrSha`；
- steps 顺序：`Cache JBR`（`actions/cache@v4`，key 含 sha256 ⇒ 换版本自动失效）
  → `Fetch JBR`（curl → sha256sum -c → 解压 → 剥层 → **`test -f $dest/release` 兜底断言**
  → 打印 `java -version` 供日志留痕）
  → `Build desktop package`（多传一个 `-Pcp.jbrHome=…`）。

⚠️ **`actions/setup-java` 仍然保留**：Gradle 守护进程、Kotlin 编译、测试都跑在 temurin 上；
JBR 只是**被打进产物**的运行时。两者不是二选一。

---

## 4. 换完之后必须复查的两处（**这是本方案真正的风险点**）

### 4.1 `DesktopTitleBar` 的拖拽区边界（`app/src/desktopMain/.../DesktopTitleBar.kt`）

那份 KDoc 第 158 行起写着：

> `WindowDraggableArea` 在**非 JBR 运行时**（本项目当前用 Zulu）走 `StandardMoveHandler`：
> 它给 AWT 窗口挂一个 `MouseListener`，在 `mouseDragged` 里直接移动窗口。这层在 AWT 上，
> **Compose 消费不掉**，而且**不认 Compose 的命中测试** —— 只要「按下的那一点」落在拖拽区内，
> 之后整段拖动都会移动窗口。

换成 JBR 后走的是 `JbrMoveHandler`（原生 `WindowMove`），**命中测试的语义变了**。
当时「返回键 / 账号 / 消息 / 设置 / 窗口控制必须全部留在拖拽区外面」这个约束，
是为了绕开 AWT 层不认 Compose 命中测试而设的；在 JBR 下是否仍然必要、
拖拽区与搜索框的**边界是否需要重画**，**必须真机核对**，不能靠推断。

⇒ 换 JBR 的 PR 里必须带上**这四条的真机验证**（截图或录屏）：
1. 按在标题文字上拖动 ⇒ 窗口跟手；
2. 按在返回键/账号/消息/设置/窗口控制上 ⇒ **只触发按钮，窗口不动**；
3. 搜索框左右**紧邻的空白**能拖动（这两块是独立拖拽区，见 `TitleBarSearch`）；
4. 搜索框**内部**按下滑动 ⇒ 框内行为，窗口不动。

### 4.2 `onTitleBarDoubleClick` 的 `PointerEventPass.Final`

同一文件第 299 行起的 `onTitleBarDoubleClick` 用 `Final` pass，
注释里的理由是「只观察、不消费，否则会和拖拽抢事件」—— 那是针对 AWT `MouseListener` 的。
JBR 的 `WindowMove` 是**原生**接管，双层事件模型变了之后，
双击最大化是否还会被原生拖动抢先、`Final` 是否还够，**也要在真机确认**。

### 4.3 顺手可以删掉的 TODO

`DesktopTitleBar.kt` 第 325 行有个 `TODO(下一步)`：
「实现 Windows 的『拖出标题栏即还原并跟随光标』。**这条不建议手写**……
换成 JBR 运行时会由原生 `WindowMove` 一并解决」。

换 JBR 之后这条 TODO 应该**验证后再删** —— 如果原生 `WindowMove` 真的提供了
拖出还原，就把 `WindowDragRegion` 的 `enabled = !maximized` 那段重新想一遍
（现在是「最大化时禁用拖拽」，因为软件移动没有拖出还原语义；
原生移动有的话，最大化时也应该允许拖动）。

---

## 5. 回退开关（**换 JBR 之前就该先做**）

`LOG.md`（2026-09-30）里已经把这条标成「从未做」，换 JBR 会放大它的必要性：

> 无边框回退开关（我最初推荐的「自救通道」）—— 一旦无边框在某台机器上出问题
> （窗口移不动），用户进不去设置页改回来。

现在更糟：**如果 JBR 的拖拽在某台机器上出问题，那是打进包里的运行时的问题，
用户没有任何开关可调**。所以顺序应该是：

1. **先**照 `DesktopRenderTuning` 那套「JVM 属性 / 环境变量 + 持久化项」做出
   「无边框 / 有边框」或「JBR 移动 / 软件移动」的回退开关；
2. **再**换 JBR。

`JbrMoveHandler` vs `StandardMoveHandler` 的选择点在 Compose 内部
（`WindowDraggableArea_desktopKt` 调 `com.jetbrains.JBR.isWindowMoveSupported()`），
应用侧改不了。所以回退只能做在**窗口装饰**这一层（有边框 ⇒ 用系统标题栏 ⇒
完全不依赖 `WindowDraggableArea`），这也正好和 §5 引文里那条对得上。

---

## 6. 验收清单

换 JBR 之后**必须逐条过**，别只看构建绿了：

| # | 项 | 判据 | 本次已验 |
|---|---|---|---|
| 1 | JBR 下载可复现 | 删 `.jbr/` 重跑 `-Pcp.jbrDownload=true`，落到 `.jbr/windows-x64/` | ✅ |
| 2 | 运行时确实是 JBR | `.jbr/<plat>-x64/bin/java -version` 输出含 `JBR-21.0.8+9-1163.62-nomod`、`release` 里 `IMPLEMENTOR="JetBrains s.r.o."` | ✅ |
| 3 | 幂等 | 第二次 configure **不重新下载**（实测 27s → 2s） | ✅ |
| 4 | **sha256 不匹配会拦下** | 故意改一位 ⇒ 构建失败 + 报出期望/实得 + **临时文件被删** + 无残留 `.jbr/` | ✅ |
| 5 | 拍平单层目录 | 顶层只有 `bin/ conf/ include/ legal/ lib/ release`，没有嵌套的版本号目录 | ✅ |
| 6 | `javaHome` 真生效 | jpackage 日志/产物里 runtime image 指向 `.jbr/`；`-Dcp.player.*` 元数据不退化 | ⬜ 需真机打包 |
| 7 | **原生拖动生效** | 拖动跟手、贴边有吸附、拖出屏幕边缘能还原（对比换之前） | ⬜ 需真机 |
| 8 | §4.1 四条边界 | 标题拖动 / 按钮不吃拖动 / 搜索框旁空白可拖 / 框内不拖 —— 逐条截图或录屏 | ⬜ 需真机 |
| 9 | 双击最大化不退化 | 原本就有的 `onTitleBarDoubleClick` 行为不变 | ⬜ 需真机 |
| 10 | 全量回归 | `:app:desktopTest` + `:core:desktopTest`，`skipped="0"` 且 failures/errors 全 0 | ✅ |

> ⬜ 的六项都在「真机打包 + 人工操作」这一侧，**代码层面量不到** ——
> 尤其 7/8/9 是 §4 说的事件模型变更，必须上手试。

## 7. 参考

- jbr-api 已在 classpath：`org.jetbrains.runtime:jbr-api:1.9.0`
  （`compose.desktop.currentOs` 的传递依赖），所以 `com.jetbrains.JBR` 直接可用，
  **不需要额外声明依赖**。已用 `javap` 核实 `isAvailable()` / `getApiVersion()` /
  `isWindowMoveSupported()` 三个方法存在。
- `WindowDraggableArea_desktopKt` 的字节码已核实走
  `com/jetbrains/JBR.isWindowMoveSupported:()Z` 二选一：
  `JbrMoveHandler(window)` / `StandardMoveHandler(window)`。
- **解 tar 用的是 Gradle 运行时自带的 `commons-compress`**（`gradle-9.4.1/lib/` 里就有），
  所以 build script 直接 `import org.apache.commons.compress.archivers.tar.TarArchiveInputStream`
  即可，不需要在 `settings.gradle.kts` 里额外加构建脚本依赖。
- 更保真但更贵的一条路（**本次不走**）：JBR 的
  `WindowDecorations.setCustomTitleBar(frame, bar)` —— 保留原生边框、只换标题栏，
  吸附 / 圆角 / 投影 / Snap Layouts / 原生缩放全保留。但它要求 `undecorated = false`，
  等于把现在的 `UndecoratedWindowResizer`、DWM 圆角调用、拖拽区结构**全部作废**，
  是一次重构而不是开关（见 `LOG.md` 2026-09-30）。

---

## 8. `desktopRun` 的运行时陷阱（2026-10-04 实锤）

**现象**：装了 JBR，`desktopRun` 下窗口照样没有贴边吸附 / Snap Layouts，
`JbrWindowChrome.isSupported` 为 false（启动日志打「未检测到可用的 JBR 自定义标题栏」）。

**根因（只读探针实测，`gradlew :app:help` + init 脚本打印任务属性）**：

| 任务 | 类型 | 运行时 |
|---|---|---|
| `:app:run` | `JavaExec`（compose 插件） | ✅ JBR —— 插件把 `javaLauncher` 指到 `compose.desktop.application.javaHome` |
| `:app:desktopRun` | **`KotlinJvmRun`（KGP 的任务，不是 compose 的）** | ❌ 守护进程 JDK —— `javaLauncher.convention(launcherFor(toolchain))`（`KotlinJvmRun.kt:121`，javap 实锤） |
| `:app:runDistributable` | `AbstractRunDistributableTask` | ✅ 打包产物自带运行时 |

`WindowDecorations` 只在 JBR 里 ⇒ `desktopRun` 下永远走 `Undecorated` 无边框模拟路。

**修法**（已落在 `app/build.gradle.kts` 的 afterEvaluate）：把 `javaLauncher` 的
**value** 设成自定义 `JavaLauncher`（`executablePath` 指向 `.jbr` 的 `bin/java`）。
三个必须知道的坑：

1. **不能 `setExecutable` 完事**：launcher 在场时 JavaExec 优先用它，executable 被忽略；
2. **不能 `set(null)` / `convention(null)` 清 launcher**：Gradle 9 上与 KGP 创建任务时的
   `convention()` 撞出 `property 'javaLauncher' is final`（堆栈落在
   `KotlinJvmRunKt$registerKotlinJvmRun$1.execute`）—— value 方案不碰 convention，
   与 KGP 谁先谁后都安全（value 永远压过 convention）；
3. **不能写 `executable = …`（Kotlin 属性赋值）**：JavaExec 的 setter 是
   `setExecutable(Object)`，与 String getter 不配对，Kotlin 不合成属性，必须
   `setExecutable(...)`。同理 `JavaLauncher` 在 `org.gradle.jvm.toolchain` 包。

`hotRunDesktop` 的 launcher 被热重载插件 final 化，改不动——`runCatching` 跳过并 warn。
验证判据：`.jbr` 存在时，`desktopRun` 启动控制台**不再出现**「未检测到可用的 JBR」；
拖动标题栏贴屏幕边缘有吸附、最大化钮悬停出 Snap Layouts。

### 8.1 三态回退开关（§5 遗留项，已补）

⚠️ **代价先说**：`desktopRun` 上 JBR 后窗口走 `SystemDefault`（保留系统边框），
不再是 `Undecorated(6dp)` 自绘无边框 —— 两套外观只能选一套。所以有
`WindowDecorChoice`（`platform/WindowDecorChoice.kt`）：

- **来源优先级**：`-Dcp.player.windowDecor=` > 环境变量 `CPPLAYER_WINDOW_DECOR` >
  `~/.cpplayer/cp_player_prefs.properties` 的 `window_decor` > auto；
- 取值 `auto` / `jbr`（别名 system/native）/ `undecorated`（别名 borderless/off）；
- **auto** = JBR 可用则走 JBR 路，否则无边框路（原有行为）；
- 判路结果打印在启动日志：`[CPPlayer] 窗口装饰 = …（来源：…）→ …`。

想把外观永久切回无边框：往 `cp_player_prefs.properties` 加一行
`window_decor=undecorated`（写入时**先关掉正在运行的 CPPlayer**，
否则退出时的持久化可能把手工加的行冲掉）；想回 JBR/吸附路就删掉这行或写 `jbr`。

---

## 附：本次实际改动的文件

| 文件 | 改了什么 |
|---|---|
| `app/build.gradle.kts` | 新增 JBR 解析 / 下载 / 校验 / 解压（含 tar-slip 防护），`compose.desktop.application` 里条件设 `javaHome` 并打状态日志 |
| `.github/workflows/desktop-release.yml` | `env` 加 JBR 坐标；matrix 加 `jbrExt`/`jbrSha`；新增 `Cache JBR` + `Fetch JBR` step（含 sha256 校验与布局断言）；build step 传 `-Pcp.jbrHome` |
| `.gitignore` | 忽略 `.jbr/` |
| `docs/dev/JBR_PACKAGING.md` | 本文件（新建） |

**尚未做**（都是刻意留到换 JBR 之前的独立步骤，见 §5）：
1. 窗口装饰回退开关（「无边框 / 有边框」或「JBR 移动 / 软件移动」）；
2. `DesktopTitleBar.kt` 里两处 KDoc 的措辞更新 + 那条 `TODO(下一步)` 的销账
   —— **要等真机验证过 §4.1/4.3 之后**再改，改早了就是把未验证的结论写进注释；
3. 把 JBR 数字进产物的**体积影响**记进 `docs/dev/RELEASE.md`（MSI 会从 ~60MB 涨到 ~90MB+）。
