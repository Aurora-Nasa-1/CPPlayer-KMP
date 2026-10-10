# CPPlayer Desktop Native 化可行性评估与新型高效音源插件架构设计

> **状态：** 方案设计（RFC）  
> **日期：** 2026-10-10  
> **作者：** CPPlayer 架构团队  
> **目标：**  
> 1. 深度评估 Desktop 端进行 Native 化的多条技术路径与可行性结论；  
> 2. 针对当前 JNI 音源插件崩溃、符号强绑定、跨平台维护困难等痛点，提出一种**不同于 JNI、性能优异、安全隔离且同时深度兼容 JVM 与未来 Native 宿主**的新型音源插件架构。

---

## 目录

1. [Desktop Native 化可行性评估报告](#1-desktop-native-化可行性评估报告)
    - [1.1 现状架构底座盘点](#11-现状架构底座盘点)
    - [1.2 路径一：Kotlin/Native 桌面端（K/N + Compose Multiplatform）](#12-路径一kotlinnative-桌面端kn--compose-multiplatform)
    - [1.3 路径二：GraalVM Native Image（AOT 编译现有 JVM 字节码）](#13-路径二graalvm-native-imageaot-编译现有-jvm-字节码)
    - [1.4 路径三：全原生重写（Rust / C++ / Qt / Slint）](#14-路径三全原生重写rust--c--qt--slint)
    - [1.5 现状最佳实践：自包含打包已实现“用户感知 Native 化”](#15-现状最佳实践自包含打包已实现用户感知-native-化)
    - [1.6 Native 化综合结论与演进建议](#16-native-化综合结论与演进建议)
2. [音源插件现状与 JNI 模式的固有缺陷诊断](#2-音源插件现状与-jni-模式的固有缺陷诊断)
    - [2.1 现有三种 Provider 模式简述](#21-现有三种-provider-模式简述)
    - [2.2 JNI Provider 的五大致命痛点](#22-jni-provider-的五大致命痛点)
    - [2.3 核心设计目标](#23-核心设计目标)
3. [主推方案：WebAssembly (WASM) 微沙箱高性能插件架构](#3-主推方案webassembly-wasm-微沙箱高性能插件架构)
    - [3.1 总体架构设计](#31-总体架构设计)
    - [3.2 跨运行环境执行机制（JVM vs Native）](#32-跨运行环境执行机制jvm-vs-native)
    - [3.3 零 JNI、中立 ABI 的共享线性内存通信协议](#33-零-jni中立-abi-的共享线性内存通信协议)
    - [3.4 契约与接口设计规范](#34-契约与接口设计规范)
    - [3.5 插件端实现示例（Rust）](#35-插件端实现示例rust)
    - [3.6 宿主端适配实现（Kotlin / JVM）](#36-宿主端适配实现kotlin--jvm)
4. [互补备选方案](#4-互补备选方案)
    - [4.1 备选方案 A：标准 C-ABI + FFM (Project Panama) / dlopen 模式](#41-备选方案-a标准-c-abi--ffm-project-panama--dlopen-模式)
    - [4.2 备选方案 B：高速本地 IPC 模式（Domain Socket / Named Pipe + Protobuf）](#42-备选方案-b高速本地-ipc-模式domain-socket--named-pipe--protobuf)
5. [综合对比评估矩阵](#5-综合对比评估矩阵)
6. [落地与演进路线图](#6-落地与演进路线图)

---

## 1. Desktop Native 化可行性评估报告

### 1.1 现状架构底座盘点

CPPlayer 现行 Desktop 端架构如下：

```
┌─────────────────────────────────────────────────────────────┐
│                    CPPlayer Desktop 进程                     │
├─────────────────────────────────────────────────────────────┤
│ UI 层: Compose Multiplatform (1.12.1) + Voyager             │
│ 图形渲染: Skiko (0.150.1) -> AWT Canvas / Direct3D / OpenGL   │
│ 运行时: JetBrains Runtime 21 (JBR 21.0.8, JbrMoveHandler)    │
│ 核心逻辑: Kotlin/JVM (Kotlin 2.4.10)                        │
│ 依赖生态:                                                   │
│   - kotlinx.coroutines.swing (Swing EDT 调度)               │
│   - io.github.kdroidfilter:composemediaplayer-audio (JNI)   │
│   - JMTC + JNA (Windows SMTC 系统媒体集成)                   │
│   - Rhino (纯 JVM JavaScript 引擎，用于歌词插件)              │
│   - Ktor Client OkHttp / Ktor Server CIO                    │
│   - HypnoticCanvas (SkSL 运行时着色器)                       │
│   - MaterialKolor (基于 Monet 的动态主题计算)               │
└─────────────────────────────────────────────────────────────┘
```

代码体量：`app` 模块 16.6k 行，`core` 模块 11.4k 行，两端与 Android 共享约 85% 代码。

针对“Desktop 能不能 Native 化”，我们从以下三个层面进行技术可行性论证：

---

### 1.2 路径一：Kotlin/Native 桌面端（K/N + Compose Multiplatform）

#### 评估结论：**当前生态下不可行（Blocker 级别生态缺失）**

#### 根因剖析：
1. **JetBrains Compose Multiplatform 桌面端并未支持 Kotlin/Native**：
   - CMP 官方支持的 Target 矩阵：
     - Android（JVM / ART）
     - Desktop（**仅 JVM**：基于 AWT / Java2D / Skiko-JVM 窗口桥接）
     - iOS（Kotlin/Native：UIKit 桥接）
     - Web（Kotlin/Wasm、Kotlin/JS）
   - **CMP 没有官方的 Windows (mingwX64) 或 Linux (linuxX64) 的 Desktop Native 渲染与窗口管道**。虽然底层的 Skiko 源码在理论上有 C 绑定，但 Compose UI 顶层的 Window、PointerInput、TextLayout、Accessibility 在桌面操作系统上全部与 Java AWT 体系紧密捆绑。
2. **核心支撑库断代**：
   - `kotlinx.coroutines.swing`：纯 JVM 构件，负责将协程无缝分发到桌面主事件循环（Swing EDT）。K/N 桌面端没有对应的稳定事件循环生态。
   - `JMTC` 与 `JNA`：完全依赖 JVM 类加载与 JNA C 桥接，无 K/N 对应物。
   - `Rhino`：纯 Java 实现的 JS 引擎，无法在 K/N 运行。
   - `composemediaplayer-audio`：虽然底层是 Rust `rodio`，但其 Kotlin 分发包只发布了 JVM 和 Android 产物，内部调用靠 Java JNI 桥（`NativeRodioBridge`）。
3. **维护成本与多平台共享破坏**：
   - 现有的 `jvmMain` 源集涵盖了 Android 和 Desktop 的大量复用代码（IO 流、网络引擎、文件管理）。一旦 Desktop 剥离为 Native，将导致 Android 维持 JVM、Desktop 变为 Native，`jvmMain` 瓦解，全仓需要拆出庞大的 `expect/actual`。

---

### 1.3 路径二：GraalVM Native Image（AOT 编译现有 JVM 字节码）

#### 评估结论：**工程落地的脆弱性极高，维护成本巨大，且会牺牲核心体验**

GraalVM Native Image 可以把 JVM 字节码 AOT 编译为单个原生可执行文件（ELF / PE）。但在当前 CPPlayer-KMP 项目中，存在以下严重阻碍：

1. **AWT / Swing 在 Native Image 下的已知黑洞**：
   - Compose Desktop 窗口的宿主是 `androidx.compose.ui.awt.ComposeWindow`（继承自 `java.awt.Frame`）。
   - Oracle GraalVM 对 AWT/Swing 的支持历来极其有限，涉及 Windows GDI/DirectX 的内部 JNI 绑定、字体管理器（FontManager）、动态类反射等。虽然 JavaFX 社区有 Gluon Substrate 支持，但 Compose Desktop 是基于 AWT 的，几乎无法通过闭合世界分析（Closed-World Assumption）。
2. **JBR 原生特性的丧失**：
   - 本项目专门配置了 JetBrains Runtime 21（见 `docs/dev/JBR_PACKAGING.md`），核心目的是为了通过 `com.jetbrains.JBR.isWindowMoveSupported()` 调用 JetBrains 专有的原生窗口拖动通道（`JbrMoveHandler`），以获得 Windows 贴边吸附与跟手拖动。
   - GraalVM 自身使用标准的 OpenJDK 基底，无法识别和内嵌 JBR 专有本地库与类扩展。
3. **动态代码与反射地狱**：
   - Rhino 解释器在运行时动态生成字节码执行插件代码，这与 GraalVM 的静态 AOT 本质冲突。
   - JNA（SMTC 媒体控制）严重依赖动态 libffi 调用，在 Native Image 下需要极其繁琐的反射元数据（reachability metadata）。

---

### 1.4 路径三：全原生重写（Rust / C++ / Qt / Slint）

#### 评估结论：**ROI（投入产出比）极低，失去 KMP 的跨端核心价值**

- CPPlayer 经过多轮架构收敛，`core` 与 `app` 具备完备的双栏适配、M3 Expressive 动画、响应式状态流、缓存容灾与离线渲染。
- 重写意味着丢弃 28,000+ 行精心调试并与 Android 共享的代码，且后续所有新特性均需双倍人力分别维护 Kotlin 与 Native 两个代码库。

---

### 1.5 现状最佳实践：自包含打包已实现“用户感知 Native 化”

在客户端工程中，“Native 化”对于终端用户的核心价值其实只有三点：
1. **零外部依赖**：用户不需要预先安装 JRE/JDK；
2. **秒级启动与流畅帧率**：冷启动 < 1s，渲染不掉帧；
3. **原生系统集成**：具备原生安装包（Setup.exe）、托盘、贴边吸附、硬件加速与原生通知。

**CPPlayer 目前已经通过工程化手段实现了上述所有价值**：
- **Jlink 深度剪裁**：使用 JBR 21 SDK 经 `jlink` 将 JVM 裁剪为仅包含所需模块的高性能嵌入式运行时；
- **Velopack 打包体系**：直接产出原生 Windows 安装器与单个入口 `CPPlayer.exe`，免安装 JVM；
- **硬件加速**：Skiko 直接接管 GPU，走 Direct3D / OpenGL 渲染；
- **原生拖动**：JBR 原生 API 接管窗口移动；
- **音视频原生加速**：音频解码与输出底层已经是 Rust（`rodio` / `symphonia`）。

---

### 1.6 Native 化综合结论与演进建议

| 路径 | 可行性 | 核心代价/风险 | 建议结论 |
|---|---|---|---|
| **K/N 桌面端** | ❌ 极低 (无官方支持) | CMP 官方缺失桌面 Native target，生态库全部失效 | **放弃**，不具备实施条件 |
| **GraalVM AOT** | ⚠️ 极难且脆弱 | AWT/JBR/Skiko/Rhino 反射配置黑洞，极易运行期闪退 | **不推荐**，维护负担过重 |
| **全栈原生重写** | ❌ 负收益 | 2.8万行代码重写，彻底割裂 Android 端共享优势 | **放弃** |
| **分层 Native 演进（推荐）** | ✅ **可行且平滑** | 保持 Compose JVM UI 壳层，将核心高负荷/音源模块抽象为原生插件 | **采纳**（详见下节） |

> **定案策略**：  
> **UI 表现层坚定保持 Compose Multiplatform Desktop (JBR 21)**，享受跨端统一开发体验与丰富的 MD3 生态；  
> **插件与底层计算层推行现代 Native / Wasm 抽象**，摆脱 JNI 的腐化，实现跨平台的高效插件机制。

---

## 2. 音源插件现状与 JNI 模式的固有缺陷诊断

### 2.1 现有三种 Provider 模式简述

根据 `docs/dev/PROVIDER_DEV_GUIDE.md` 与 `core/src/jvmMain/.../JniProvider.kt`：

1. **HttpProvider**：外部常驻服务，通过 HTTP POST 交互。
2. **BinaryProvider**：CPPlayer 启动子进程，通过本地 Loopback 端口发起 HTTP POST 交互。
3. **JniProvider**：直接 `System.load(soPath)`，通过 Java JNI 调用原生动态库（`.dll` / `.so`）。

---

### 2.2 JNI Provider 的五大致命痛点

当前 JNI Provider 在生产环境中存在不可忽视的安全与工程缺陷：

#### 1. 全进程崩溃扩散（No Fault Isolation）
在 JNI 模式下，C/Rust 插件与 CPPlayer 宿主运行在同一个进程、同一个虚拟内存空间内。
- 插件发生一次 **内存越界访问、空指针解引用或 Rust `panic!`**，操作系统会直接向宿主进程发送 `SIGSEGV` 或引发未处理异常；
- **导致整个播放器主进程瞬间暴毙**，正在播放的音乐中断，未保存的播放状态丢失，用户体验极差。

#### 2. 符号名称与 Java 包名/类名强耦合（Brittle ABI Coupling）
JNI 的导出符号名遵循严格的规范：`Java_<包名下划线>_<类名>_<方法名>`。
- 宿主类为 `cp.player.core.provider.JniProvider`，因此导出的 C 符号必须形如：
  `Java_cp_player_core_provider_JniProvider_nativeCallApi`
- **历史真实血泪史**：仓库重构将包名由 `cp.player.kmp` 改为 `cp.player.core` 时，全部既有 JNI 模块全部报 `UnsatisfiedLinkError` 瘫痪！
- 一旦宿主未来开启 R8 混淆或重命名类，外部插件必须全部重新编译。

#### 3. 缺乏细粒度安全与沙箱隔离
- JNI 插件拥有宿主进程的全部系统权限。恶意或有缺陷的插件可以直接读取用户的本地敏感文件（如 Cookie、私人歌单本地缓存）、随意建立外部未经授权的 Socket 连接，宿主对此毫无监管手段。

#### 4. 与非 JVM 运行时彻底绝缘
- JNI 是 Java 虚拟机专有规范。如果未来 CPPlayer 的核心引擎部分以 Kotlin/Native、Rust 或 WebAssembly 形式发布，已有的 JNI 音源插件资产将全部作废，无法复用。

#### 5. 跨平台/跨架构分发成本高昂
- 插件开发者需要使用专门的 NDK 与交叉编译链，分别为 `x86_64-pc-windows-msvc`、`x86_64-unknown-linux-gnu`、`aarch64-linux-android`、`arm64-v8a` 等每个平台分别编译动态库，维护极其沉重。

---

### 2.3 核心设计目标

我们需要一种新型音源插件模式，满足：
1. **脱离 JNI 束缚**：不包含 `JNIEnv`，不绑定 Java 包名/类名符号，纯净标准化；
2. **同时兼容 JVM 与未来可能的 Native 宿主**：在当前 JBR 桌面端及 Android 端能高效运行，在未来纯 Native 环境下同样能零成本复用；
3. **内存级安全隔离与容灾**：单个插件崩溃不会拖垮播放器进程；
4. **极致性能**：通信开销远低于 TCP HTTP（毫秒/微秒级），支持高效二进制与流式数据；
5. **分发简易**：一次编译，全平台通用分发。

---

## 3. 主推方案：WebAssembly (WASM) 微沙箱高性能插件架构

### 3.1 总体架构设计

我们主推引入 **`WasmProvider`** 架构。音源插件使用 Rust / C / Zig / Go 编写，统一编译为标准的 **`.wasm` (WebAssembly)** 模块包。

```
                    ┌───────────────────────────────────────────────┐
                    │               CPPlayer 宿主                   │
                    │   (MusicApiService / ProviderManager)         │
                    └───────────────────────┬───────────────────────┘
                                            │ 统一统一调用契约
                                            ▼
                    ┌───────────────────────────────────────────────┐
                    │          WasmProvider (宿主驱动层)             │
                    │   - 生命周期管理 / 超时熔断 / 状态隔离          │
                    │   - 线性共享内存管理器 (Linear Memory Pool)    │
                    └───────┬───────────────────────────────┬───────┘
                            │                               │
            [JVM / JBR 环境] │                               │ [Native 环境]
                            ▼                               ▼
       ┌───────────────────────────────┐     ┌─────────────────────────────┐
       │   Chicory (纯 JVM)            │     │   Wasmtime / Wasmi          │
       │   或 Wasmtime-Java JIT 引擎    │     │   (原生 C/Rust 嵌入式运行时)  │
       └───────────────┬───────────────┘     └──────────────┬──────────────┘
                       │                                    │
                       └─────────────────┬──────────────────┘
                                         ▼
                       ┌───────────────────────────────────┐
                       │       Wasm 沙箱实例 (Instance)     │
                       │ --------------------------------- │
                       │  - 独立 64KB Page 线性隔离内存     │
                       │  - 无宿主崩溃扩散 (Safe Traps)     │
                       │  - 纯粹的中立 C-ABI 导出函数       │
                       │    * cp_alloc() / cp_free()       │
                       │    * cp_provider_invoke()         │
                       └───────────────────────────────────┘
```

---

### 3.2 跨运行环境执行机制（JVM vs Native）

#### 在当前的 JVM / JBR 宿主上：
- **方案 A（首选极简）：Chicory Wasm 引擎**
  - **特点**：100% 纯 Java/Kotlin 实现，零外部 C/C++ 动态链接库依赖，零 JNI！
  - **安全性**：完全符合 Java 内存安全，不受操作系统平台与架构差异影响。
  - **性能**：对音源插件（主要是 HTTP 协议封装、加解密 AES/RSA、JSON 组装）这类非 3D 渲染运算，其经过 AOT/解释器执行的吞吐量可达数万次 API 调用/秒，完全满足音源需求。
- **方案 B（极致高吞吐）：Wasmtime-Java / Extism Java SDK**
  - 使用经过验证的工业级 Wasmtime 引擎，JIT 编译后性能接近裸机原生（90%+ Native Speed）。

#### 在未来的 Native 宿主上（若 Core 迁移至 K/N 或 Rust）：
- 直接通过 Rust / C 原生链接 `wasmtime` 或 `wasmi`，宿主无需修改任何插件接口，**同一个 `.wasm` 插件文件原封不动运行**。

---

### 3.3 零 JNI、中立 ABI 的共享线性内存通信协议

Wasm 模块拥有自己独立的线性内存空间（Linear Memory，初始若干个 64KiB Page）。宿主与插件之间通过直接内存偏移量与长度进行高效数据交换，避免套接字握手与重型序列化开销。

#### 交互时序图：

```
宿主 (Host)                                       Wasm 插件实例 (Plugin)
    │                                                        │
    │ 1. 请求内存分配: cp_alloc(input_len)                   │
    ├───────────────────────────────────────────────────────>│
    │<───────────────────────────────────────────────────────┤ (返回 input_ptr)
    │                                                        │
    │ 2. 宿主直接写入入参数据 (JSON / Protobuf)               │
    │    [Host writes bytes directly into Linear Memory]     │
    │                                                        │
    │ 3. 执行 API 动作: cp_provider_invoke(method_id, ...)   │
    ├───────────────────────────────────────────────────────>│ (执行加解密与逻辑)
    │<───────────────────────────────────────────────────────┤ (返回 output_fat_ptr)
    │                                                        │
    │ 4. 宿主直接读取返回结果 (output_ptr, output_len)       │
    │    [Host reads bytes from Linear Memory]               │
    │                                                        │
    │ 5. 释放插件返回的内存: cp_free(output_ptr, output_len)  │
    ├───────────────────────────────────────────────────────>│
    │                                                        │
```

整个过程只有纯内存读写与函数跳转，**调用开销在微秒级别（< 0.05ms）**，相比本地 TCP 握手（5~20ms）提升两个数量级。

---

### 3.4 契约与接口设计规范

所有 Wasm 模块仅需导出 4 个极简的基础符号（纯 C-ABI，无任何语言绑定）：

```c
// 内存管理：供宿主申请与释放 Wasm 内部内存
uint32_t cp_alloc(uint32_t size);
void cp_free(uint32_t ptr, uint32_t size);

// 核心初始化：传入初始化上下文配置（如宿主版本、特性声明）
int32_t cp_provider_init(uint32_t config_ptr, uint32_t config_len);

// 核心调用：分发 API 请求
// 返回一个 64 位整数，高 32 位为 output_ptr，低 32 位为 output_len
uint64_t cp_provider_invoke(
    uint32_t method_ptr, 
    uint32_t method_len, 
    uint32_t params_ptr, 
    uint32_t params_len
);
```

---

### 3.5 插件端实现示例（Rust）

插件开发者只需使用纯标准 Rust（无需引入 `jni` crate，仅需 `wasm32-unknown-unknown` 或 `wasm32-wasi`）：

```rust
// Cargo.toml
// [lib]
// crate-type = ["cdylib"]

use std::mem;
use std::slice;

#[no_mangle]
pub extern "C" fn cp_alloc(size: u32) -> *mut u8 {
    let mut buf = Vec::with_capacity(size as usize);
    let ptr = buf.as_mut_ptr();
    mem::forget(buf);
    ptr
}

#[no_mangle]
pub unsafe extern "C" fn cp_free(ptr: *mut u8, size: u32) {
    if !ptr.is_null() {
        let _ = Vec::from_raw_parts(ptr, size as usize, size as usize);
    }
}

#[no_mangle]
pub unsafe extern "C" fn cp_provider_invoke(
    method_ptr: *const u8,
    method_len: u32,
    params_ptr: *const u8,
    params_len: u32,
) -> u64 {
    let method = std::str::from_utf8(slice::from_raw_parts(method_ptr, method_len as usize)).unwrap_or("");
    let params = std::str::from_utf8(slice::from_raw_parts(params_ptr, params_len as usize)).unwrap_or("{}");

    // 业务处理：解析网易云/其他音源的加解密算法 (EAPI / WEAPI)
    let response_json = match method {
        "cloudsearch" => handle_search(params),
        "song/url/v1" => handle_song_url(params),
        _ => r#"{"code": 501, "message": "unsupported method"}"#.to_string(),
    };

    // 将结果写入内存并打包 fat pointer (ptr: u32, len: u32)
    let bytes = response_json.into_bytes();
    let out_len = bytes.len() as u32;
    let out_ptr = bytes.as_ptr() as u32;
    mem::forget(bytes);

    ((out_ptr as u64) << 32) | (out_len as u64)
}

fn handle_search(_params: &str) -> String {
    // 实际业务逻辑（纯运算与协议拼装）
    r#"{"code": 200, "result": {"songs": []}}"#.to_string()
}

fn handle_song_url(_params: &str) -> String {
    r#"{"code": 200, "data": []}"#.to_string()
}
```

构建命令只需一条，不需要任何 C++ 编译器与 MSVC 环境：
```bash
cargo build --target wasm32-unknown-unknown --release
```
生成的单个 `.wasm` 文件（配合 `manifest.json`）即可全平台运行！

---

### 3.6 宿主端适配实现（Kotlin / JVM）

宿主端统一接入 `BackendProvider` 接口：

```kotlin
package cp.player.core.provider.wasm

import cp.player.core.provider.BackendProvider
import cp.player.core.provider.ProviderType
import cp.player.core.util.PlatformContext

class WasmProvider(
    override val id: String,
    override val name: String,
    override val version: String,
    private val wasmBytes: ByteArray,
    override val apiMap: Map<String, String>? = null,
    override val updateUrl: String? = null,
    override val capabilities: List<String>? = null,
    override val apiVersion: Int = 3
) : BackendProvider {

    override val type: ProviderType = ProviderType.WASM
    private var engineInstance: WasmRuntimeInstance? = null
    private var loadError: String? = null

    override fun isReady(): Boolean = engineInstance != null && loadError == null

    override fun startServer(context: PlatformContext, port: Int) {
        try {
            // 初始化 Wasm 虚拟机实例（例如使用 Chicory 或 Wasmtime）
            val instance = WasmRuntime.load(wasmBytes)
            instance.invoke("cp_provider_init", 0, 0)
            this.engineInstance = instance
        } catch (e: Throwable) {
            loadError = "Wasm 模块载入失败: ${e.message}"
            throw IllegalStateException(loadError, e)
        }
    }

    override fun stopServer() {
        engineInstance?.close()
        engineInstance = null
    }

    override fun callApi(method: String, params: Map<String, String>): String {
        val instance = engineInstance ?: return "{\"code\": 500, \"msg\": \"Wasm not ready\"}"
        val paramsJson = buildJson(params)

        return try {
            // 1. 申请内存并写入入参
            val methodBytes = method.encodeToByteArray()
            val paramsBytes = paramsJson.encodeToByteArray()

            val methodPtr = instance.alloc(methodBytes.size)
            instance.writeMemory(methodPtr, methodBytes)

            val paramsPtr = instance.alloc(paramsBytes.size)
            instance.writeMemory(paramsPtr, paramsBytes)

            // 2. 调用纯 Wasm 导出函数
            val fatPtr = instance.invoke("cp_provider_invoke", methodPtr, methodBytes.size, paramsPtr, paramsBytes.size)
            val outPtr = (fatPtr ushr 32).toInt()
            val outLen = (fatPtr and 0xFFFFFFFFL).toInt()

            // 3. 读取响应
            val resultBytes = instance.readMemory(outPtr, outLen)
            val result = resultBytes.decodeToString()

            // 4. 释放插件返回内存
            instance.invoke("cp_free", outPtr, outLen)
            result
        } catch (e: Exception) {
            // 异常被完全限制在 Wasm 沙箱，不会导致播放器主进程崩溃！
            "{\"code\": 500, \"msg\": \"Wasm invocation trap: ${e.message}\"}"
        }
    }

    private fun buildJson(params: Map<String, String>): String {
        return params.entries.joinToString(prefix = "{", postfix = "}") { 
            "\"${it.key}\":\"${it.value.replace("\"", "\\\"")}\"" 
        }
    }
}
```

---

## 4. 互补备选方案

### 4.1 备选方案 A：标准 C-ABI + FFM (Project Panama) / dlopen 模式

如果插件必须使用重型 C/C++ 算法（例如私有自研无损软解压、专用硬件驱动交互），可以采用**标准 C-ABI 动态库**，但彻底规避 JNI。

#### 核心机制：
- 插件输出普通动态库（`.dll` / `.so`），**绝不引入 `jni.h`，绝不导出任何 `Java_*` 符号**；
- 导出标准 C-ABI 函数：
  ```c
  CP_EXPORT int cp_provider_call(
      const char* method, 
      const char* json_in, 
      char** json_out
  );
  CP_EXPORT void cp_provider_free(char* ptr);
  ```
- **JVM 宿主端**：采用 Java 22+ 的 **FFM (Foreign Function & Memory API, JEP 454)** 或轻量 `JNA`，通过 `Linker` 与 `SymbolLookup` 直接绑定该符号；
- **Native 宿主端**：通过标准 POSIX `dlopen` / `dlsym` 或 Win32 `LoadLibrary` / `GetProcAddress` 绑定。

#### 优缺点：
- 优势：达到 Native 的最高计算性能；
- 缺点：仍然缺乏内存沙箱隔离，插件段错误依然可能导致宿主崩溃；仍需为各操作系统单独编译多份动态库。

---

### 4.2 备选方案 B：高速本地 IPC 模式（Domain Socket / Named Pipe + Protobuf）

如果坚持采用独立进程模型以获取强隔离，但不满于当前 BinaryProvider 的 TCP HTTP 缺点，可采用 **IPC 微服务化模式**。

#### 核心机制：
- 插件仍为独立可执行程序，但**禁止监听 TCP 端口**（规避代理拦截与端口抢占）；
- 宿主与插件之间通过 **Unix Domain Socket (UDS)**（Windows 10+ 与 Linux/macOS 均原生支持）或 **Windows 命名管道 (Named Pipe)** 握手；
- 序列化采用 **Protobuf 或 FlatBuffers 二进制帧**，取代带有沉重文本头的 HTTP/1.1 请求。

#### 优缺点：
- 优势：进程级绝对隔离，崩溃零影响；避开全部 HTTP 代理冲突问题；
- 缺点：进程管理开销相对 Wasm 略大，移动端（Android）对子进程衍生限制较多。

---

## 5. 综合对比评估矩阵

| 评估维度 | 现行 JNI 模式 | 现行 Binary 模式 | **主推：WASM 微沙箱 (WasmProvider)** | 备选 A：C-ABI + FFM | 备选 B：UDS/Pipe IPC |
|---|---|---|---|---|---|
| **通信延迟** | 极低 (<0.01ms) | 较高 (5~25ms, TCP) | **极低 (0.01~0.05ms)** | 极低 (<0.01ms) | 低 (0.2~1.0ms) |
| **崩溃隔离性** | ❌ 零隔离 (全员暴毙) | ✅ 进程隔离 | ✅ **沙箱隔离 (Trap 可捕获)** | ❌ 零隔离 | ✅ 进程隔离 |
| **跨系统分发** | ❌ 极差 (每个 OS/ABI 重编) | ❌ 需多平台二进制 | ✅ **极佳 (一次编译全平台通用)** | ❌ 需多平台动态库 | ❌ 需多平台二进制 |
| **符号/包名解耦** | ❌ 强绑定 `Java_...` | ✅ 完全中立 | ✅ **完全中立 (仅导出 C 符号)** | ✅ 完全中立 | ✅ 完全中立 |
| **JVM 平台兼容** | ✅ 深度依赖 JVM | ✅ 支持 | ✅ **完美支持 (Chicory / Wasmtime)** | ✅ (Java 22+ / JNA) | ✅ 支持 |
| **Native 宿主兼容**| ❌ 无法在非 JVM 运行 | ✅ 支持 | ✅ **完美支持 (Wasmi / Wasmtime)** | ✅ 完美支持 | ✅ 支持 |
| **网络代理干扰** | 无 | ⚠️ 易被系统代理劫持 (502) | **无 (内部调用)** | 无 | **无** |
| **实现难度** | 中 | 低 | **中等** | 中 | 中高 |

---

## 6. 落地与演进路线图

为了平滑引入新架构且不破坏现有老用户资产，建议分三阶段落地：

### 第一阶段：Manifest v3 规范演化与架构解耦（兼容期）
1. 在 `ModuleManifest` 中新增 `apiVersion: 3` 与 `type: "wasm"` 类型支持；
2. 保持对已有 `http`、`binary` 和 `jni` 的完全向后兼容；
3. 将统一的 `BackendProvider` 接口增加沙箱安全兜底拦截器（`SafeProviderProxy`），防止底层未知异常扩散。

### 第二阶段：接入 Wasm 运行时核心与样例工程
1. 在 `core` 模块中引入轻量级 Wasm 引擎抽象 `WasmRuntime`（在 JVM 上优先引入零原生依赖的 Chicory 实现）；
2. 实现 `WasmProvider` 并完成与 `ProviderManager` 的挂载；
3. 将官方参考项目 `reference/netease-module-rust` 升级增加 `wasm32-unknown-unknown` 构建目标，产出首个官方 Wasm 音源插件包。

### 第三阶段：全面拥抱与旧模式安全收敛
1. 建立 Wasm 插件开发者脚手架与自动化 CI 编译模板（第三方开发者一次提交，自动产出跨平台 `.wasm`）；
2. 对既有 JNI 模式标记为 `@Deprecated`，逐步引导生态开发者迁移至安全沙箱 Wasm 方案。
