# app/graalvm — 项目级 Native Image 元数据

这个目录是 `nucleus { application { graalvm { nativeImageConfigBaseDir } } }` 的默认值，
构建时被原样传给 native-image：
`-H:ConfigurationFileDirectories=<项目>/app/graalvm`（见 `build/compose/tmp/main/graalvm/native-image-args.txt`）。
其中 `pgo/` 是 PGO profile，**不是**元数据；`reachability-metadata.json` 才是。

放什么：**只有在 native image 运行时才会被用到、静态分析看不见的注册项**。
JDK / 第三方库里能被静态分析到的（普通调用、接口实现）不用写。

放什么之前先想清楚代价：**这里漏一条 = 构建照常成功、运行期某个功能突然崩**，
而且崩的位置往往离真正的原因很远（见下面第 1 条）。所以每条都要写清「为什么」和「怎么验证的」。

## 现有条目与来历

| 条目 | 为什么必须写 |
| --- | --- |
| `resources`: `com/ibm/icu/impl/data/icudata/**`、`fonts/*`、`font/*`、`composeResources/**`、`META-INF/services/*` | ICU 的完整断行规则、语言包与字符归一化数据；桌面端内置中日文字体与通用 Compose 资源；以及 Coil 3.x 等通过 `ServiceLoader` 动态加载解码器与网络 Fetcher（`coil3.util.FetcherServiceLoaderTarget`、`coil3.util.DecoderServiceLoaderTarget`）的元服务描述符文件，必须打包进原生镜像中，否则 Native Image 运行时无法通过 ServiceLoader 发现网络抓取与解码实现。 |
| `reflection`: `coil3.*` 与 `org.jetbrains.skiko.renderer.Direct3DRenderer` | Coil 3 ServiceLoader 反射实例化目标（`OkHttpNetworkFetcherServiceLoaderTarget`、`SkiaImageDecoder$Factory`）以及 Skiko 0.156 新包名下的渲染器类与构造。 |
| `jni`: `cp.player.core.playback.DesktopHiResEngine` 与 `org.jetbrains.skiko.renderer.*` | 桌面端 FlickEngine 音频引擎声明了 27 个 `external` (JNI) 原生方法；Skiko 0.156 将渲染层由旧 `org.jetbrains.skiko.redrawer.*` 重构成 `org.jetbrains.skiko.renderer.*`，`Direct3DRenderer` 承载了 Direct3D 12 渲染与 Live Resize 相关的 17 个 JNI 原生方法（含 C++ 回调 Java 的 `isAdapterSupported` 及 `installLiveResizeHook` 等），必须登记 JNI 可达性，否则 Native Image 在渲染启动或窗口缩放时抛 JNI 符号缺失错误。 |
| `foreign.downcalls`（31 条，新增 SMTC / FFM / libsentry / Tao 等 8 条） | **FFM（Project Panama）的每个 downcall 签名都必须在构建期登记**，否则运行时 `Linker.downcallHandle` 抛 `MissingForeignRegistrationError`。新增条目包括：① `SetEnvironmentVariableW` 与 COM 字符/实例绑定的 `jint(void*, void*)`；② `RoInitialize` 的 `jint(jint)`；③ SMTC 启用按钮的 `jint(void*, jbyte)`；④ SMTC 窗口绑定的 `jint(void*, jlong, void*, void*)`；⑤ SMTC 进度条时间线 `jint(void*, jlong)`；⑥ `SystemParametersInfoW` 工作区计算的 `jint(jint, jint, void*, jint)`；⑦ libsentry 初始化的 `void*()` 与 `void(void*, void*)`。漏一条 ⇒ 功能静默失效。类型名按 GraalVM 的 C 写法：`jint`/`jlong`/`jbyte`/`void`/`void*`。 |

### 关于 `foreign.downcalls` 那次事故（2026-11-14）

症状：Windows 原生包里导入网易云 1.3.0（DLL 确实导出 5 个 `cpp_*`，`dumpbin` 核过）
仍然提示「只导出了 JNI 符号，没有导出 C ABI」。

排查：用同一份 GraalVM 编了个只做 FFM 调用的迷你镜像，输出是

```
imagecode=runtime
shared=OK   auto=OK                      ← Arena 不是问题（不需要 SharedArenaSupport）
lookup_FAIL=org.graalvm.nativeimage.MissingForeignRegistrationError:
  Cannot perform downcall with leaf type (long)int.
```

也就是说 `Arena.ofShared()` / `SymbolLookup.libraryLookup(...)` 都正常，**卡在
`linker.downcallHandle(...)` 的构建期登记**上。补上 `foreign.downcalls` 后同一镜像：
`DOWNLOADS_OK abi=1` → `CREATE handle=...` → `INVOKE code=0 len=3383` → `CLOSE ok`。

两点教训：① 这类「JVM 上跑得好好的 FFM 代码」在镜像里可能根本没被登记过，
必须用迷你镜像验一次；② 探测失败的原因不能吞掉（`catch (_: Throwable) { null }`），
否则错误信息会把方向指到音源仓库 —— 现在已经把它带进报错文本里了。

### 第二起：原生包里没有 Win11 圆角 / 窗口细边框（同日）

症状：原生包里窗口是直角、也没有 Win11 那圈 1px 细边框；JVM 版（`desktopRun`）正常。
`WindowsWindowCorners` 明明在跑，`DwmSetWindowAttribute` 却像没生效。

真因同一类：`WindowsWindowCorners` 的 7 个 downcall 一个都没登记 ⇒ 每次
`downcallHandle` 直接抛 `MissingForeignRegistrationError` ⇒ 被 `runCatching` 吞掉
⇒ 50 次重试全是同一个异常 ⇒ 窗口停在 Tao 写死的 `DWMWCP_DONOTROUND`（读回是 1）。
**「圆角」「那圈半透明细边框」都是 DWM 画的，只在 `DWMWCP_ROUND` 下才画**，
所以两样一起消失。

验证（迷你镜像 + 线上窗口，不是推理）：

```
未登记        DOWN_JINT_VOID_FAIL / DOWN_GETTOPWINDOW_FAIL / DOWN_DWMSET_FAIL
              org.graalvm.nativeimage.MissingForeignRegistrationError（连 int(void) 都抛）
补 5 条签名   BIND_OK 7/7
              SET hwnd=0x33104b4 1471x958 hr=0 readback=2
              CHANGED=1        ← 真窗口的 DWM 偏好从 1 变 2
```

改完的窗口实测：`DwmGetWindowAttribute(33)` = 2；左上角逐行起始 x 出现 8px 圆弧；
上边缘第一行比内容亮一档（`25252B` vs `1B1B21`，深色主题下 DWM 的半透明描边）。

⚠️ 反面教材：描述符写错（`GetTopWindow` 少写 HWND 参数）时，JVM 会抛
`WrongMethodTypeException`，**原生镜像里却静默返回 0**（C 函数收到垃圾参数）。
所以「镜像里没报错」不等于签名对，必须看返回值/读回值。

### 第三组：用 NVAPI DRS 关掉「本程序」的 VRR（同日）

机制：`nvapi_QueryInterface(id)` 拿函数指针 → DRS `CreateSession` / `LoadSettings` →
按 exe 路径 `FindApplicationByName`（找不到就 `CreateProfile` + `CreateApplication`）→
`VRR_APP_OVERRIDE_ID`（0x10A879CF）设成 `VRR_APP_OVERRIDE_FIXED_REFRESH`（4，即控制面板里的
「固定刷新率」）→ `SaveSettings`。实现见 `app/src/desktopMain/.../WindowsNvapiVrr.kt`。

实测（本机 RTX 3070 / 驱动 616.56 / **普通用户权限**）：`DRS_SaveSettings` 返回 `NVAPI_OK`，
不需要管理员。探针在 **JVM 与 `-Ob` 原生镜像**里都跑过，镜像里的输出：

```
QUERY_OK 13/13
GetModuleFileNameW -> 56 C:\...\nvprobe.exe
Initialize -> 0 NVAPI_OK        DRS_CreateSession -> 0 NVAPI_OK
DRS_FindApplicationByName(...) -> 0 NVAPI_OK profile=...
GET VRR_APP_OVERRIDE -> type=0 location=0 current=4
DRS_SetSetting(4) -> 0 NVAPI_OK DRS_SaveSettings -> 0 NVAPI_OK   VERIFY current=4
```

这条功能**只在原生镜像里跑**：开发运行时进程是 `java.exe`，按 exe 路径建配置会污染
java.exe 的驱动配置。另外驱动只在**进程启动时**读 profile ⇒ 写入后下次启动才生效。

### 第四组：删掉 AWT，选择器改走 Win32 Common Item Dialog（同日）

症状：原生包里点「选择目录」直接崩：

```
java.lang.Error: Could not instantiate Shell Folder Manager: sun.awt.shell.ShellFolderManager
  at sun.awt.shell.ShellFolder.<clinit>(ShellFolder.java:246)
  at javax.swing.filechooser.FileSystemView.getDefaultDirectory(...)
  at javax.swing.JFileChooser.<init>(...)
```

根因是两处叠加，不是一条元数据能补完的：`ShellFolder.<clinit>` 用 `Class.forName`
反射加载 `sun.awt.shell.Win32ShellFolderManager2`（动态字符串，静态分析看不见），
失败后静默回落到基类 `ShellFolderManager`，而基类的无参构造同样没登记反射；
用 `-Ob` 迷你镜像补完这两条后又撞第二处
`java.lang.Error: no ComponentUI class for: javax.swing.JFileChooser`（`UIDefaults.getUI`
拿不到 `WindowsFileChooserUI`）。而且 AWT `FileDialog` 弹的是 Win95 风格旧对话框，
本来就不满足 Windows 10/11 现代体验。

**决策：不再给 AWT 堆元数据，直接把依赖换掉。** `desktopMain` 里所有
`java.awt` / `javax.swing` / `javax.imageio` 引用移除：

| 功能 | 现在的实现 |
| --- | --- |
| 选 zip / 存 zip / 选目录 | `Win32CommonItemDialog`：FFM 调 COM `IFileDialog`，`FOS_PICKFOLDERS` 选目录；非 Windows 用 zenity / osascript |
| 剪贴板 | `Win32Clipboard`：FFM 调 `OpenClipboard` / `SetClipboardData(CF_UNICODETEXT)`；非 Windows 用 xclip / pbcopy |
| 打开链接 / 文件夹 / 安装包 | `rundll32 url.dll,FileProtocolHandler` / `explorer.exe` / `xdg-open` / `open` |
| 刷新率 | `EnumDisplaySettingsW` 读 `DEVMODEW.dmDisplayFrequency`（偏移 184） |
| 壁纸取色 / 图标尺寸 | Skia 解码、逐行 `readPixels` 抽样；PNG IHDR 直接读字节 |
| 测试截图断言 | 共享的 `readPng`（Skia 解码），不再用 `ImageIO` / `BufferedImage` |

`WFileDialogPeer` / `java.awt.FileDialog` 两条反射登记随之删除。

验证（`-Ob` 迷你镜像，只建 COM 对象、不弹模态框，避免自动化里卡在 `Show`）：

```
COINIT_HR=0 OK          CREATE_HR=0 OK
SET_FILE_TYPES_HR=0 OK  SET_OPTIONS_HR=0 OK
GET_OPTIONS_HR=0 OK     OPTIONS_READBACK=1868 expected=868
SET_TITLE_HR=0 OK       SET_FILE_NAME_HR=0 OK
GET_FOLDER_HR=0 OK      GET_DISPLAY_NAME_HR=0 OK   PATH=D:\Doc
ITEM_RELEASE_HR=0 OK    DIALOG_RELEASE_HR=0 OK
```

两个坑：① `SetFileTypes` 必须在设置 `FOS_PICKFOLDERS` **之前**调用，文件夹模式下
shell 会返回 `E_INVALIDARG`（-2147418113 / 0x80070057）；② `GetOptions` 读回会多出
`0x1000`（shell 强制加的位），判据是「自己设置的位是否都在」，不是「完全相等」。
vtable 索引按 Windows SDK `shobjidl_core.h` 逐条核对，别凭印象写。

## 加条目之前先做探针（别直接改主构建）
主构建（PGO + -O3）一次十几分钟，而且崩了只能靠猜。做法是：
用**同一份 GraalVM**（`~/.gradle/nucleus/graalvm/...`）+ 主构建生成的
`build/compose/tmp/main/graalvm/{platformMetadata,libraryMetadata}` 两份元数据目录，
编一个只调用目标 API 的迷你程序（`-Ob`，约 1 分钟），把这份
`reachability-metadata.json` 一起传进去：

```
native-image -cp . Probe -o probe \
  -H:ConfigurationFileDirectories=<...>/platformMetadata \
  -H:ConfigurationFileDirectories=<...>/libraryMetadata \
  -H:ConfigurationFileDirectories=E:/project/CPPlayer-KMP/app/graalvm \
  --enable-native-access=ALL-UNNAMED -Ob --no-fallback
```

2026-11-14 第一批（反射 / JNI）探针输出是 `PEER_OK` / `LOCALE_OK zh_CN` / `BI_OK 0,1,2,…`；
第四批（COM `IFileDialog`）探针只传 `app/graalvm` 一份元数据即可——应用代码已经不碰
AWT，不需要 `platformMetadata` / `libraryMetadata`，也不再需要
`--initialize-at-run-time=sun.awt.windows,sun.font`。

两次事故都是「先有真镜像输出，再改仓」；反过来（改完仓等主构建）一次十几分钟还可能猜错方向。

## 不要做的事

- 不要把这些写进 `app/src/*/resources/META-INF/native-image/...`：本项目走
  `-jar` + `ConfigurationFileDirectories`，集中在这一处才看得见、改得动。
- 不要因为某个 API 在镜像里报缺失就**只**加 try/catch 把异常吞掉，
  先按上面查清是哪一层少了登记。
- 但如果某个依赖本身在镜像里需要**成串**补登记（AWT / Swing 文件选择器就是这样：
  补完 `ShellFolder` 还有 `UIDefaults`），正确做法是换掉这条依赖，而不是继续堆元数据
  —— 见「第四组」。换实现不是降级：功能必须等价或更好。
