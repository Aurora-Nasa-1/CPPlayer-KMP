# 原生 Provider

> 原生库的两条 ABI：JNI 符号、Wire 字节格式、C ABI v1。适用读者：原生音源作者。
> 契约版本 `kmp-sdk-v1` · 更新 2026-11-14 · 事实源 `provider-sdk-kmp/src/commonMain/kotlin/cp/player/sdk/Wire.kt`、`NativeWireCodec.generated.kt`、`jni/NativeSession.kt`

## 1. 两条 ABI 的分工

| 运行环境 | 使用 | 原因 |
|---|---|---|
| JVM 桌面、Android | JNI | 每包 `defineClass` 一份 `NativeSession`，绑定天然独立 |
| GraalVM Native Image | C ABI v1 | 镜像里类文件不是可读资源，且不支持运行时定义类 |

JNI 绑定挂在类对象上。宿主按包各自 `defineClass` 一次 `cp.player.sdk.jni.NativeSession`（`core/src/desktopMain/.../typed/NativePackageClassLoader.kt`），类对象不同，绑定才不同。

原生镜像下这两步都不成立（2026-11-14，GraalVM 25.3.4.1 迷你镜像实测）：

| 事实 | 后果 |
|---|---|
| `ClassLoader.getResource("任意类.class")` 恒为 `null` | 无法读类字节 |
| 运行时不支持定义新类 | 类布局构建期静态确定 |

C ABI 改按符号名直调：每包一个动态库句柄，绑定天然独立，不需要 `defineClass`、不需要 `JNIEnv*`，JVM 与原生镜像共用宿主代码。

**建议两套都导出。** JVM 上行为不变；原生构建才有路可走。只导出 JNI 的老包在原生构建里明确报错并提示升级，或改用 JS 音源 / JVM 版客户端。

## 2. JNI 导出符号

宿主对每个包 `System.load(<包内库的独立快照>)`，然后反射这 4 个符号。名字必须精确；`JNIEnv*` 与接收者（`jclass`）由 JNI 隐式传入。

| 符号 | 参数 | 返回 |
|---|---|---|
| `Java_cp_player_sdk_jni_NativeSession_createNative` | `jstring providerId, jstring accountId, jstring credential, jstring settingsJson` | `jlong` 句柄；返回 0 视为创建失败 |
| `Java_cp_player_sdk_jni_NativeSession_invokeNative` | `jlong handle, jint operation, jbyteArray request` | `jbyteArray`；可为 `null`，宿主按截断失败处理 |
| `Java_cp_player_sdk_jni_NativeSession_cancelNative` | `jlong handle` | `void`；线程安全、立即返回、不得释放句柄 |
| `Java_cp_player_sdk_jni_NativeSession_closeNative` | `jlong handle` | `void`；调用已排空，只调用一次 |

`credential` 可能为 `null`。`settingsJson` 是设置快照的 JSON 文本，不是对象。

## 3. Wire 格式

### 3.1 基本类型

小端、定长、无对齐填充、无 varint、无字段名、无版本号。

| 类型 | 编码 |
|---|---|
| `Int` | `i32`，4 字节小端 |
| `Long` | `i64`，8 字节小端 |
| `Boolean` | 1 字节 `0` / `1`；其他值直接判非法 |
| `String` | `i32` 字节长度 + UTF-8 原始字节（长度是字节数，不是字符数） |
| `ByteArray` | `i32` 长度 + 原始字节 |
| `T?` | 1 字节存在位（同 bool）+ 存在时的值 |
| `List<T>` | `i32` 元素个数 + 依次编码的元素 |

记录是纯位置序列化：字段顺序就是 [SDK_TYPES](SDK_TYPES.md) 表格的行顺序。不能跳过、不能换序、只能在末尾追加。

### 3.2 响应封套

```text
[1 字节 bool] [载荷]
```

| 首字节 | 含义 |
|---|---|
| `0` | 成功，后接该操作的响应 DTO |
| `1` | 失败，后接 `Failure`（`category: String` + `message: String`） |

失败类别用 `Unsupported` / `NotFound` / `Authentication` / `Network` / `RateLimit` / `Timeout` / `InvalidResponse` / `Runtime`。`category` 须非空白且 ≤128 字符，`message` ≤4096 字符；超出时宿主改写成 `InvalidResponse("Invalid provider failure")`。

### 3.3 操作码

`operation` 即 [SDK_OPERATIONS](SDK_OPERATIONS.md) 第一列的 tag，`2..166` 为业务操作。请求按该操作的请求 DTO 编码，响应按响应 DTO 编码。

| 操作码 | 含义 | 要求 |
|---|---|---|
| `1` | `CAPABILITIES` | 原生包必须实现：请求 `Bytes`，响应 `Capabilities { supported: List<String> }` |
| `2..166` | 业务操作 | 按 tag 编码 |
| `6..9` | `HTTP` / `SHA256` / `STORAGEGET` / `STORAGESET` | SDK 有 DTO，宿主不向原生包派发 |

宿主每次调用前先取能力表；不实现 `operation = 1`，所有业务调用都会以 `Unsupported` 结束。

`Bytes` 请求（无参操作）编码后是 `i32 0`（四个零字节），不是空数组。

### 3.4 解码后继续校验

这些不是线格式问题，但会让编码正确的响应仍然失败。

| 校验 | 阈值 |
|---|---|
| 整条消息 | ≤ 4 MiB |
| 任何 `List` | ≤ 500 项 |
| 读取结束位置 | 必须刚好读完；多一字节即 `Trailing wire data` |
| `search` | `items.size <= request.limit`；`total` 为 null 或落在 `0..2^53-1` |
| `Track.id` | 非空白且 ≤1024 字符 |
| `Track.title` / `album` | ≤4096 字符 |
| `Track.durationMs` | `0..2^53-1` |
| `Track.artists` | ≤64 项，每项 `name` 非空白 |
| `Track.coverUrl` | 可解析为 `http` / `https` 且有 host |
| `Long` 字段 | 精确安全整数；超过 2^53-1 或非整数被拒 |

### 3.5 两条最容易踩的

| 问题 | 正确做法 |
|---|---|
| 登录态只放内存 | 宿主在 `authenticate` 成功后保存 cookie、换掉会话并重新 `createNative`；`credential` 非空即已登录，用户身份也从凭证推 |
| `getUserPlaylists` 的 `id` 当上游用户 ID | 它可能是宿主账号键（实测 `9084388061`）；按「当前登录用户」处理，不要拿它过滤 `creatorId` |

## 4. C ABI v1

### 4.1 必须导出的符号

五个都要，缺任何一个视为不提供 C ABI。

| 符号 | 签名 | 语义 |
|---|---|---|
| `cpp_abi_version` | `int32_t cpp_abi_version(void)` | 返回 `1`；宿主先用它探测是否支持 C ABI |
| `cpp_create` | `int64_t cpp_create(const char* providerId, const char* accountId, const char* credential, const char* settingsJson, char* err, int32_t errCap)` | 返回非零句柄；失败返回 0，并把 UTF-8 原因写进 `err`（NUL 结尾，最多 `errCap-1` 字节）；`credential` 可为 `NULL` |
| `cpp_invoke` | `int32_t cpp_invoke(int64_t handle, int32_t operation, const uint8_t* req, int32_t reqLen, uint8_t* out, int32_t outCap, int32_t* outLen)` | 见 4.3；请求与响应都是 Wire 字节，不额外加壳 |
| `cpp_cancel` | `void cpp_cancel(int64_t handle)` | 线程安全、立即返回，不释放句柄 |
| `cpp_close` | `void cpp_close(int64_t handle)` | 等 in-flight 调用排空后释放；幂等 |

| 参数约定 | 内容 |
|---|---|
| `char*` | UTF-8 + NUL 结尾；生命周期只覆盖本次调用，Provider 不得保存指针 |
| 长度类型 | `int32_t`；单个 Wire 报文远小于 2 GiB |
| 句柄 | `int64_t` 不透明，`0` 表示无效 |

### 4.2 内存所有权

没有任何内存跨 DLL 边界释放。Windows 下宿主与 Provider 可能用不同 CRT 堆，跨堆 `free` / `delete` 会崩在 native 层，栈里看不出归属。

| 方向 | 规则 |
|---|---|
| host → provider | 字符串与请求字节由 host 分配、host 释放，Provider 只读 |
| provider → host | 不返回指针，写进 host 给的 `out` 缓冲 |
| 需要扩容 | 返回 `1` + `outLen` 让宿主扩容重试，不要先申请再返回 |

### 4.3 返回码

`cpp_invoke` 返回 `int32_t`，`*outLen` 始终回填。

| 返回 | 含义 |
|---|---|
| `0` | 成功，`out[0 until *outLen]` 是响应报文 |
| `1` | `outCap` 不足，`*outLen` 是需要的容量；宿主扩容后重试（`operation` 与 `req` 不变，重试幂等） |
| `2` | ABI 层失败（句柄无效、内部 panic 等）；业务错误不走这里 |

业务错误走 Wire：首字节 `0` / `1` + `Failure`，与 JNI 版一致。`JniMusicProvider` 与 `NativeWireCodec` 无需改动。

### 4.4 线程与生命周期

| 规则 | 内容 |
|---|---|
| 调用线程 | `cpp_invoke` / `cpp_cancel` / `cpp_close` 可从任意线程调用，Provider 内部自管同步 |
| cancel 之后 | 句柄仍有效，必须照常 `cpp_close` |
| close 语义 | 等已开始的 `cpp_invoke` 返回后才真正释放；宿主在 close 前后各调一次 |
| 回调 | 不提供 native → Java upcall；需要推送就沿用轮询或 Wire 的能力查询 |
| 后台线程 | 不要在 `cpp_create` 里留游离线程 |

后台线程的风险：JVM 从不卸载 DLL，所以 JNI 路径下「创建会话时开后台线程预热」是安全的。原生宿主不同 —— `cpp_close` 后最后一个会话关闭，宿主 `Arena.close()`，FFM 据此 `FreeLibrary`；此时仍有线程跑在库内代码里就是进程级 `0xC0000005`，而且发生在用例全绿之后（2026-11-14 酷狗模块实测：两个用例通过，JVM 退出时崩在访问违例）。需要预热请改为惰性，在真正用到的那次 `cpp_invoke` 里同步做。

### 4.5 探测顺序

宿主在 `InstalledNativeSdkProvider` 分流。

| 运行时 | 有 C ABI | 没有 C ABI |
|---|---|---|
| GraalVM Native Image | 走 C ABI | 报错：只导出了 JNI 符号，请升级模块或改用 JS 音源 / JVM 版客户端 |
| JVM 桌面 / Android | 仍走 JNI，不因多导出而切换 | JVM 走 JNI；Android 走 JNI |

只导出 C ABI、不导出 JNI 的库在 JVM 上不可用。宿主只在原生构建里 `libraryLookup`；JVM 上不为探测去加载库，否则 DLL 会钉住快照目录。

### 4.6 Rust 侧骨架

```rust
#[no_mangle]
pub extern "C" fn cpp_abi_version() -> i32 { 1 }

#[no_mangle]
pub unsafe extern "C" fn cpp_create(
    provider_id: *const c_char, account_id: *const c_char,
    credential: *const c_char, settings_json: *const c_char,
    err: *mut c_char, err_cap: i32,
) -> i64 { /* 复用现有 JNI 实现里 create 的那一段 */ }
```

## 5. manifest 不改

`type: "jni"` 依旧表示原生模块，`entryPoint` 依旧指向库文件，`supportedAbis` 依旧是 ABI 白名单。C ABI 能力靠符号探测判断，不新增字段。

理由：manifest 由作者手写，多一个「可选但决定加载行为」的字段就有第二个真相；写错即「明明导出了却报不支持」。符号探测是自证的。

## 6. 宿主实现

| 文件 | 作用 |
|---|---|
| `core/src/jvmMain/.../typed/NativeSdkAbi.kt` | `CAbiSessionFactory` 接口 + `expect probeCAbiSessionFactory(library): CAbiProbe` |
| `core/src/desktopMain/.../typed/CAbiNativeSession.desktop.kt` | FFM 实现：探测 → create / invoke / cancel / close，容量不足重试 |
| `core/src/androidMain/.../typed/CAbiNativeSession.android.kt` | 恒 `CAbiProbe.Unavailable(...)`（ART 没有 `java.lang.foreign`） |
| `core/src/jvmMain/.../typed/InstalledNativeSdkProvider.kt` | 分流：有 C ABI 走它；否则 JVM 回落 JNI、原生构建抛可读错误（带上探测失败原因） |

| 实现要点 | 内容 |
|---|---|
| Arena | 每包一个 `Arena.ofShared()` 持有库句柄（close 时关闭即卸载）；每次调用用 `Arena.ofConfined()` 放请求与响应缓冲。实测 GraalVM 25.3.4.1 **不需要** `-H:+SharedArenaSupport`，`ofShared` / `ofAuto` 直接可用 |
| 探测失败 | 不是 C ABI 库、缺符号、版本不是 1、库加载不了、**构建期没登记 FFM 签名**：JVM 上安静回落 JNI、绝不抛异常；但失败原因要留下来（`CAbiProbe.Unavailable.reason`），原生构建的报错文本里必须带上 |
| **FFM 签名登记** | native image 里每个 `Linker.downcallHandle(...)` 的签名都要在 `reachability-metadata.json` 的 `foreign.downcalls` 有对应条目（`jint` / `jlong` / `void` / `void*` 的 C 写法），否则抛 `MissingForeignRegistrationError`。JVM 上不需要，**单测覆盖不到**，必须用迷你原生镜像验 |
| 原生构建开关 | `--enable-native-access=ALL-UNNAMED` 已就位 |
| FFM 调用 | 用 `invokeWithArguments`，不用 `invokeExact` |

⚠️ 2026-11-14 的真实事故：网易云 1.3.0 在 Windows 原生包里报「没有导出 C ABI」，
DLL 其实导出了 5 个 `cpp_*`。真因是宿主漏登记 `foreign.downcalls`，而旧实现
`catch (_: Throwable) { null }` 把 `MissingForeignRegistrationError` 吞了，
错误信息把方向指到了音源仓库。

## 7. 验证清单

JNI 与 C ABI 的最小可用检查。

- [ ] 4 个 JNI 符号都在导出表里（`dumpbin -exports` 有 4 个名字，顺序无关）。
- [ ] 5 个 `cpp_*` 符号齐全，`cpp_abi_version()` 返回 1。
- [ ] `createNative` / `cpp_create` 返回非 0。
- [ ] `operation = 1` 至少返回 `tracks.search`、`tracks.get`、`tracks.resolve`。
- [ ] `search` 的 `total` / `hasMore` 可为 null，但给了就必须自洽。
- [ ] 失败路径返回 `1` + `Failure`，不抛异常、不返回空数组。
- [ ] 解码按同一套位置规则，不按 JSON 思路跳字段。

已通过项（2026-11-14，JDK 25，Windows x64）：

| 项 | 结果 |
|---|---|
| 真实库 `reference/netease-module-rust/target/release/ncm_api_rs.dll`（JNI + `cpp_*` 双导出）经 `CAbiNativeProviderTest` | 两路独立会话、`CAPABILITIES` 往返、`cancel` 与重复 `close` 无异常（`tests=2 skipped=0 failures=0`） |
| 非原生库探测 | 返回 `null`，回落 JNI |
| 容量不足重试 | 16 KiB 起始缓冲跑通；扩容分支由实现与单测覆盖 |
| JVM 老 JNI 包回归 | `NativeSdkInstallationTest` 3/3（`skipped=0 failures=0`），适配器 JAR、快照清理、更新失败保留旧安装行为未变 |
| 两个包导出同名符号 | 宿主侧结构已隔离（每包一个 `SymbolLookup`）；仍缺两个真实音源同装的端到端用例 |
| 迷你原生镜像（`-Ob`，带 `app/graalvm` 元数据，发布版 `ncm_api_rs.dll`） | `DOWNLOADS_OK abi=1` → `CREATE handle=…` → `INVOKE code=0 len=3383` → `CLOSE ok`。这一条 JVM 单测替代不了：FFM 签名的构建期登记只在镜像里检查 |
| 原生构建冒烟（装出来的包导入音源） | 待 `packageGraalvmNative` 后导入实测 |

## 8. 明确不在 v1 内

| 项 | 说明 |
|---|---|
| native → Java 回调、事件推送 | 原生镜像对 upcall 限制更多 |
| 跨线程共享同一 handle 的并发 invoke | Provider 自保内部安全，宿主不并发调同一 handle |
| 热更新 | 仍为「复制快照 + 重启生效」 |

## 相关

- 清单与安装：[MANIFEST](MANIFEST.md)
- 契约与错误：[GUIDE](GUIDE.md)
- tag 与 DTO：[SDK_OPERATIONS](SDK_OPERATIONS.md)、[SDK_TYPES](SDK_TYPES.md)
