# KMP-PRO

> CP-Player 的 **Provider 插件系统 + API 层** Kotlin Multiplatform 移植版。
>
> 从原 Android 项目 `app/src/main/java/cp/player/` 移植，抽象为可在 Android 与
> Desktop（JVM）复用的库模块。额外封装了带**网络缓存 + 异步加载**与**深度 API
> 健康监控（错误回退 / 警告 / 错误三级分类）**的 `CachedMusicApiService`。

---

## 目录结构

```
KMP-PRO/
├── settings.gradle.kts            # 独立 Gradle 工程
├── build.gradle.kts               # 根
├── gradle.properties             # android.useAndroidX 等
├── gradle/libs.versions.toml     # 版本目录
└── kmp-pro/
    ├── build.gradle.kts          # KMP 模块：androidTarget + jvm("desktop")，共享 jvmMain
    └── src/
        ├── commonMain/           # 纯跨平台代码
        ├── jvmMain/              # JVM 共享（Android + Desktop）：ServerSocket / Zip / ELF / BinaryProvider / HttpClient
        ├── androidMain/          # Android 独有：Context、SharedPreferences、Build.SUPPORTED_ABIS、JniProvider
        └── desktopMain/          # Desktop 独有：~/.kmp-pro 持久化、不支持的 JNI 模块
```

目标层级：`commonMain → jvmMain → { androidMain, desktopMain }`。

## 构建验证

```bash
cd KMP-PRO
./gradlew :kmp-pro:compileKotlinDesktop   # JVM 编译
./gradlew :kmp-pro:compileDebugKotlinAndroid   # Android 编译
./gradlew :kmp-pro:desktopJar              # Desktop 可执行 jar
./gradlew :kmp-pro:assembleDebug           # Android AAR
```

📗 Gradle 9.4.1 / Kotlin 2.1.0 / AGP 8.7.3 / Ktor 3 / kotlinx-serialization 1.7 / coroutines 1.9 / datetime 0.6
（wrapper 复用主工程 `gradle/wrapper`）。

---

## 模块源码映射（原项目 → KMP-PRO）

| 来源（原项目） | KMP-PRO | 说明 |
|------|---------|------|
| `model/*`（纯数据） | `commonMain/.../model/*` | @Serializable 化；移除 Compose/Media3 依赖的 `PlayerUiState`（属 UI 层） |
| `provider/BackendProvider.kt` | `commonMain/.../provider/BackendProvider.kt` | `Context` → `expect class PlatformContext` |
| `provider/ModuleManifest.kt` | `commonMain/.../provider/ModuleManifest.kt` | Gson → kotlinx-serialization `@Serializable` |
| `provider/HttpProvider.kt` | `commonMain/.../provider/HttpProvider.kt` | OkHttp → **Ktor**（commonMain） |
| `provider/BinaryProvider.kt` | `jvmMain/.../provider/BinaryProvider.kt` | ProcessBuilder + Ktor localhost（JVM 共享） |
| `provider/JniProvider.kt` | `androidMain/.../provider/JniProvider.kt` | `System.load` + `external fun`，仅 Android |
| `provider/ProviderManager.kt` | `commonMain/.../provider/ProviderManager.kt` | 单例 → 实例化（依赖注入 `SettingsStorage`） |
| `provider/ModuleManager.kt` | `commonMain/.../provider/ModuleManager.kt` | 文件操作经 `expect object PlatformSupport` |
| `api/MusicApiMethod.kt` | `commonMain/.../api/MusicApiMethod.kt` | 纯常量，零改动 |
| `api/MusicApiService.kt` | `commonMain/.../api/MusicApiService.kt` | `JsonObject` → `JsonElement` |
| `api/MusicApiServiceImpl.kt` | `commonMain/.../api/MusicApiServiceImpl.kt` | Gson 解析 → kotlinxserialization；保留 cookie 注入、validate、`callWithAllProviders` 容灾 |
| `api/MusicApiServiceFactory.kt` | `commonMain/.../api/MusicApiServiceFactory.kt` | 单例持有 `MusicApiServiceImpl` + `CachedMusicApiService` |
| `monitor/HealthMonitor.kt` | `commonMain/.../monitor/HealthMonitor.kt` | 新增**三级分类** `HealthLevel { OK, WARNING, ERROR }` |
| `util/UserPreferences`（cookie/最近 provider） | `commonMain/.../util/SettingsStorage` + 各平台 actual | SharedPreferences(Android) / Properties 文件(Desktop) |

---

## 核心特性：`CachedMusicApiService`（cache + 异步 + 健康监控）

`cp.player.kmp.cache.CachedMusicApiService` 在 `MusicApiServiceImpl` 之上**再次封装**，
对外暴露 `callApiCached(...): Flow<CacheResult<JsonElement>>`，多值发射：

### 调用流程

```
1) 先返回缓存          → CacheResult.Cached(data, isStale)            （即时）
2) 后台拉取网络        → delegate.callApi(...)
3) 计算新响应指纹     → Fingerprinter.compute(json)                  （"简单数据"比对）
4) 指纹 == 缓存指纹    → CacheResult.NoChange                         （内容未变，无需替换）
   指纹 != 缓存指纹    → CacheResult.Fresh(data)                      （"不同的较大数据"异步回传 + 写回缓存）
5) 响应判为 ERROR      → 多 Provider 容灾 tryFallback(...)
        容灾成功     → CacheResult.Fresh(source = FALLBACK)
        容灾失败     → CacheResult.Error(fallback = 缓存数据)
6) 网络异常            → CacheResult.Error(message, fallback = 缓存)
```

- **指纹（`Fingerprinter`）**：从响应抽取 `code` + 顶层数组长度 + 主数据数组的 `id` 列表（前 64 个，去重排序）+ 版本位。
  增删/重排条目指纹变化；改无关字段不影响。以此廉价判断"是否有不同的较大数据需要回传"。
- **缓存接口**：`ApiCache`（默认 `InMemoryApiCache` LRU），键 `providerId#method#sortedParams#cookieHash`。
- **写/动作类接口不缓存**（登录、点赞、发评论、打卡等），见 `isCacheable(...)`。
- **`CacheConfig`**：`freshTtlMs`、`maxEntries`、`enableFallback`、`enableCache`。

### 三级健康分类（`HealthMonitor.HealthLevel`）

| 级别 | 含义 | 处理 |
|------|------|------|
| `OK` | 响应正常 | 直接使用 |
| `WARNING` | 不符合预期但勉强可用（缺可选字段、慢响应、空数据、异常 code） | 使用但附 `warnings` 告警；记录 |
| `ERROR` | 不可用（解析失败、Provider 不支持 code=-1、MALFORMED_RESPONSE） | 触发**多 Provider 错误回退**；失败则带缓存降级 |

分类规则见 `HealthMonitor.classify(ResponseWarning)` 与 `MusicApiServiceImpl.classifyLevel(...)`。
`overallLevelFlow` 反映最近 100 条记录的综合等级，供 UI 顶部状态指示。

---

## 接入示例

### 1) Android `Application.onCreate()`

```kotlin
import cp.player.kmp.util.initKmpAndroidContext
import cp.player.kmp.util.toPlatformContext

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 1. 注入平台 Context（SharedPreferences 工厂需要）
        initKmpAndroidContext(this)
        // 2. 初始化整条 API/Provider/模块栈
        MusicApiServiceFactory.init(
            context = toPlatformContext(),                 // Android：Context → PlatformContext
            settings = cp.player.kmp.util.defaultSettingsStorage()
        )
    }
}
```

### 2) 使用（缓存版）

```kotlin
val cached = MusicApiServiceFactory.cachedInstance

// Flow：先发缓存，再发 Fresh/NoChange/Error
cached.callApiCached(MusicApiMethod.PLAYLIST_DETAIL, mapOf("id" to "123"))
    .collect { result ->
        when (result) {
            is CacheResult.Cached -> show(result.data, stale = result.isStale)
            is CacheResult.Fresh -> show(result.data)
            is CacheResult.Error -> showError(result.message, fallback = result.fallback)
            is CacheResult.NoChange -> { /* 与缓存一致，无需更新 UI */ }
        }
    }

// 直通版（原始同步接口，cookie 自动注入 + 健康记录）
val raw = MusicApiServiceFactory.instance
val json = raw.getPlaylistDetail(123L)
```

### 3) Desktop

```kotlin
MusicApiServiceFactory.init(
    context = cp.player.kmp.util.PlatformContext.EMPTY,
    settings = cp.player.kmp.util.defaultSettingsStorage()
)
```

---

## 与原项目差异说明

- **单例 → 实例化**：`ProviderManager` / `ModuleManager` 改为构造注入依赖的类，便于测试与多实例。
- **Gson → kotlinx-serialization**：模型 `@Serializable`；`MusicApiService` 返回 `JsonElement`。
- **OkHttp → Ktor**（OkHttp 引擎在 JVM 实现），HttpProvider 可放 commonMain。
- **移除 PlayerUiState** 等含 Compose/Media3 依赖的 UI 状态类（不属于 Provider/API 层）。
- **HealthMonitor** 扩展三级分类与 `overallLevelFlow`。
- **新增 cache 层**：`CachedMusicApiService` + `Fingerprinter` + `ApiCache`，实现"缓存先返回 + 简单数据比对 + 差异较大数据异步回传"。

> ⚠️ JNI 模块（`.so`）仅在 Android 端可用；Desktop 上 `createJniProvider` 返回 `null`，对应模块标记不可用。