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
| `sun.awt.windows.WFileDialogPeer`（`parent` / `fileFilter` 字段 + `setHWnd` / `handleSelected` / `handleCancel` / `checkFilenameFilter` / `isMultipleMode`，`jniAccessible`） | awt.dll 的 `Java_sun_awt_windows_WFileDialogPeer_initIDs` 在**类初始化**时就用 `GetFieldID` / `GetMethodID` 取这些成员。native-image 只把「Java 代码用到的」成员编进镜像，`parent` 这种**只有 C 代码读**的字段、`handleSelected` 这种**只被 C 代码回调**的方法原本不在镜像里 ⇒ `java.awt.FileDialog.setVisible(true)` 时抛 `NoSuchFieldError: sun.awt.windows.WFileDialogPeer.parent`（表现：导入音源弹选择器直接崩）。 |
| `java.awt.FileDialog`（`mode` / `dir` / `file` / `filter` 字段，`jniAccessible`） | 同一次 `initIDs()` 里对 `java.awt.FileDialog` 取字段 ID（`mode` 决定 LOAD/SAVE）。前一条修好之后，下一批就轮到它。 |
| `java.util.Locale.getDefault(Locale$Category)` / `setDefault(Locale$Category, Locale)` | ICU4J 的 `ULocale$JDKLocaleHelper` 用**反射**调 JDK 7+ 的分类 Locale API。`ULocale.<clinit>` 在第一次 `BreakIterator` 调用时就跑 ⇒ 歌词页一进来就 `MissingReflectionRegistrationError`。 |
| `com.ibm.icu.text.BreakIteratorFactory` `<init>` | ICU 的 `BreakIterator.getShim()` 用 `Class.forName("com.ibm.icu.text.BreakIteratorFactory")` + `Class.newInstance()` 拿实现类。纯反射，静态分析看不见。 |
| `resources`: `com/ibm/icu/impl/data/icudata/brkitr/*`、`com/ibm/icu/impl/data/icudata/*.res` | ICU 的断行规则（`brkitr/*.res`，含中日韩泰等字典）与语言包（顶层 `*.res`）都是运行时 `getResourceAsStream` 读的；不登记就是 `MissingResourceException`。**只登记这两个 glob（约 7 MB）**，不登记 `icudata` 下 `curr/zone/region/lang/unit/coll/rbnf/translit` 子目录（约 19 MB）—— 本项目只用 ICU 做断行，不碰它的格式化/排序数据。 |

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
  --initialize-at-run-time=sun.awt.windows,sun.font -Ob --no-fallback
```

2026-11-14 修这一批时，探针的输出是 `PEER_OK` / `LOCALE_OK zh_CN` / `BI_OK 0,1,2,…`，
即三条都在真镜像里跑通之后才改仓。

⚠️ 探针里如果只传 `platformMetadata` 不传 `libraryMetadata`，会在 AWT 初始化时先撞上
`NoClassDefFoundError: sun/java2d/SurfaceData`（`WToolkit.<clinit>` 的 `FindClass`）——
那是探针少传了元数据，不是新的 bug。

## 不要做的事

- 不要把这些写进 `app/src/*/resources/META-INF/native-image/...`：本项目走
  `-jar` + `ConfigurationFileDirectories`，集中在这一处才看得见、改得动。
- 不要因为某个 API 在镜像里报缺失就去改运行时代码（加 try/catch、降级），
  先按上面查清是哪一层少了登记。
