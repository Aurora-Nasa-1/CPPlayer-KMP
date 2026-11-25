# 缓存、图片管线与低内存运行架构

> 适用读者：核心层维护者、客户端性能优化与 Provider 宿主开发者。

本文说明 CPPlayer 如何把 **Native Image 低堆（`-Xmx100m`）**、**全链路缓存**、**宿主运行态同步**、**多档封面防重下**、以及 **Skia / 渲染循环零分配** 串成统一闭环。

---

## 1. 全链路总览

```text
UI 渲染 & Coil 图片请求
  ├─ CpImageRequest / CoverUrls.resized ──► HostTrafficPolicy（托盘 / 省流像素预算 + 多档 URL）
  ├─ DesktopCachedCroppedFetcher / AndroidLocalReuseFetcher
  │     ├─ 1. 命中精确裁剪磁盘缓存 (~/.cpplayer/cache/images/cropped) ──► 零网络直出
  │     ├─ 2. 命中原图 ("orig:<canonical>") 或更高档位磁盘缓存 ─────► 本地裁剪/降采样直出（零网络）
  │     └─ 3. 托盘/省流/离线兜底命中已有任意小图 ────────────────────► 零网络直出
  └─ SwrCache ──► HostTrafficPolicy 允许时才做 stale 后台换新
        │
        ▼
Core 后端源码层 (ProviderHost + ScopedProviderRuntime)
  ├─ ProviderHost
  │     ├─ 只读接口 Singleflight 并发合并（引用计数取消安全）
  │     ├─ 会话级 Bounded Read Cache（最多 48 条，Mutation 自动失效）
  │     └─ 后台 / 省流 / 离线延长 stale 宽限窗口
  └─ ScopedProviderRuntime.http()
        ├─ 并发 GET 请求合并
        ├─ RFC 条件缓存 (ETag / If-None-Match / Last-Modified / 304)
        └─ 暴露可选只读 hostState() 给 SDK 插件
```

---

## 2. 宿主运行态与防重访链路

### 2.1 `HostTrafficPolicy` 状态同步
位置：`core/src/commonMain/kotlin/cp/player/core/provider/HostTrafficPolicy.kt`

宿主通过 `HostTrafficPolicy` 向前端图片请求、业务 `SwrCache`、后端 `ProviderHost` 与 SDK `ProviderRuntime` 同步当前状态：
- `inBackgroundTray`：窗口隐藏到托盘（由桌面端 `DesktopSkiaLifecycle.onMinimizeToTray / onRestoreFromTray` 自动驱动）；
- `dataSaver`：省流模式开关；
- `offlineMode`：离线模式标志；
- `activeImageResizeRule` / `activeCustomCoverResolver`：活跃音源的可选多档封面规则。

各层响应方式：
1. **UI / `SwrCache`**（`app/src/commonMain/kotlin/cp/player/app/repository/SwrCache.kt`）：处于托盘后台 / 省流 / 离线时，命中 `stale` 窗口只返回缓存结果，**不再悄悄启动后台网络刷新协程**。
2. **`ProviderHost`**（`core/src/commonMain/kotlin/cp/player/core/provider/typed/ProviderHost.kt`）：
   - 对安全只读接口启用并发合并（Singleflight）与有界 LRU 读缓存（`MAX_READ_CACHE_ENTRIES = 48`），写操作（`mutation`）与会话失效（`invalidate`）自动清空读缓存；
   - 后台/省流/离线模式下复用 stale 宽限窗口，不唤醒底层插件与网络；
   - 后台压缩时调用 `trimCache()` 裁至热点 8 条。
3. **`ScopedProviderRuntime`**（`core/src/jvmMain/kotlin/cp/player/core/provider/typed/ScopedProviderRuntime.kt`）：
   - 对非敏感、允许缓存的 `GET` 请求自动执行 `If-None-Match` / `If-Modified-Since` 条件验证；
   - 仅将无 `Cookie`/`Authorization` 且响应无 `Set-Cookie`/`no-store`/`private` 的公共 HTTP 响应写入桌面端 `~/.cpplayer/cache/sdk-http` 磁盘缓存，防止认证凭证落盘或跨账号串号；
   - 通过 `hostState()` 向插件提供只读运行态快照。

---

## 3. 图片缓存：32MB 上限与“零冗余下载”闭环

### 3.1 双端 32MB 内存上限与双层磁盘目录
- **桌面端**（`app/src/desktopMain/kotlin/cp/player/app/platform/DesktopImageCacheTuning.kt`）：
  - Coil `MemoryCache` 默认 **32MB**（支持 `-Dcp.coil.cacheMb=16..128` 覆盖）；
  - 原始磁盘缓存：`~/.cpplayer/cache/images/original`（256MB）；
  - 裁剪磁盘缓存：`~/.cpplayer/cache/images/cropped`（128MB，`DesktopCroppedThumbDiskCache`）。
- **Android**（`app/src/androidMain/kotlin/cp/player/app/platform/AndroidImageCacheTuning.android.kt`）：
  - Coil `MemoryCache` 收紧为 **32MB**，开启 `.allowRgb565(true)`，DiskCache 上限 256MB。

### 3.2 规范分桶、稳定资产键与多档尺寸解析
位置：`core/src/commonMain/kotlin/cp/player/core/provider/ProviderImageResizeRule.kt`、`app/src/commonMain/kotlin/cp/player/app/ui/util/CoverUrls.kt`、`app/src/commonMain/kotlin/cp/player/app/ui/util/CpImageRequest.kt`

1. **5 档标准像素分桶**：`128 / 256 / 512 / 1024 / 1440`，统一由 `CoverUrlResolver.quantizeBucketPx` 收敛，`HostTrafficPolicy.preferredImagePx` 在托盘后台压到 `128px`、省流模式压到 `256px`。
2. **稳定资产主键（`canonicalCoverKey`）**：自动剥离 `param=`、音源自定义清理参数及常见 CDN 临时签名参数（`sign`、`expires`、`token`、`X-Amz-Signature` 等）作为缓存键；真实 HTTP 请求仍保留原始完整 URL 与签名。
3. **高档位本地向下衍生（0 网络下载）**：
   - 桌面端 `DesktopCachedCroppedFetcher` 与 `DesktopCroppingDecoder`、以及 Android 端 `AndroidLocalReuseFetcher` 在发起网络前按顺序检查：
     1. 精确尺寸裁剪缓存；
     2. 原图快照 `orig:<canonical>`；
     3. 磁盘上任意更高分辨率分档缓存（`1440 -> 1024 -> 512 -> 256 -> 128`）；
     4. 托盘/省流/离线受限模式下已有任意低档位本地缓存。
   - 命中任意本地源文件后直接在 Skia / Bitmap 解码期抽取缩略采样、居中裁剪并落盘对应目标分档，完全不发重复 HTTP 请求。

---

## 4. Native Image `-Xmx100m`、Skia 生命周期与核心循环零分配

- **仅对 GraalVM Native Image 约束 `-Xmx100m`**：`app/build.gradle.kts` 设置 `graalvm.maxHeapSize = "100m"` 与 `-R:MaxNewSize=24m`；普通 JVM 运行参数维持不变。
- **存活集压缩与本地流式解析**：
  - `MusicReadCaches` 与 `PlaybackLyricsLoader` 收紧默认条数上限，后台/托盘触发 `trimMemoryForBackground()` 裁剪非活跃项；
  - `LocalCoverStore` 与 `LocalArtwork.desktop.kt` 通过 `RandomAccessFile` 流式扫描 ID3/MP4 结构定位封面偏移量，消除最大 8MB/32MB 的临时堆数组峰值。
- **Skia 缓存、MSAA 与托盘 Surface 生命周期**：
  - `DesktopRenderTuning`：`<=1080p` GPU 资源上限 32MB、2K/4K 上限 48MB（`skiko.gpu.resourceCacheLimit`），2K/4K（`>=2560x1440`）默认关闭 MSAA（`MsaaMode.AUTO`）。
  - `DesktopSkiaLifecycle`：每 1.5s 调用 `DirectContext.performDeferredCleanup(5000)` 主动回收超过 5 秒未使用的离屏纹理，静止超过 5 秒清理 scratch 资源与 `Graphics.purgeFontCache()`；最小化到托盘时关闭/置空宿主 `Surface`/`RenderTarget`、释放 `DirectContext` GPU 资源并裁剪应用内存缓存，恢复窗口时重建。
- **核心渲染与轮询循环 0 分配**：
  - `DesktopHiResEngine`：替换 100ms JSON 轮询和事件解析中的动态 `Regex` 编译为手写零正则扫描；
  - `PlaybackLyricsLoader` / `PlaybackControllerImpl`：`computeLyricIndex` 增加顺序播放 `O(1)` 游标快路径并复用 `LyricPushTrack`；
  - `FluidBackground` / `FluidPlatform.desktop.kt`：桌面流体背景迁移为 Skia `RuntimeEffect`（SkSL）着色器直出，共享场景模型原地更新不产生每帧 `List`/`Brush`/`Pair` 分配；
  - `PosterLyricScene` / `PosterWallCanvas`：复用 `Path.rewind()`、`drawWithCache`、`drawPoints` 与单位径向渐变缩放绘制。
