# 私信新消息系统通知 — 设计与实现

> 状态：**P0 已实现**（2026-10-05）。P1 的设置页也已落地；P2（托盘真 Toast / WorkManager / 免打扰时段）未做。
> ⚠️ 交付时的编译验证受阻：`:core` 通过，`:app` 被**另一个会话在途的 i18n 迁移**挡住
> （`CpStrings.kt` 里 `InsightsStrings` 与新建 `InsightStrings.kt` 的单复数改名未完成）。
> 用临时 `typealias` 顶掉那处后编译，`:app` 侧残留的 8 条错误**全部**落在对方文件里，
> 本次新增/修改的文件 **0 条**。等对方落地后需重跑 `:app:compileKotlinDesktop` 与 `:app:desktopTest`。
>
> 涉及代码：`app/src/commonMain/.../notify/`（新增）、`.../platform/PlatformNotifications.kt`（新增 expect）、
> `ui/screen/MessagesScreen.kt` / `MessagesPane.kt` / `ChatScreen.kt` / `MessageNotifySettingsScreen.kt`、
> `AppModel.kt`、`App.kt`、`app-android/MainActivity.kt`、`desktopMain/Main.kt`、`platform/DesktopTray.kt`。

## 实现对照（落地后回填）

| 方案里的点 | 落地位置 |
|---|---|
| 订阅表 + 游标 + 引导标志 | `notify/MessageNotifyPrefs.kt`（键都带 providerId；索引整表存 JSON 数组） |
| 纯判定 `shouldNotify` | `notify/MessageNotifyPolicy.kt` + `desktopTest/.../MessageNotifyPolicyTest.kt` |
| 轮询（默认不启动） | `notify/MessageWatchService.kt`；启停唯一入口 `AppModel.syncMessageWatch()` |
| 平台通知能力 | `platform/PlatformNotifications.kt` + android / desktop 两个 actual |
| 桌面托盘（通知 + 常驻） | `desktopMain/.../platform/DesktopTray.kt`（懒创建；无托盘时如实返回 false） |
| 右键 / 长按开关 | `MessagesScreen.kt` 的 `ContactRowItem`（桌面 `CpContextMenu` / 安卓 `MessageNotifySheet`） |
| 行内铃铛 | `ContactRow` 的 `trailingContent`（在未读角标之前） |
| 首次引导 | `MessageNotifySheet.kt` 的 `MessageNotifyGuideSheet` + `rememberMessageNotifyGuide` |
| 设置页 | `MessageNotifySettingsScreen.kt`（总开关 / 权限提示 / 已订阅列表 / 桌面关窗去向） |
| 关窗确认 + 不再提示 | `desktopMain/.../ui/component/DesktopCloseDialog.kt` + `platform/DesktopCloseBehavior.kt` |
| **顺便修掉的 `msg/private/mark/read` 500** | 见下节 |

## 顺带修掉的 `msg/private/mark/read` 500（与 §1 的 P0 无关，但同批交付）

**结论：上游根本没有这个端点，模块不该声明支持它。**

实测（本机探针 `JniProvider.nativeCallApi`，2026-10-05）：

- `msg/private/mark/read` → `{"code":500,"msg":"API error (code=404)"}`；
- 同探针下 `/api/msg/private/history` → `400 参数错误`（路径存在、参数不对），
  `/api/msg/private/send` 正常 ⇒ 探针没问题，是目标路径不存在；
- `weapi` / `eapi` 两种加密 + 11 个候选路径（`mark/read`、`read`、`msgs/read`、`session/read`…）**全 404**；
- 上游 `SPlayer-Dev/ncm-api-rs` 的 `src/api/` 里**没有** `msg_private_mark_read.rs`（本项目自己加的）；
- 官方 `NeteaseCloudMusicApi` 的 `module/` 只有 6 个 `msg_*`，无 mark-read；GitHub 全站搜不到 `msg/private/markread`。

⇒ 官方客户端标记私信已读走**云信 IM 长连接**，不经 HTTP API（与 `listen/together/play/invitation/send` 不存在同因）。

**改法（两处，都在「能力声明」这条既有链路上）**：

1. `reference/netease-module-rust/build_module_desktop.ps1` / `.sh` / `build_module.sh` 生成的
   `manifest.json` 里把该方法的 `apiMap` 标为 `"unsupported"`；
   本机已安装的 `~/.cpplayer/modules/cp_api/manifest.json` 同步改掉（否则要等重新打包才生效）。
   于是 `ProviderManager.callApi` **直接短路**（返回 `code = -1`，**不发请求**）。
2. `core/.../api/MusicApiServiceImpl.kt` 的 `classifyLevel`：把「失败且唯一原因是
   `UNSUPPORTED_BY_PROVIDER`」判为 **WARNING 而不是 ERROR** —— 能力缺失不是音源故障。
   不这样改的话，诊断页会被一个从未被支持的端点拖成红色（记录仍保留，`warningTypes` 里查得到）。

---

## 1. 目标 / 非目标

### 目标
1. 通过**消息 API**（`msg/recentcontact` / `msg/private` / `msg/private/history` / `pl/count`）检测到联系人发来的新私信时，
   用**系统通知**（Windows 通知气泡 / Android 通知栏）提醒用户，点击通知直达该会话。
2. 桌面**右键**、安卓**长按**联系人行 → 切换「这个人的新消息是否推送」。
3. **默认全不推送、不监听**：没有任何联系人被开启时，后台不做任何私信请求。
4. **第一次进入消息界面**时给一次引导，讲清「默认不打扰 + 右键/长按开启」。

### 非目标（v1 明确不做）
- 不做**服务端推送**（APNs/FCM/WebSocket）。本方案是**本地轮询**，只在**进程存活期间**有效（见 §7）。
- 不做通知里的**远程头像**（需要预下载位图，收益低）。v1 用应用图标 / 单色剪影。
- 不做「按消息内容关键词过滤」「按会话静音时段」等进阶规则（列入 §9 P2）。
- 不改上游 `Message` / `Contact` 数据模型（字段已够用，见 §4.1）。

---

## 2. 现状盘点（决定方案的三个硬事实）

| # | 事实 | 证据 | 影响 |
|---|------|------|------|
| 1 | **目前没有任何私信轮询** | `AppModel.refreshUnreadMessages()` 只在「启动 / 登录成功 / 打开消息页」被调用，KDoc 明确写「不做轮询」 | 要「推送」就必须**新建一条轮询管线**，这是本方案的主体 |
| 2 | **没有任何系统通知基础设施** | 全仓 `SystemTray` / `NotificationCompat` 只在 `app-android/PlaybackMediaSessionService.kt` 的 **media3 播放通知**里出现；桌面端为零 | 桌面要从零建托盘通知；安卓要新建**独立通知渠道**（不能复用 media3 那条） |
| 3 | **私信是「按音源 + 按账号」的** | cookie 按 `cookie_$providerId` 存（`ProviderManager`），缓存键含 cookieHash | 订阅关系必须按 `(providerId, uid)` 落盘，切音源/切号不能串 |

已有的可复用件：
- `SocialRepository.getContacts()` —— 一次请求拿回**全部**最近联系人（含 `lastMessage` / `lastMessageTime` / `unreadCount`）。
- `AppModel.modelScope` —— 单例 `CoroutineScope`（`SupervisorJob + Dispatchers.Default`），**不绑 Compose 生命周期**，适合挂常驻轮询。
- `SettingsStorage`（字符串 KV）+ `AppModel.settings`（`defaultSettingsStorage()`）。
- `cp.player.app.platform` 的 `expect/actual` 惯例；`ctxOrNull`（Android `Context` 桥）。
- `CpContextMenu`（桌面右键，支持 `isSelected` 高亮）；`LegacyListItem.onLongClick`（安卓长按）。
- Android `POST_NOTIFICATIONS` **已在 manifest 声明**，且 `MainActivity` 启动时已申请。

---

## 3. 总体架构

```
        ┌──────────────────────────── app / commonMain ────────────────────────────┐
        │                                                                          │
  UI ───┤  MessagesScreen / MessagesPane ──► ContactRow ──(右键/长按)──► 开关订阅   │
        │        │                                    ▲                            │
        │        │ 首次进入 → MessageNotifyGuideSheet   │ 铃铛指示订阅态              │
        │        ▼                                    │                            │
        │  MessageNotifyPrefs  ◄── 读写 SettingsStorage（订阅表 + 游标 + 引导标志）  │
        │        ▲                                                                 │
        │        │ 读订阅/游标                                                      │
        │  MessageWatchService（AppModel.modelScope 常驻循环）                       │
        │        │  ──► SocialRepository.getContacts()（只读，1 请求/轮）            │
        │        │  ──► MessageNotifyPolicy.shouldNotify(...)  ← 纯函数，可单测      │
        │        ▼                                                                 │
        │  expect fun postMessageNotification(...)                                 │
        └────────┼─────────────────────────────────────────────────────────────────┘
                 │
     ┌───────────┴────────────┐
     ▼                        ▼
 Android actual            Desktop actual
 NotificationManager       java.awt.SystemTray + TrayIcon.displayMessage
 （独立渠道 cp_messages）    （懒创建托盘图标；ActionListener → 拉起窗口 + 路由）
     │
     ▼ 点击 → PendingIntent(MainActivity, extras) → App 路由到 ChatScreen
```

**分层原则**（与本仓既有约定一致）：
- **判定逻辑做成纯函数**（`MessageNotifyPolicy`），不碰 IO、不碰平台 —— 与 `CookieLogin` 同款做法，便于单测。
- **平台能力走 `expect/actual`**，Android 用 `ctxOrNull`，桌面用 AWT；不把 `android.*` 泄漏进 commonMain。
- **数据落盘只有一个入口**（`MessageNotifyPrefs`），UI 与轮询都通过它读写，不各写各的 key。

---

## 4. 数据与持久化

### 4.1 为什么不需要改 `Message` / `Contact`

`Contact` 已有 `lastMessageTime` 与 `unreadCount`，`Message` 已有 `time` 与 `isMe`。
判定「有没有新的**别人发来**的消息」只需这两个字段组合（见 §5.2），**不需要新增模型字段**。

### 4.2 SettingsStorage 键设计

| Key | 值 | 说明 |
|-----|----|------|
| `msg_notify_guide_done` | `"true"` | 首次引导是否已看过（§6.3） |
| `msg_notify_master` | `"true"/"false"`，默认 `"false"` | 总开关。**默认关** ⇒ 连轮询都不启动 |
| `msg_notify_subs_<providerId>` | JSON 数组 `["123","456"]` | 该音源下被订阅的 uid 列表（**索引**，见下） |
| `msg_notify_cursor_<providerId>_<uid>` | epoch ms | 已提醒到的 `lastMessageTime` 水位，防重复 |

> ⚠️ **必须存一份 uid 索引**：`SettingsStorage` 只有 `getString/putString/contains`，**没有 key 枚举**，
> 靠扫描 key 找订阅是做不到的。索引与「每联系人一条」两者都在同一个写入路径里维护，避免漂移。
> 也可以把订阅表整张做成一个 JSON map（`{ "providerId": ["uid"...] }`）—— 实现二选一，但**只能选一个**。

### 4.3 多音源 / 多账号隔离

- 所有 key 都带 `providerId`；`providerId` 取 `AppModel.activeProviderId()`。
- 登出 / 切号时：**不删订阅**（用户回来还想收），但轮询立即停止、游标不推进；
  重新登录后 `refreshUserProfileAwait()` 里已有的「账号代际」机制会重挂轮询（见 §7.2）。
- 上游音源不支持私信（`getContacts()` 返回空且报错）⇒ 引导页照常展示，但开关置灰并写明「当前音源不支持私信」。

---

## 5. 检测与推送管线

### 5.1 轮询循环（`MessageWatchService`）

```
AppModel.startMessageWatch()        // App.kt 启动块调用，幂等
  └─ 循环（delay 45s）:
       0. 前置检查：已登录 && master 开 && 订阅数 > 0  —— 任一不满足 → 本轮跳过（不发请求）
       1. contacts = socialRepository.getContacts()      // 1 次请求，拿回全部联系人
       2. for c in contacts where isSubscribed(providerId, c.userId):
             if MessageNotifyPolicy.shouldNotify(c, cursor, activePeerUid, now):
                  postMessageNotification(key, c.nickname, c.lastMessage, ...)
                  setCursor(providerId, c.userId, c.lastMessageTime)
```

- 频率：默认 **45s**（可调常量）。首次进消息页/刚订阅时**立即跑一轮**，不等下一个 tick。
- 幂等：`startMessageWatch()` 重复调用只保留一个 job（`watchJob?.cancel()` 后重启，同 `refreshUserProfile` 模式）。
- 停止：登出 / master 关 / 订阅清零 → `watchJob?.cancel()`。

### 5.2 判定条件（纯函数，重点单测对象）

```kotlin
fun shouldNotify(
    contact: Contact,
    cursorMs: Long?,          // 已提醒水位；null = 从未提醒过
    activePeerUid: Long?,     // 用户此刻正在看的会话
    nowMs: Long,
): Boolean {
    if (contact.unreadCount <= 0) return false          // 没有「未读的对方消息」→ 不打扰
    if (contact.userId == activePeerUid) return false    // 正开着这个会话 → 不打扰
    val t = contact.lastMessageTime ?: return false
    if (cursorMs != null && t <= cursorMs) return false  // 没有新进展 → 不重复提醒
    if (nowMs - t > STALE_WINDOW_MS) return false        // 太旧（如 > 24h 的积压）→ 不炸一屏
    return true
}
```

- **为什么用 `unreadCount > 0` 而不是只比时间**：`Contact.lastMessage` 可能是**我自己**发的最后一条。
  时间会前进但没有「别人发来的新消息」。`unreadCount`（上游 `newMsgCount`）是服务端口径的
  「未读的对方消息」，用它才能保证「只对别人发来的消息提醒」。同时它天然解决「我在别处读过」——读到即清零。
- **回落**：部分音源走 `msg/private` 回落时拿不到 `newMsgCount`（恒 0）⇒ 永不提醒。
  兜底策略（P1）：对这类音源，改为**只对订阅的联系人**调 `getMessages(uid, myUid)`，
  取最新一条 `!isMe && time > cursor` 的消息。请求数 = 订阅数，比「全量联系人」重，所以只在
  检测到「`unreadCount` 长期恒 0 而 `lastMessageTime` 在推进」时自动切换。
- `STALE_WINDOW_MS` 默认 24h：避免冷启动时把一堆历史未读一次性炸成几十条通知。

### 5.3 通知合并

同一轮里多个联系人都有新消息：**每人一条**（key 不同，系统会各显示一条），不做合并；
但**同一联系人**连续多轮只保留**最新一条**（同 key 覆盖，Android `setOnlyAlertOnce(false)` + 固定 id；
桌面因 AWT 无覆盖语义，用「同一 uid 5 分钟内不重复弹」的节流）。

---

## 6. UI 交互

### 6.1 平台能力抽象（`platform/PlatformNotifications.kt`，新增 expect）

```kotlin
expect fun messageNotificationsSupported(): Boolean          // 平台能不能发系统通知
expect fun canPostMessageNotifications(): Boolean            // 系统层面当前是否被允许（权限/开关）
expect fun requestMessageNotificationPermission()            // 主动申请（安卓 13+ 弹框；桌面空操作）
expect fun postMessageNotification(
    key: String, providerId: String, peerUid: Long,
    title: String, body: String,
)
expect fun cancelMessageNotification(key: String)
/** 点击通知的回调注册（桌面托盘点击 / 安卓 PendingIntent 回跳）。null = 注销。 */
expect fun setOnMessageNotificationClick(handler: ((providerId: String, peerUid: Long) -> Unit)?)
```

**Android actual**
- `NotificationManager` + 独立渠道 `cp_messages`（名「私信消息」，`IMPORTANCE_DEFAULT`，**与 media3 播放渠道分开** ——
  否则用户在系统里关掉「播放控制」会把私信通知一起关掉）。
- 图标复用已有的 `R.drawable.ic_stat_playback` 风格单色剪影，新增一个 `ic_stat_message`（由 `scripts/gen_app_icon.py` 生成）。
- 点击 → `PendingIntent.getActivity(MainActivity, extras{providerId, peerUid})`；
  `MainActivity.onCreate/onNewIntent` 读 extras，转成一次性全局「待打开会话」，由 `App.kt` 消费后 `push(ChatScreen(...))`。
- `canPostMessageNotifications()`：API 33+ 查 `POST_NOTIFICATIONS`；< 33 查 `NotificationManagerCompat.areNotificationsEnabled()`。
- 依赖：`androidx.core.app.NotificationCompat` —— 已在类路径上（`libs.androidx.media.compat` 传递引入）。
  **若编译期发现缺失，就显式加 `androidx.core:core-ktx`**，别去用裸 `android.app.Notification.Builder`（要自己写 API 24/25 分支）。

**Desktop actual**
- `java.awt.SystemTray` + `TrayIcon`：**懒创建** —— 只有「至少一个订阅」时才装托盘图标，订阅清零时移除。
  理由：平时不占托盘位，不改变现有用户观感。
- 弹出：`trayIcon.displayMessage(title, body, MessageType.NONE)`。
- 点击：AWT 气球点击**不带 payload**，只触发 `ActionListener`。做法：维护「最近一条通知」的
  `(providerId, peerUid)`，点击时拉窗口到前台 + 按该 payload 路由。多通知并发时路由到最新一条（v1 可接受）。
- `messageNotificationsSupported()` = `SystemTray.isSupported()`；无托盘环境（部分 Linux / headless）返回 false，
  设置页与引导页如实显示「本平台不支持系统通知」。
- ⚠️ **Windows 限制**：AWT 气球在 Win10+ 是否进「操作中心」取决于系统通知设置；未注册 AUMID 时可能只显示传统气球。
  这是 v1 可接受的行为，若要「真·Toast」需走 WinRT（`WinRT ToastNotification`）—— 列入 P2，不在本方案。

### 6.2 右键 / 长按切换订阅

在 `MessagesScreen`（窄屏整页）与 `MessagesPane`（桌面双栏左栏）的 `ContactRow` 上挂两套入口，**共用同一个订阅读写**：

- **桌面（右键）**：用现成的 `CpContextMenu` 包住行（默认 Initial 消费，符合它的 KDoc —— 行内菜单要在页面级 passive 菜单**内层**）：
  ```
  [🔔] 开启新消息通知        （已开启时 → [🔕] 关闭新消息通知，isSelected = true）
  ───────────
  标记为已读
  ```
- **安卓（长按）**：给 `ContactRow` 加 `onLongClick`（`LegacyListItem` 已支持），弹 `ModalBottomSheet`：
  标题「`昵称` 的新消息通知」+ 一个 `Switch` + 一句说明「默认不推送，只对开启的人提醒」。
  ⚠️ 不要给 `ContactRow` 塞 `combinedClickable` —— `LegacyListItem` 内部已按 `onLongClick != null` 切换实现，走它的参数。
- **行内指示**：`ContactRow.trailingContent` 在未读 `Badge` **前面**加一个 `Icons.Filled.Notifications`（尺寸 16dp，
  `onSurfaceVariant`）仅当已订阅时显示，让「谁开了推送」一眼可见（否则只能靠右键去翻）。

**开关动作**（两端共用）：
```
toggle(contact):
  if (!master) { master = true }            // 首次开启某人时，自动把总开关带上（否则用户会以为没生效）
  setSubscribed(providerId, uid, !isSubscribed)
  if (订阅数 == 0) stopWatch() else { startWatch(); 立即跑一轮 }
  if (刚开启 && !canPostMessageNotifications()) requestMessageNotificationPermission()
```

### 6.3 首次进入引导

- 触发：`MessagesScreen` / `MessagesPane` 首次组合，且 **已登录**、**加载出了联系人**、`msg_notify_guide_done != true`。
- 形式：一次性 `ModalBottomSheet`（不用全局 Onboarding 的第 5 页 —— 那是「装音源/登录」级别的门槛，
  本功能是**上下文引导**，放在消息页更贴切）。
- 文案要点（三行以内）：
  - 「私信**默认不会**打扰你，不会在后台监听。」
  - 「想收谁的新消息：**右键**（电脑）/ **长按**（手机）联系人 → 开启新消息通知。」
  - 主按钮「我知道了」→ 置 `msg_notify_guide_done = true`。
- ⚠️ **必须幂等**：Voyager 路由页 pop 回来会**重新进入组合、`LaunchedEffect` 全部重跑**（见 `AGENTS.md` / `MEMORY.md`）。
  判据读 `settings`，关闭时**立即落盘**；不要只在 ScreenModel 里记布尔（那是跨组合存活但**跨进程不存活**）。
  引导只在「有联系人」时弹，避免和空态/错误态叠在一起。

### 6.4 设置页入口（建议 P1）

新增 `SettingsEntry(id = "msg_notify", group = CONNECTIVITY, ...)` → `MessageNotifySettingsScreen`：
- 总开关（默认关）+ 一句「开启后仅在应用运行时轮询，不会省电后台常驻」；
- 已订阅联系人列表（头像 + 昵称 + `Switch`，可直接关）；
- 平台不支持时整页只显示说明。

这样用户不必「记得去消息页右键」才能改，也给了「一键全关」的出口。

---

## 7. 生命周期与后台限制（必须对用户诚实）

| 平台 | 能否收到 | 说明 |
|------|---------|------|
| 桌面 | 应用窗口**开着**（可最小化）时能收到 | 关掉窗口 = 进程退出 = 收不到。除非做「最小化到托盘常驻」（P2，目前**没有**托盘常驻实现） |
| 安卓 · 前台/可见 | 能收到 | 正常轮询 |
| 安卓 · 后台且**正在放歌** | 通常能收到 | 媒体前台服务保活进程（`PlaybackMediaSessionService`）⇒ 轮询协程继续跑 |
| 安卓 · 后台且**没放歌** | **不可靠** | 进程可能被 LMK/厂商 ROM 杀掉。真正可靠需 `WorkManager` 周期任务（最小 **15 分钟**）或服务端推送 —— 均**不在本方案** |

**落地要求**：
1. 引导页与设置页都必须写明「仅在应用运行时有效」，不能让用户以为这是真·推送。
2. 轮询只在「已登录 + master 开 + 订阅数 > 0」时进行；默认全关 ⇒ **零额外请求**（满足「默认全不监听」）。
3. 每次 tick 只发 **1 次** `getContacts()`（不是每联系人一次），回落策略（§5.2）才按订阅数发。

---

## 8. 涉及文件清单

**新增**
```
app/src/commonMain/kotlin/cp/player/app/notify/MessageNotifyPrefs.kt      // 订阅表/游标/引导标志 读写
app/src/commonMain/kotlin/cp/player/app/notify/MessageNotifyPolicy.kt     // shouldNotify 纯函数
app/src/commonMain/kotlin/cp/player/app/notify/MessageWatchService.kt     // 轮询循环（挂 AppModel.modelScope）
app/src/commonMain/kotlin/cp/player/app/platform/PlatformNotifications.kt // expect
app/src/androidMain/kotlin/cp/player/app/platform/PlatformNotifications.android.kt
app/src/desktopMain/kotlin/cp/player/app/platform/PlatformNotifications.desktop.kt
app/src/commonMain/kotlin/cp/player/app/ui/component/MessageNotifyGuideSheet.kt
app/src/commonMain/kotlin/cp/player/app/ui/screen/MessageNotifySettingsScreen.kt   // P1
app/src/desktopTest/kotlin/cp/player/app/notify/MessageNotifyPolicyTest.kt
app/src/desktopTest/kotlin/cp/player/app/notify/MessageNotifyPrefsTest.kt
```

**修改**
```
app/src/commonMain/.../ui/screen/MessagesScreen.kt   // ContactRow 右键菜单 + 引导
app/src/commonMain/.../ui/screen/MessagesPane.kt     // 同上（左栏）
app/src/commonMain/.../ui/screen/ChatScreen.kt       // 发布/清除「当前正在看的会话」（抑制用）
app/src/commonMain/.../AppModel.kt                   // startMessageWatch/stopMessageWatch + activePeerUid
app/src/commonMain/.../App.kt                        // 启动块调用 startMessageWatch()
app/src/commonMain/.../ui/screen/SettingsRegistry.kt // P1 设置入口
app/src/commonMain/.../i18n/CpStrings*.kt            // 新文案（本仓设置页走 i18n，消息页目前是硬编码，新增文案建议一并进 i18n）
app-android/src/main/kotlin/cp/player/app/MainActivity.kt // 通知点击 extras → 待打开会话
app-android/src/main/res/drawable-*/ic_stat_message.png    // 由 scripts/gen_app_icon.py 生成
```

---

## 9. 分阶段实施

**P0（最小可用）**
1. `MessageNotifyPrefs` + `MessageNotifyPolicy` + 单测。
2. `PlatformNotifications` 三端 actual（安卓渠道 + 桌面托盘）。
3. `MessageWatchService` + `AppModel` 启停 + `App.kt` 挂载。
4. 消息页右键 / 长按开关 + 行内铃铛 + 首次引导。

**P1**
5. 设置页 `MessageNotifySettingsScreen` + 总开关。
6. `unreadCount` 恒 0 音源的**历史回落**检测（§5.2）。
7. 通知点击直达 `ChatScreen` 的完整链路（安卓 extras 路由 / 桌面 ActionListener）。

**P2（可选）**
8. 桌面「最小化到托盘常驻」（关闭窗口不退出，托盘菜单退出）。
9. 安卓 `WorkManager` 周期兜底（≥15min）或服务端推送调研。
10. 免打扰时段、通知里显示头像、Windows WinRT 真 Toast。

---

## 10. 待确认问题

1. **桌面端是否接受「必须开着应用才收得到」**？若不能接受，需要先做托盘常驻（P2 提前到 P0 前置）。
2. **安卓后台**是否投入 `WorkManager`（最小 15 分钟，实时性差）？还是明确「只在播放/前台时有效」？
3. 默认频率 45s 是否合适？（越短越实时，也越费电/流量。）
4. 通知**点击**行为：直接跳到该会话，还是先跳消息列表？（本方案取「直达会话」。）
5. 首次引导的形式：一次性底部弹层（本方案）还是行内可关闭横幅（不打断）？

---

## 11. 验证方式

- **单测**：`MessageNotifyPolicyTest`（`unreadCount=0` 不提醒 / 游标不前进不提醒 / 正在看该会话不提醒 /
  超过 24h 不提醒 / 正常提醒）；`MessageNotifyPrefsTest`（读写往返、**跨音源隔离**、登出后订阅保留）。
- **离线渲染**：`desktopTest` 出图核对 `MessageNotifyGuideSheet` 与「行内铃铛」版式
  （复用 `SidebarPreviewTest` 的 `ImageComposeScene` 模式，产物核对完删除）。
- **真机/真桌面**：桌面托盘气球点击回跳；安卓 13+ 权限被拒 → 开关时提示重新授权；无托盘 Linux 环境 → 如实显示「不支持」。
- **回归**：确认默认状态下**不发任何私信请求**（默认全关 = 零监听）。
