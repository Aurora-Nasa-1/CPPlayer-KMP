# 冗余 / 死代码审计（2026-09-26）

> **订正（2026-09-30）**：Tier 1 第 1 项 `LoginScreen.kt` **已删除**（由 `AccountScreen.kt` +
> `AccountStore.kt` 取代）；Tier 4 第 10 项（登出不清用户资料）**已修复** ——
> `AccountScreenModel.logout()` 现在会调 `AppModel.clearUserProfile()`。
> 两处均已在正文标注，**`clearUserProfile` 因此不再是死代码**。

审计范围：`core/` + `app/` + `app-android/` 共 **244 个 `.kt` 文件、5565 条声明**（`reference/` 只读，不参与）。

方法：① 去注释/去字符串（但**保留 `${...}` 模板表达式**）后建立全仓标识符索引；
② 逐条声明比对引用计数；③ **每一条候选都用 `grep -w` 人工复核**。
第 ① 步的机械结果（7 条私有候选、116 条全局候选）经复核后**绝大多数是假阳性** —— 见文末「不要动」。

> ⚠️ **执行前提**：本表 Tier 1 / Tier 2 的目标文件**大部分正处在未提交的在途改动中**（`git status` 显示 `M`）。
> 在途工作不受 git 保护，且本工作区可能有并行会话在编辑同一批文件。**建议先让在途改动落地再动手**，
> 见文末「执行顺序」。

---

## Tier 1 · 整个文件 / 整个抽象已死

| # | 位置 | 行数 | 证据 | 备注 |
|---|------|------|------|------|
| 1 | ~~`app/.../ui/screen/LoginScreen.kt`~~ | 485 | ✅ **已删除（2026-09-30）** | 原证据：`class LoginScreen` 全仓 **0 引用**；导航**全是显式** `navigator.push(X())`，无反射注册表（`registry` / `::class` 零命中）。**已由 `AccountScreen.kt` + `AccountStore.kt` 取代**，编译验证无残留引用。⚠️ 其 `logout()` 未清用户资料的缺口已在新实现里修好（见 Tier 4 第 10 项） |
| 2 | `app/.../ui/screen/SettingsDetailScreen.kt` | 257 | 0 引用 | 已被 `SettingsSubScreens.kt`（`Appearance`/`UiLogic`/`Storage`/`Sponsor` 四个 Screen）+ `SettingsScreen.kt` 的 `SettingsDetail.*` 分支取代 |
| 3 | `app/.../ui/screen/CommentScreen.kt` | 109 | `class CommentScreen` 0 引用 | 评论 UI 已内联在 `PlayerScreen.kt:832`。⚠️ **`CommentScreenModel` 本身是活的**（`PlayerScreen` 在用），删文件时须保留 |
| 4 | `core/.../music/MusicSource.kt` 第 **22–63** 行的 `interface MusicSource` | 42 | 0 实现方、0 调用方 | 真正在用的是 `UnifiedMusicSource`（`UnifiedMusicSourceImpl` 实现；被 `DownloadEngine` / `MediaDownloadManager` / `IntegrationService` / `PlaybackControllerImpl` 消费）。⚠️ 同文件的 `PlaylistSummary`/`PlaylistDetail`/`TrackSummary`/`SongUrl`/`SearchResult`/`ArtistSummary` **是活的**，只能删接口本身 |
| 5 | `core/.../api/MusicApiServiceFactory.kt` | 56 | 唯一"引用"是 `MusicBackend.kt:114` 的 **KDoc 提及**；`instance`/`cachedInstance`/`init`/`reset` 全无调用方 | 早期兼容垫片，功能已被 `MusicBackend` 完全取代。⚠️ 删前先确认无**仓库外**集成方（见「不要动」） |

---

## Tier 2 · 活文件内的死声明（零引用）

| 位置 | 声明 | 备注 |
|------|------|------|
| `core/.../MusicBackend.kt` | `updateReadyState()` *(private)* | 唯一出现处是自身声明；**同样的逻辑在 669–670 行被内联重复了一遍** → 重构残留（不是状态机 bug，`Ready` 迁移在 669 / 769 另有入口） |
| `core/.../MusicBackend.kt` | `cachedApi` | 已标 `@Deprecated("请使用统一访问入口 unifiedSource")` 且 0 引用 → 可安全删 |
| `core/.../MusicBackend.kt` | `outputConfig` | 只是 `activeOutputConfig` 的公开别名；0 引用（`activeOutputConfig` 内部 15 处） |
| `core/.../MusicBackend.kt` | `activeProviderName()` | 0 引用；兄弟 `activeProviderId()` 24 处 |
| `core/.../MusicBackend.kt` | `pushTransport()` / `clearReceiverQueue()` | 0 引用；兄弟 `pushQueue()` 6 处、`probeReceiver()` 4 处 → 推送面板只接了 2/4 个入口，**疑似漏接线而非刻意保留** |
| `core/.../monitor/HealthMonitor.kt` | `getStats()` / `getAllStats()` / `getStatsByMethod()` | 0 引用；UI 走 `getRecentRecords()` + `overallLevelFlow` / `recordsFlow` |
| `core/.../provider/ProviderManager.kt` | `addOnProviderChangedListener()` / `removeOnProviderChangedListener()` / `getCurrentProviderName()` | 0 引用；响应式通知已由 `currentProviderFlow` 承担 |
| `core/.../BackendState.kt` | `isUnsupported` / `getOrThrow()` | 0 引用（`Unsupported` 子类型本身是活的） |
| `core/.../local/LocalMusicSource.kt` | `LocalSongMetadata.bitrateKbps` | 0 引用（同组 `sampleRate` / `bitDepth` 是活的） |
| `core/.../playback/PlatformPlayer.kt` | `supportsExclusiveAudio` | 接口默认 `false`，0 读取方 |
| `core/.../playback/PlaybackControllerImpl.kt` | `ensureOrderScopeSafe()` | 空函数体 + `/* placeholder for future constraints */`，`playQueue` 里调了一次 → **空转占位** |
| `app/.../AppModel.kt` | `isFirstRun` / `localServerConfig()` / `clearUserProfile()` | 0 引用（`clearUserProfile` 另见 Tier 4） |
| `app/.../ui/component/IconButtons.kt` | `LargeIconButton` | 0 引用 |
| `app/.../ui/component/UiFoundation.kt` | `CpSpacing.touchTarget` | 0 引用 |
| `app/.../ui/theme/Shape.kt` | `CpShapes.sheet` | 0 引用 |
| `app/.../ui/screen/DownloadsScreen.kt` | `DownloadsLibraryTab` *(internal)* | 0 引用 |
| `app/.../ui/screen/PlaybackSettingsScreen.kt` | `PlaybackSectionHeader` *(private)* | 0 引用 |
| `app/.../ui/component/ExpressiveKit.kt` ⚠️**在途新文件** | `CpWavyProgressIndeterminate` / `ExpressiveSectionHeader` | 0 引用；新增不久，可能是刻意预留 —— **先与在途作者确认** |

---

## Tier 3 · 结构性冗余（构建 / 常量层）

6. **`core/.../provider/ProviderFactory.kt` 在 `androidMain` 与 `desktopMain` 字节级完全相同**
   （12 行 `actual fun createJniProvider`，`diff` 输出为空）。`jvmMain` 已被两边 `dependsOn`，
   把 actual 挪到 `jvmMain` 即可删掉整个重复文件 —— 与既有做法一致
   （`PlatformSupport` 的注释就写着「jvmMain 提供 JVM 共享 actual（Android + Desktop 共用）」）。

7. **`gradle/libs.versions.toml` 有 4 条声明无任何构建脚本引用**（全仓 `.kts` 零命中）：
   `mp3spi`、`jflac-codec`、`jlayer`（纯 Java 音频解码器）、`voyager-koin`（Voyager 的 Koin DI）。
   与现有方案（桌面音频走 rodio Rust JNI、DI 走 `rememberScreenModel`）不符，属早期方案残留。

8. **`CPMediaId.RESOURCE_AUDIO` / `RESOURCE_VIDEO` 零引用**，而实际构造处用裸字面量
   （`DownloadsScreenModel.kt:198`：`"local://audio/${item.path}"`）。
   二选一：改用常量，或删常量 —— 现状是**同一规则有两份表达**，
   与 `ARCHITECTURE.md` §5 里「令牌校验写两遍导致漂移」是同类问题。

9. `MusicApiMethod` 的 `PLAYLIST_TAGS_UPDATE` / `DJ_SUBLIST_FULL` / `DJ_PROGRAM_DETAIL` / `API` 零引用。
   **但这是镜像上游 400+ API 的常量表** —— 建议按既有先例（`COMMENT_NEW` 已标注「无调用方」）
   **加注释而非删除**。

---

## Tier 4 · 疑似漏接线（可能不是死代码，而是 bug）

10. ~~**登出不清用户资料**~~ —— ✅ **已修复（2026-09-30）**。
    原问题：`AppModel.clearUserProfile()` 的 KDoc 写着「登出后调用」，但当时唯一的登出实现
    `LoginScreen.logout()`（`LoginScreen.kt:397`）**没有调用它**，只调了 `authRepository.logout()` +
    `cookieStorage.clear()` + `refreshUserProfile()`，于是 `_userProfile` 在登出后不会被置空。
    现况：`LoginScreen.kt` 已删除，登出改为 `AccountScreenModel.logout()`
    （`AccountScreen.kt:762`），**已调用 `AppModel.clearUserProfile()`**（`:768`），
    并补上了 `AccountStore.setActive(providerId, null)` 与 `refreshAccounts()`。
    ⇒ `clearUserProfile` 现在是**活的**，不要按「死代码」删它。

11. `MusicBackend.updateReadyState` 的重复实现（Tier 2 首条）说明 `BackendState.Ready` 的迁移逻辑
    存在两份，其中一份已死 —— 建议保留内联那份、删掉函数，或反过来抽成单一入口。

---

## Tier 5 · 明确不要动（假阳性 & 项目已声明的刻意保留）

- **`@Serializable` DTO 属性**：`Song.albumArtUrl`、`UserProfile.signature/gender/province/city/...`、
  `Message.fromUserId`、`Artist.picUrl` 等的"零引用"是**假阳性** ——
  kotlinx.serialization 由编译器插件生成读写代码，属性必须保留。
- **`CachedMusicApiService.callApiCached`**：全仓 0 调用方，但 `ARCHITECTURE.md` §5 明确记载
  「作为**流式**入口保留，与读透共用同一份缓存与键」。**不要删。**
- **`MusicApiServiceFactory`**：`ARCHITECTURE.md` §5 记载它属刻意的兼容出口。
  本仓库内 0 调用方，但**集成方可能仍在用** —— 删前须确认。
- **`desktopTest` 里的 `*Test` 类与 `cleanUp()`**：JUnit 反射发现，零引用是正常的。
- **跨源集同名文件**：`PlatformContext` / `PlatformInfo` / `PlatformSupport` / `HttpClientFactory` /
  `CreateLocalMediaSource` 是标准 `expect`/`actual` 拆分，**不是重复**。
- **`symbolMismatchHint` / `levelText` / `requiresToken`**：看似零引用，实际用在嵌套字符串模板里
  （`"${symbolMismatchHint("...")}"`）—— 机械扫描会误报，必须 grep 复核。

---

## 执行顺序

**可立即安全执行**（文件当前干净，无在途冲突）：

- Tier 1 第 4 项 —— `MusicSource.kt` 的 `interface MusicSource`（22–63 行）
- Tier 1 第 5 项 —— `MusicApiServiceFactory.kt`（确认无外部集成方后）
- Tier 2 —— `HealthMonitor` 3 个查询、`ProviderManager` 3 个成员、`BackendState` 2 个访问器、
  `LocalSongMetadata.bitrateKbps`、`IconButtons.LargeIconButton`、`CpShapes.sheet`
- Tier 3 第 6 项 —— 两个 `ProviderFactory.kt` 合并进 `jvmMain`
- Tier 3 第 7 项 —— 清 4 条无用版本目录条目
- Tier 3 第 8 项 —— `CPMediaId` 常量二选一（推荐**改用常量**）

**建议等在途改动落地后再做**（目标文件正被修改）：

- Tier 1 第 1 项 —— ~~`LoginScreen.kt`~~ ✅ **已删除（2026-09-30）**
- Tier 1 第 2/3 项 —— `SettingsDetailScreen.kt` / `CommentScreen.kt` 两个整文件
- Tier 2 中位于 `MusicBackend.kt` / `AppModel.kt` / `PlatformPlayer.kt` / `PlaybackControllerImpl.kt` /
  `UiFoundation.kt` / `DownloadsScreen.kt` / `PlaybackSettingsScreen.kt` / `ExpressiveKit.kt` 的声明

**建议单独决策**（涉及产品行为，不宜当清理做）：

- ~~Tier 4 第 10 项 —— 登出路径是否应清空用户资料~~ ✅ **已定：应清空，且已实现**（见 Tier 4 第 10 项）
- Tier 2 的 `pushTransport()` / `clearReceiverQueue()` —— 是补接线还是删
