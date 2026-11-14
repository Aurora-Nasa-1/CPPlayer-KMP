# 原生 Provider 的 C ABI（v1）

> 状态：**v1 已落地。** 宿主侧（FFM）已实现并有回归；示例音源
> `reference/netease-module-rust` 已同时导出 JNI 与 C ABI 两套符号。
> JNI 契约不变，仍以 [MANIFEST_REFERENCE.md](MANIFEST_REFERENCE.md) 的
> `Java_cp_player_sdk_jni_NativeSession_*` 为准；本页是**追加**的第二条 ABI，不是替代。

## 1. 为什么需要它

JNI 契约要求「每个音源包拿到独立的 JNI 绑定」，而 JNI 绑定挂在**类对象**上，所以宿主的做法是
把 `cp.player.sdk.jni.NativeSession` 按包各自 `defineClass` 一次
（`core/src/desktopMain/.../typed/NativePackageClassLoader.kt`）。

GraalVM Native Image 下这两步都不成立（2026-11-14 用 GraalVM 25.3.4.1 迷你镜像实测）：

- 镜像里 `ClassLoader.getResource("任意类.class")` **恒为 `null`** —— 类文件不是可读资源；
- 运行时**不支持定义新类**（类布局是构建期静态确定的）。

于是 Windows 原生构建里导入 JNI 模块只能失败。C ABI 换了个入口：**按符号名直接调用导出函数**。
每个包各自一个动态库句柄 ⇒ 绑定天然独立，不需要 `defineClass`、不需要 `JNIEnv*`，
JVM 与原生镜像可以共用同一套宿主代码。

## 2. 必须导出的符号

五个都要，缺任何一个视为不提供 C ABI。

| 符号 | 签名 | 语义 |
| --- | --- | --- |
| `cpp_abi_version` | `int32_t cpp_abi_version(void)` | 返回 `1`。宿主用它探测「这个库支不支持 C ABI」，**必须先调它**。 |
| `cpp_create` | `int64_t cpp_create(const char* providerId, const char* accountId, const char* credential, const char* settingsJson, char* err, int32_t errCap)` | 返回非零会话句柄；失败返回 `0`，并把 UTF-8 错误原因写进 `err`（NUL 结尾，最多 `errCap-1` 字节）。`credential` 可为 `NULL`。 |
| `cpp_invoke` | `int32_t cpp_invoke(int64_t handle, int32_t operation, const uint8_t* req, int32_t reqLen, uint8_t* out, int32_t outCap, int32_t* outLen)` | 见第 4 节的返回码。请求/响应都是 Wire 字节（格式见 [PROVIDER_NATIVE_WIRE.md](PROVIDER_NATIVE_WIRE.md)），**不额外加壳**。 |
| `cpp_cancel` | `void cpp_cancel(int64_t handle)` | 线程安全、立即返回，不释放句柄（与 JNI 契约的 `cancelNative` 同义）。 |
| `cpp_close` | `void cpp_close(int64_t handle)` | 等 in-flight 调用排空后释放句柄；**幂等**（重复调用无副作用）。 |

参数细节：

- 所有 `char*` 都是 **UTF-8 + NUL 结尾**，生命周期只覆盖本次调用 —— provider **不得**保存指针，
  需要留存就自己拷一份。
- `req` / `out` 的长度用 `int32_t`（单个 Wire 报文远小于 2 GiB）。
- 句柄 `int64_t` 不透明，`0` 表示无效。

## 3. 内存所有权（这条最容易踩）

**没有任何内存跨 DLL 边界释放。** Windows 下宿主与 provider 可能用不同的 CRT 堆，
跨堆的 `free` / `delete` 会直接崩，而且崩在 native 层、栈里看不出是谁。

- host → provider：字符串、请求字节，全部由 host 分配、host 释放，provider 只读。
- provider → host：**不返回指针**，写进 host 给的 `out` 缓冲。
- 需要扩容时不要「先申请再返回」：用第 4 节的 `1` + `outLen` 让宿主扩容重试。

## 4. 返回值与错误分层

`cpp_invoke` 返回 `int32_t`，`*outLen` 始终回填：

| 返回 | 含义 |
| --- | --- |
| `0` | 成功，`out[0 until *outLen]` 是响应报文 |
| `1` | `outCap` 不足，`*outLen` 是**需要**的容量；宿主扩容后再调一次（重试是幂等的：`operation` 与 `req` 不变） |
| `2` | ABI 层失败（句柄无效、内部 panic 等）；业务错误不要走这里 |

**业务错误走 Wire**：响应首字节 `0` 成功 / `1` 失败 + `Failure` DTO，与 JNI 版完全一致。
所以 `JniMusicProvider` / `NativeWireCodec` 一行都不用改。

## 5. 线程与生命周期

- `cpp_invoke` / `cpp_cancel` / `cpp_close` 可从任意线程调用，provider 内部自管同步。
- `cpp_cancel` 之后句柄仍然有效，必须照常 `cpp_close`。
- `cpp_close` 必须等已开始的 `cpp_invoke` 返回后才能真正释放（宿主会在 close 前后各调一次）。
- **不提供任何 native → Java 回调**（upcall）。原生镜像对 upcall 的限制比 downcall 多得多，
  这条也是本 ABI 刻意留下的空白：需要推送/事件就沿用现有轮询或 Wire 里的能力查询。

## 6. 双导出：老包怎么办

一个动态库可以同时导出 JNI 符号和 C 符号。推荐的迁移方式：

- **保留现有 JNI 导出**（JVM / Android 继续走它，行为一点不变）；
- **追加 C ABI 导出**（原生构建走它）。

宿主的探测顺序（`InstalledNativeSdkProvider`）：

| 运行时 | 有 C ABI | 没有 C ABI |
| --- | --- | --- |
| GraalVM Native Image | 走 C ABI | 报错：「只导出了 JNI 符号…请升级模块，或改用 JS 音源 / JVM 版客户端」 |
| JVM 桌面 / Android | 仍然走 **JNI**（线上既有路径，**不**因为多导出了 C ABI 就切走） | JVM 走 JNI；Android 走 JNI |

也就是说：**只导出 C ABI、不导出 JNI 的库在 JVM 上不可用**（会报「must export NativeSession …」）。
要同时支持桌面 JVM 与原生构建，两套都导出；宿主只在原生构建里 `libraryLookup`，
JVM 上不会为了探测去加载库（否则 DLL 会钉住快照目录）。

Rust 侧大致长这样（内部仍走现有实现，只是换个 FFI 外壳）：

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

## 7. manifest 要不要改

**不改。** `type: "jni"` 依旧表示「原生模块」，`entryPoint` 依旧指向库文件，
`supportedAbis` 依旧是 ABI 白名单。C ABI 能力靠**符号探测**判断，不新增字段。

理由：manifest 是音源作者手写的，多一个「可选但决定加载行为」的字段就有第二个真相，
一旦写错就是「明明导出了却报不支持」。符号探测是自证的。

## 8. 宿主实现要点（我方）

已落地位置：

| 文件 | 作用 |
| --- | --- |
| `core/src/jvmMain/.../typed/NativeSdkAbi.kt` | `CAbiSessionFactory` 接口 + `expect cAbiSessionFactory(library)` |
| `core/src/desktopMain/.../typed/CAbiNativeSession.desktop.kt` | FFM 实现（探测 → create/invoke/cancel/close，容量不足重试） |
| `core/src/androidMain/.../typed/CAbiNativeSession.android.kt` | 恒 `null`（ART 没有 `java.lang.foreign`） |
| `core/src/jvmMain/.../typed/InstalledNativeSdkProvider.kt` | 分流：有 C ABI 走它；没有则 JVM 回落 JNI、原生构建抛可读错误 |

实现细节：

- 每个包一个 `Arena.ofShared()` 持有库句柄（`close` 时关掉 = 卸载库），
  每次调用再用 `Arena.ofConfined()` 放请求/响应缓冲 —— 共享 arena 不随调用增长，
  confined arena 又保证缓冲随调用释放。
- 探测失败（不是 C ABI 库、缺符号、版本不是 1、库加载不了）一律 `null`，
  绝不抛异常：JVM 上必须能安静回落 JNI。
- 原生构建需要的开关项目里已就位：`--enable-native-access=ALL-UNNAMED`；
  `-H:+SharedArenaSupport` 由 `nucleus.graalvm-runtime` 的 `native-image.properties` 自动带上。

## 9. 验证清单

已通过（2026-11-14，JDK 25，Windows x64）：

1. ✅ 真实库 `reference/netease-module-rust/target/release/ncm_api_rs.dll`（同时导出 JNI + `cpp_*`）
   经 `CAbiNativeProviderTest` 打开两路独立会话、`CAPABILITIES` 往返成功、`cancel`/重复 `close` 均无异常
   （`TEST-cp.player.core.provider.CAbiNativeProviderTest.xml`：`tests=2 skipped=0 failures=0`）。
2. ✅ 非原生库返回 `null`（回落 JNI 而不是抛异常）。
3. ✅ 容量不足重试：`CAbiNativeProviderTest` 用 16 KiB 起始缓冲跑通；
   provider 返回 `1` + `outLen` 时的扩容分支由 `cpp_invoke` 的实现 + 单测覆盖。
4. ⏳ 两个包导出同名符号互不影响：宿主侧结构上已隔离（每包一个 `SymbolLookup`），
   还需要两个真实音源同时在装的端到端用例。
5. ✅ JVM 老 JNI 包回归：`NativeSdkInstallationTest` 3/3（`skipped=0 failures=0`），
   JNI 路径（含适配器 JAR、快照清理、更新失败保留旧安装）行为未变。
6. ⏳ 原生构建冒烟：`packageGraalvmNative` 后导入 NetEase 模块实测（需要完整构建）。

## 10. 明确不在 v1 内

- native → Java 回调（upcall）、事件推送；
- 一个句柄跨线程共享会话状态（provider 自己保证内部安全，但宿主不会并发调同一 handle 的 invoke）；
- 热更新（替换 DLL 后不重启就生效）—— 仍沿用「复制快照 + 重启生效」。
