# MusicBackend 核心设计

<cite>
**本文引用的文件**
- [MusicBackend.kt](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/MusicBackend.kt)
- [ProviderManager.kt](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/provider/ProviderManager.kt)
- [ModuleManager.kt](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/provider/ModuleManager.kt)
- [MusicApiServiceImpl.kt](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/api/MusicApiServiceImpl.kt)
- [CachedMusicApiService.kt](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/cache/CachedMusicApiService.kt)
- [PlaybackControllerImpl.kt](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/playback/PlaybackControllerImpl.kt)
- [BackendState.kt](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/BackendState.kt)
- [MediaDownloadManager.kt](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/download/MediaDownloadManager.kt)
- [LocalMediaSource.kt](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/local/LocalMediaSource.kt)
- [AndroidLocalMediaSource.kt](file://kmp-pro/src/androidMain/kotlin/cp/player/kmp/local/AndroidLocalMediaSource.kt)
- [DesktopLocalMediaSource.kt](file://kmp-pro/src/jvmMain/kotlin/cp/player/kmp/local/DesktopLocalMediaSource.kt)
</cite>

## 更新摘要
**变更内容**
- 新增懒初始化的本地媒体源（localMedia）属性，支持平台特定的媒体扫描和导入功能
- 新增懒初始化的下载管理器（downloadManager）属性，提供统一的媒体下载管理接口
- 完善了依赖关系和资源管理机制，确保本地媒体源和下载管理器的正确初始化顺序
- 增强了后端架构的完整性和可扩展性

## 目录
1. [简介](#简介)
2. [项目结构](#项目结构)
3. [核心组件](#核心组件)
4. [架构总览](#架构总览)
5. [详细组件分析](#详细组件分析)
6. [依赖关系分析](#依赖关系分析)
7. [性能考量](#性能考量)
8. [故障排查指南](#故障排查指南)
9. [结论](#结论)
10. [附录：初始化与使用示例](#附录初始化与使用示例)

## 简介
本文件聚焦 MusicBackend 作为统一入口点的设计模式，详细说明其单例模式的线程安全实现、构造函数参数依赖注入、内部组件协调机制，并逐一解释 providerManager（Provider 管理）、moduleManager（模块管理）、musicApiImpl（API 服务）、cachedMusicApi（缓存服务）、playbackController（播放控制）、localMedia（本地媒体源）、downloadManager（下载管理器）等核心属性的职责。文档还包含初始化流程中各组件的创建顺序与依赖关系，以及正确的初始化与使用方式。

## 项目结构
MusicBackend 位于 KMP 公共模块中，围绕"后端统一入口"组织：
- 入口与状态机：MusicBackend、BackendState
- Provider 与模块：ProviderManager、ModuleManager、ProviderCookieStorage
- API 层：MusicApiServiceImpl、CachedMusicApiService
- 播放层：PlaybackControllerImpl、平台播放器抽象
- 本地媒体：LocalMediaSource（平台实现）、下载管理：MediaDownloadManager
- 工具与上下文：PlatformContext、SettingsStorage、HealthMonitor

```mermaid
graph TB
UI["前端界面"] --> MB["MusicBackend"]
MB --> PM["ProviderManager"]
MB --> MM["ModuleManager"]
MB --> API["MusicApiServiceImpl"]
MB --> CAPI["CachedMusicApiService"]
MB --> PC["PlaybackControllerImpl"]
MB --> LM["LocalMediaSource"]
MB --> DM["MediaDownloadManager"]
API --> PM
CAPI --> API
PC --> API
PC --> MB
LM --> DM
DM --> LM
```

图表来源
- [MusicBackend.kt:73-161](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/MusicBackend.kt#L73-L161)
- [ProviderManager.kt:31-145](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/provider/ProviderManager.kt#L31-L145)
- [ModuleManager.kt:19-137](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/provider/ModuleManager.kt#L19-L137)
- [MusicApiServiceImpl.kt:28-101](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/api/MusicApiServiceImpl.kt#L28-L101)
- [CachedMusicApiService.kt:46-88](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/cache/CachedMusicApiService.kt#L46-L88)
- [PlaybackControllerImpl.kt:35-44](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/playback/PlaybackControllerImpl.kt#L35-L44)
- [MediaDownloadManager.kt:26-77](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/download/MediaDownloadManager.kt#L26-L77)
- [LocalMediaSource.kt:19-56](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/local/LocalMediaSource.kt#L19-L56)

章节来源
- [MusicBackend.kt:30-72](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/MusicBackend.kt#L30-L72)

## 核心组件
- MusicBackend：统一入口，封装生命周期、状态机、Provider 管理、数据访问、播放控制与健康监控。
- ProviderManager：管理当前活跃 Provider、切换、端口分配、Cookie 存储、恢复上次选择。
- ModuleManager：扫描/导入/删除模块，加载 BackendProvider，维护可用 Provider 列表。
- MusicApiServiceImpl：将调用转发到当前 Provider，自动注入 Cookie，健康监控与响应校验。
- CachedMusicApiService：带缓存与多 Provider 容灾回退的 API 封装，返回 Flow 的多值结果。
- PlaybackControllerImpl：队列管理、URL 解析、歌词抓取、听歌打卡、自动下一首、音质降级等。
- LocalMediaSource：本地媒体源接口，支持平台特定的媒体扫描、导入和管理。
- MediaDownloadManager：媒体下载管理器，提供统一的下载任务管理和持久化功能。

章节来源
- [MusicBackend.kt:73-161](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/MusicBackend.kt#L73-L161)
- [ProviderManager.kt:31-145](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/provider/ProviderManager.kt#L31-L145)
- [ModuleManager.kt:19-137](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/provider/ModuleManager.kt#L19-L137)
- [MusicApiServiceImpl.kt:28-101](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/api/MusicApiServiceImpl.kt#L28-L101)
- [CachedMusicApiService.kt:46-88](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/cache/CachedMusicApiService.kt#L46-L88)
- [PlaybackControllerImpl.kt:35-44](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/playback/PlaybackControllerImpl.kt#L35-L44)
- [LocalMediaSource.kt:19-56](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/local/LocalMediaSource.kt#L19-L56)
- [MediaDownloadManager.kt:26-77](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/download/MediaDownloadManager.kt#L26-L77)

## 架构总览
MusicBackend 通过依赖注入组合多个子系统，对外暴露统一接口；内部以 StateFlow 驱动状态机，保证 UI 可观察且一致。新增的本地媒体源和下载管理器进一步完善了后端架构，提供了完整的媒体管理能力。

```mermaid
classDiagram
class MusicBackend {
-context
-settings
-providerManager
-moduleManager
-musicApiImpl
-cachedMusicApi
-cache
-localMediaLazy
-downloadManagerLazy
+stateFlow
+unifiedSource
+playbackController
+importModule()
+switchProvider()
+deleteModule()
+reset()
}
class ProviderManager {
+currentProvider
+currentProviderFlow
+switchProvider()
+restoreLastProvider()
+callApi()
}
class ModuleManager {
+providersFlow
+importModule()
+deleteModule()
+getAvailableProviders()
}
class MusicApiServiceImpl {
+callApi()
+getSongUrl()
+getLyric()
+scrobble()
}
class CachedMusicApiService {
+callApiCached()
-tryFallback()
}
class PlaybackControllerImpl {
+state
+playQueue()
+togglePlayPause()
+skipNext()
+refreshLyrics()
+setQuality()
}
class LocalMediaSource {
+scan()
+importFolder()
+removeItem()
+items()
+isScanningFlow
+addExternalItems()
}
class MediaDownloadManager {
+tasksFlow
+taskFlow()
+start()
+enqueue()
+pause()
+resume()
+cancel()
+retry()
+remove()
+isDownloaded()
+shutdown()
}
MusicBackend --> ProviderManager : "管理活跃Provider"
MusicBackend --> ModuleManager : "加载/导入模块"
MusicBackend --> MusicApiServiceImpl : "云API"
MusicBackend --> CachedMusicApiService : "缓存+容灾"
MusicBackend --> PlaybackControllerImpl : "播放控制"
MusicBackend --> LocalMediaSource : "本地媒体管理"
MusicBackend --> MediaDownloadManager : "下载管理"
CachedMusicApiService --> MusicApiServiceImpl : "委托"
PlaybackControllerImpl --> MusicApiServiceImpl : "歌词/打卡"
MediaDownloadManager --> LocalMediaSource : "下载完成登记"
```

图表来源
- [MusicBackend.kt:73-161](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/MusicBackend.kt#L73-L161)
- [ProviderManager.kt:31-145](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/provider/ProviderManager.kt#L31-L145)
- [ModuleManager.kt:19-137](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/provider/ModuleManager.kt#L19-L137)
- [MusicApiServiceImpl.kt:28-101](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/api/MusicApiServiceImpl.kt#L28-L101)
- [CachedMusicApiService.kt:46-88](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/cache/CachedMusicApiService.kt#L46-L88)
- [PlaybackControllerImpl.kt:35-44](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/playback/PlaybackControllerImpl.kt#L35-L44)
- [LocalMediaSource.kt:19-56](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/local/LocalMediaSource.kt#L19-L56)
- [MediaDownloadManager.kt:26-77](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/download/MediaDownloadManager.kt#L26-L77)

## 详细组件分析

### MusicBackend：统一入口与单例
- 单例模式与线程安全
  - 伴生对象持有 @Volatile INSTANCE，init 方法使用 synchronized(COMPA) 双重检查锁定，确保多线程下仅创建一次实例。
  - reset 时释放资源并清空单例引用，便于测试或重新初始化。
- 构造函数参数依赖注入
  - 通过私有构造接收 PlatformContext、SettingsStorage、ProviderManager、ModuleManager、MusicApiServiceImpl、CachedMusicApiService、ApiCache，解耦具体实现，便于替换与测试。
- 内部组件协调
  - stateFlow 驱动状态机，结合 moduleManager 与 providerManager 计算终态。
  - importModule 在导入后若无活跃 Provider，则自动激活首个可用 Provider。
  - playbackController 惰性创建，绑定 UnifiedMusicSource、MusicApiServiceImpl 与平台播放器。
- 关键属性职责
  - providerManager：当前活跃 Provider 管理与切换、Cookie 隔离、端口分配。
  - moduleManager：模块扫描/导入/删除、可用 Provider 列表。
  - musicApiImpl：云 API 调用、Cookie 注入、健康监控与响应校验。
  - cachedMusicApi：带缓存与多 Provider 容灾回退的 API 封装。
  - playbackController：播放队列、URL 解析、歌词抓取、听歌打卡、自动下一首、音质降级。
  - localMedia：懒初始化的本地媒体源，支持平台特定的媒体扫描和导入功能。
  - downloadManager：懒初始化的下载管理器，提供统一的下载任务管理接口。

**更新** 新增了 localMedia 和 downloadManager 两个懒初始化属性，完善了本地媒体管理和下载功能。

章节来源
- [MusicBackend.kt:73-161](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/MusicBackend.kt#L73-L161)
- [MusicBackend.kt:156-206](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/MusicBackend.kt#L156-L206)
- [MusicBackend.kt:180-248](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/MusicBackend.kt#L180-L248)
- [MusicBackend.kt:314-371](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/MusicBackend.kt#L314-L371)

### ProviderManager：Provider 管理
- 职责
  - 维护 currentProvider、currentPort，提供 switchProvider、restoreLastProvider、startServer 等方法。
  - 通过 SettingsStorage 持久化最近活跃的 Provider ID。
  - 提供 callApi 在 IO 调度器上执行，按 Provider.apiMap 映射方法名，处理不支持的情况。
- 错误与恢复
  - 切换失败时回滚到上一个 Provider 并更新流状态。
  - 端口占用时尝试回退端口，最多尝试次数受控。

章节来源
- [ProviderManager.kt:31-145](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/provider/ProviderManager.kt#L31-L145)

### ModuleManager：模块管理
- 职责
  - 扫描 modulesDir，加载所有子目录模块，按 manifest.json 创建 BackendProvider。
  - 支持 importModule（解压 zip、移动目录、加载模块）、deleteModule（删除目录并更新列表）。
  - 初始化时恢复上次选择的 Provider，若无则自动选择第一个可用 Provider。
- 错误处理
  - lastLoadError 记录最近一次导入/加载失败原因，供 UI 展示。

章节来源
- [ModuleManager.kt:19-137](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/provider/ModuleManager.kt#L19-L137)

### MusicApiServiceImpl：API 服务
- 职责
  - 将请求转发给当前 Provider，自动注入 Cookie（区分认证类接口）。
  - 对响应进行 JSON 解析、字段校验、健康监控分类（OK/WARNING/ERROR）。
  - 提供 getSongUrl 的容错逻辑（302 版本失败回退到 v1），以及 scrobble、lyric 等常用方法。
- 容灾
  - callWithAllProviders 依次尝试所有已加载 Provider，优先当前 Provider，记录 wasFallback。

章节来源
- [MusicApiServiceImpl.kt:28-101](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/api/MusicApiServiceImpl.kt#L28-L101)
- [MusicApiServiceImpl.kt:189-221](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/api/MusicApiServiceImpl.kt#L189-L221)
- [MusicApiServiceImpl.kt:429-479](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/api/MusicApiServiceImpl.kt#L429-L479)

### CachedMusicApiService：缓存服务
- 职责
  - 对幂等读类接口启用缓存，键由 providerId、method、params、cookieHash 组成。
  - 先发射缓存（可能 stale），后台拉取新数据，指纹比对决定是否写回缓存。
  - 健康监控 ERROR 时触发多 Provider 容灾回退，成功则标记 FALLBACK。
- 配置
  - enableCache、freshTtlMs、enableFallback 等策略可控。

章节来源
- [CachedMusicApiService.kt:18-52](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/cache/CachedMusicApiService.kt#L18-L52)
- [CachedMusicApiService.kt:60-88](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/cache/CachedMusicApiService.kt#L60-L88)
- [CachedMusicApiService.kt:90-140](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/cache/CachedMusicApiService.kt#L90-L140)
- [CachedMusicApiService.kt:142-180](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/cache/CachedMusicApiService.kt#L142-L180)

### PlaybackControllerImpl：播放控制
- 职责
  - 队列管理（含 shuffle、repeat）、URL 解析、歌词抓取、听歌打卡、自动下一首、音质降级。
  - 通过 UnifiedMusicSource 获取曲目详情与播放 URL，结合 cookieProvider 注入请求头。
  - 监听平台播放器状态，折叠进 PlaybackUiState，提供丰富 UI 状态。
- 健壮性
  - 播放失败时自动降级到 standard 音质重试一次。
  - 收藏状态本地乐观更新，失败回滚。

章节来源
- [PlaybackControllerImpl.kt:18-44](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/playback/PlaybackControllerImpl.kt#L18-L44)
- [PlaybackControllerImpl.kt:495-556](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/playback/PlaybackControllerImpl.kt#L495-L556)
- [PlaybackControllerImpl.kt:558-575](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/playback/PlaybackControllerImpl.kt#L558-L575)
- [PlaybackControllerImpl.kt:719-753](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/playback/PlaybackControllerImpl.kt#L719-L753)

### LocalMediaSource：本地媒体源
- 职责
  - 提供统一的本地媒体管理接口，支持平台特定的实现。
  - 支持媒体扫描、文件夹导入、条目移除、外部条目登记等功能。
  - 通过 StateFlow 提供响应式的媒体列表和扫描状态。
- 平台实现
  - Android：基于 MediaStore 扫描和 SAF 树导入，支持权限检查和增量索引。
  - Desktop：基于文件系统遍历默认音乐/视频目录，支持已导入文件夹管理。

**新增** 懒初始化的本地媒体源，首次访问时创建并装配到 unifiedSource 中。

章节来源
- [LocalMediaSource.kt:19-56](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/local/LocalMediaSource.kt#L19-L56)
- [AndroidLocalMediaSource.kt:40-158](file://kmp-pro/src/androidMain/kotlin/cp/player/kmp/local/AndroidLocalMediaSource.kt#L40-L158)
- [DesktopLocalMediaSource.kt:33-141](file://kmp-pro/src/jvmMain/kotlin/cp/player/kmp/local/DesktopLocalMediaSource.kt#L33-L141)

### MediaDownloadManager：下载管理器
- 职责
  - 提供统一的媒体下载管理接口，支持任务入队、暂停、恢复、取消、重试等操作。
  - 通过 UnifiedMusicSource 获取下载链接，确保只接受合法的 mediaId。
  - 下载完成后自动登记到本地媒体源，source 标记为 DOWNLOADED。
  - 支持任务持久化，应用重启后可恢复下载任务。
- 实现特性
  - 幂等入队：同 mediaId 已完成且文件仍在 → 直接返回已有任务。
  - 自动重试：失败/已取消/文件丢失的任务自动重试。
  - 断点续传：暂停任务保留 .part 文件，恢复时可继续下载。

**新增** 懒初始化的下载管理器，首次访问时创建并在 backendScope 上异步恢复持久化任务。

章节来源
- [MediaDownloadManager.kt:26-77](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/download/MediaDownloadManager.kt#L26-L77)
- [MediaDownloadManager.kt:88-205](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/download/MediaDownloadManager.kt#L88-L205)

## 依赖关系分析
- 初始化顺序与依赖
  - 先创建 ProviderCookieStorage（基于 SettingsStorage）。
  - 再创建 ProviderManager（依赖 settings、cookieStorage）。
  - 创建 ModuleManager（依赖 modulesDir、context），并 init(providerManager)。
  - 创建 MusicApiServiceImpl（依赖 providerManager、cookieStorage）。
  - 创建 CachedMusicApiService（委托 MusicApiServiceImpl，依赖 cache、providerManager、allProviders、config）。
  - 最后创建 MusicBackend（注入以上所有组件），并计算终态。
  - **新增** 初始化完成后调用 bootstrapLocalStack()，按顺序装配 localMedia 和 downloadManager。
- 运行时依赖
  - playbackController 惰性创建，依赖 unifiedSource、musicApiImpl、platform player、backendScope。
  - importModule 依赖 moduleManager 导入，并在必要时调用 switchProviderInternal 激活。
  - **新增** localMedia 和 downloadManager 采用懒初始化，首次访问时才创建。
  - **新增** downloadManager 依赖 localMedia 已装配，确保 unifiedSource 包含本地源。

```mermaid
sequenceDiagram
participant App as "应用"
participant MB as "MusicBackend"
participant PCS as "ProviderCookieStorage"
participant PM as "ProviderManager"
participant MM as "ModuleManager"
participant API as "MusicApiServiceImpl"
participant CAPI as "CachedMusicApiService"
participant LMS as "LocalMediaSource"
participant DMM as "MediaDownloadManager"
App->>MB : init(context, settings, cache, cacheConfig)
MB->>PCS : 创建(基于 settings)
MB->>PM : 创建(settings, cookieStorage)
MB->>MM : 创建(modulesDir, context)
MB->>MM : init(providerManager)
MB->>API : 创建(providerManager, cookieStorage)
MB->>CAPI : 创建(delegate=API, cache, providerManager, allProviders, config)
MB->>MB : 创建自身(注入以上组件)
MB->>MB : stateFromInit()
MB->>MB : bootstrapLocalStack()
MB->>LMS : 懒初始化(localMedia)
MB->>DMM : 懒初始化(downloadManager)
MB-->>App : 返回单例
```

图表来源
- [MusicBackend.kt:330-367](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/MusicBackend.kt#L330-L367)
- [ProviderManager.kt:31-49](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/provider/ProviderManager.kt#L31-L49)
- [ModuleManager.kt:38-48](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/provider/ModuleManager.kt#L38-L48)
- [MusicApiServiceImpl.kt:28-31](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/api/MusicApiServiceImpl.kt#L28-L31)
- [CachedMusicApiService.kt:46-52](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/cache/CachedMusicApiService.kt#L46-L52)
- [MusicBackend.kt:156-206](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/MusicBackend.kt#L156-L206)

章节来源
- [MusicBackend.kt:330-367](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/MusicBackend.kt#L330-L367)
- [MusicBackend.kt:156-206](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/MusicBackend.kt#L156-L206)

## 性能考量
- 缓存策略
  - 仅对幂等读类接口启用缓存，避免写操作被缓存导致数据不一致。
  - 指纹比对减少重复写入，降低 I/O 压力。
- 多 Provider 容灾
  - 健康监控 ERROR 时触发回退，提升可用性；WARNING 仍返回数据但附带告警。
- 懒加载
  - playbackController 惰性创建，减少启动开销。
  - **新增** localMedia 和 downloadManager 采用懒初始化，避免不必要的资源消耗。
- 队列批量解析
  - 解析队列条目时按块批量请求，减少网络往返。
- **新增** 本地媒体扫描优化
  - 增量扫描避免重复处理相同文件。
  - 批量处理（50条/批）提高扫描效率。
- **新增** 下载任务优化
  - 并发下载数限制（默认3个），避免资源过度消耗。
  - 断点续传减少重复下载。

[本节为通用指导，不直接分析具体文件]

## 故障排查指南
- 导入模块失败
  - 查看 moduleManager.lastLoadError，常见原因：解压失败、缺少 manifest.json、移动临时目录失败、ABI 不匹配。
- Provider 未就绪
  - 检查端口是否被占用（ProviderManager 会尝试回退端口），或 JNI/Binary 入口是否存在。
- API 调用异常
  - 关注 HealthMonitor 记录的 level 与 warnings，定位缺失字段、慢响应、不支持功能等问题。
- 播放失败
  - 自动降级到 standard 音质重试；若仍失败，检查 URL 有效性、Cookie 注入、平台播放器兼容性。
- **新增** 本地媒体问题
  - Android：检查媒体读取权限（READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE）。
  - Desktop：确认默认音乐/视频目录存在且可读。
  - 扫描进度流中查看 permissionDenied 标志。
- **新增** 下载任务问题
  - 检查下载根目录配置是否正确。
  - 查看任务状态流中的错误信息。
  - 确认本地文件路径有效且可写。

章节来源
- [ModuleManager.kt:57-86](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/provider/ModuleManager.kt#L57-L86)
- [ProviderManager.kt:65-109](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/provider/ProviderManager.kt#L65-L109)
- [MusicApiServiceImpl.kt:48-101](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/api/MusicApiServiceImpl.kt#L48-L101)
- [PlaybackControllerImpl.kt:540-556](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/playback/PlaybackControllerImpl.kt#L540-L556)
- [AndroidLocalMediaSource.kt:66-97](file://kmp-pro/src/androidMain/kotlin/cp/player/kmp/local/AndroidLocalMediaSource.kt#L66-L97)
- [MediaDownloadManager.kt:110-125](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/download/MediaDownloadManager.kt#L110-L125)

## 结论
MusicBackend 以单例与依赖注入为核心，将 Provider 管理、模块管理、API 服务、缓存与播放控制有机整合，通过状态机与响应式流驱动 UI，提供稳定、可扩展的后端统一入口。**新增的本地媒体源和下载管理器进一步完善了后端架构**，提供了完整的媒体管理能力，包括本地媒体扫描、导入、下载管理等核心功能。其设计兼顾了可测试性、可维护性与跨平台能力，适合在复杂音乐应用中作为核心基础设施。

[本节为总结，不直接分析具体文件]

## 附录：初始化与使用示例
- 初始化
  - 在平台 Application.onCreate 或 JVM main 中调用 MusicBackend.init(context, settings)，可选传入自定义 ApiCache 与 CacheConfig。
  - 首次运行若无模块，状态为 NoProvider，引导用户导入模块。
  - **新增** 初始化完成后会自动装配本地媒体源和下载管理器。
- 导入模块
  - 调用 importModule(zipPath)，若此前无活跃 Provider，会自动激活首个可用 Provider；否则仅加载不切换。
- 切换 Provider
  - 使用 switchProvider 或 switchProviderById，失败时状态保持原样并返回错误。
- 数据访问
  - 推荐使用 unifiedSource 进行统一数据访问；旧版可直接使用 musicApi/cachedApi（已标注废弃）。
- 播放控制
  - 通过 playbackController 进行播放队列管理、播放/暂停、切歌、歌词刷新、音质设置等。
- **新增** 本地媒体管理
  - 通过 localMedia 访问本地媒体源，支持扫描、导入文件夹、管理本地媒体列表。
  - 使用 items() 获取响应式的本地媒体列表。
  - 使用 scan() 触发媒体扫描，监听 ScanProgress 获取扫描进度。
- **新增** 下载管理
  - 通过 downloadManager 进行下载任务管理，支持入队、暂停、恢复、取消、重试等操作。
  - 使用 enqueue() 添加下载任务，传入 CPMediaId 形式的媒体标识。
  - 监听 tasksFlow 获取下载任务状态和进度。
  - 下载完成后自动登记到本地媒体源。

章节来源
- [MusicBackend.kt:30-72](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/MusicBackend.kt#L30-L72)
- [MusicBackend.kt:156-206](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/MusicBackend.kt#L156-L206)
- [MusicBackend.kt:180-248](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/MusicBackend.kt#L180-L248)
- [MusicBackend.kt:330-367](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/MusicBackend.kt#L330-L367)
- [BackendState.kt:5-57](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/BackendState.kt#L5-L57)
- [BackendState.kt:59-130](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/BackendState.kt#L59-L130)
- [LocalMediaSource.kt:19-56](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/local/LocalMediaSource.kt#L19-L56)
- [MediaDownloadManager.kt:26-77](file://kmp-pro/src/commonMain/kotlin/cp/player/kmp/download/MediaDownloadManager.kt#L26-L77)