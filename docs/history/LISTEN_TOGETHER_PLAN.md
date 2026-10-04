# 「一起听」深度集成 + 多音源兼容 —— 方案

> 调研对象：`reference/netease-module-rust/src/api/listentogether_*.rs`（9 个文件）、
> `docs/API.md` §🎧 一起听，以及上游生态交叉验证（HyPlayer / ncm-api-rs /
> QCloudMusicApi / NeteaseCloudMusicApiEnhanced）。
>
> **v3（2026-10-04 09:10）：P0 探针实测已完成**（`§9`），推翻并修正了 v2 的两处结论。
> 本文**只给方案**，不落地代码。

> **版本更正史**
> - v1 → v2：① 「邀请做不成」改为「没有官方一键接口，但有两条确定可行的替代路径」；
>   ② 指出「远端指令随轮询带回」缺乏依据。
> - v2 → **v3（实测）**：② 被**实测推翻** —— `sync/playlist/get` **确实**会把 `playCommand`
>   （指令 + 进度 + 序号）完整读回，**纯 HTTP 轮询即可双向同步，不需要自带信令**。
>   详见 §9。

---

## 0. 结论摘要

| 项 | 结论 | 依据 |
|---|---|---|
| 接口是否可用 | **今天就能调，不用重编模块** | 已部署 dll 里 `listentogether` 路由字符串命中 16 处；已实跑通 |
| 传输层是否需要新增 | **不需要**。纯 HTTP，走现有 JNI 通道 | 9 个端点全是 request/response |
| 是否需要 WebSocket | **不需要**，也不需要自带信令 | 实测 `sync/playlist/get` 可读回指令与进度（§9） |
| 音频面是否需要中继 | **不需要**，也不该做 | 各端各用自己的账号取流 |
| **接受邀请** | **能，现成 API** | `listentogether_accept`（`roomId` + `inviterId`） |
| **发出邀请** | **没有官方一键接口，但能做到** | 分享链接 / 私信投递两条确定路径（§1.2） |
| **远端指令同步** | ✅ **双向可行**（延迟 ≈ 轮询间隔） | 实测：上报的 `PAUSE`/`progress=45000`/`targetSongId` 原样读回（§9.3） |
| **连接态** | ⚠️ HTTP 客户端恒为 `NOT_CONNECTED`（IM 连接态），但**不影响读写** | §9.4 |
| 跨音源互通 | **做不到**，也不该承诺 | 媒体 ID 是 provider 作用域的（§5.3） |
| 心跳间隔 | **30 秒**（服务端 `timeSpan` 给的，别自己猜） | §9.4 |

---

## 1. 接口清单与能力边界

### 1.1 九个端点

全部走 `nativeCallApi` 的通用分发器（`build.rs` 编译期扫描 `src/api/mod.rs` 生成，
`mod listentogether_*` 登记在 mod.rs 第 419–427 行）。

| 方法名 | 实际路径 | 加密 | 关键参数 |
|---|---|---|---|
| `listentogether_room_create` | `/api/listen/together/room/create` | eapi | `refer`（硬编码 `songplay_more`） |
| `listentogether_room_check` | `/api/listen/together/room/check` | eapi | `roomId` |
| `listentogether_accept` | `/api/listen/together/play/invitation/accept` | eapi | `roomId`、`inviterId`、`refer`（硬编码 `inbox_invite`） |
| `listentogether_status` | `/api/listen/together/status/get` | **weapi** | 无参 |
| `listentogether_heatbeat` | `/api/listen/together/heartbeat` | eapi | `roomId`、`songId`、`playStatus`、`progress` |
| `listentogether_play_command` | `/api/listen/together/play/command/report` | eapi | `roomId` + `commandInfo`（**JSON 字符串**） |
| `listentogether_sync_list_command` | `/api/listen/together/sync/list/command/report` | eapi | `roomId` + `playlistParam`（**JSON 字符串**） |
| `listentogether_sync_playlist_get` | `/api/listen/together/sync/playlist/get` | eapi | `roomId` |
| `listentogether_end` | `/api/listen/together/end/v2` | eapi | `roomId` |

内层字段：

- `commandInfo`：`commandType`、`progress`、`playStatus`、`formerSongId`、`targetSongId`、`clientSeq`
- `playlistParam`：`commandType`、`version[]{userId,version}`、`anchorSongId`、`anchorPosition`、`randomList[]`、`displayList[]`

⚠️ **`status` 用 weapi**，其余 8 个用 eapi —— 唯一一处不一致。

⚠️ **`room/check` 不是房间详情**。实测它只返回 4 个字段
（`{copywriting, joinable, status:"AVAILABLE", type:"NORMAL"}`），
**没有成员、没有播放状态** —— npm 文档把它描述成「含在线用户与房间状态」是错的。
**房间详情只能从 `status/get` 拿。**

#### 枚举：**发什么存什么，大小写不敏感**（实测）

实测上报 `"Play"` / `"PAUSE"` 均被原样回读，说明服务端不做枚举校验。
参考取值（HyPlayer C# 强类型 + 上游文档）：

| 字段 | 取值 |
|---|---|
| `play/command.commandType` | `Play`、`Pause`、`Progress`（另有 `Goto`） |
| `play/command.playStatus` | `Play`、`Pause` |
| `sync/list/command.commandType` | `Replace`、`PlayModeChange` |
| `sync/list/command.playMode` | `OrderLoop`、`Random`、`SingleLoop` |
| `refer` | `inbox_invite`（邀请）/ `songplay_more`（建房） |

建议统一用**大写**（服务端自己推导时产出的是 `PLAY`/`PAUSE`）。

#### 已部署二进制里就有这些接口

```bash
grep -a -c "listentogether" ~/.cpplayer/modules/cp_api/ncm_api_rs.dll   # 16
grep -a -c "listen/together"                                            # 11
grep -a -c "cloudsearch"                                                # 8（对照组）
```

⚠️ **本机没有 `strings` 命令**，用它查会得到 0 命中而**误判「模块太旧」** —— 必须 `grep -a`。

### 1.2 邀请与接受

**接受邀请 —— 有现成 API。** `listentogether_accept`，只要 `roomId` + `inviterId`。

**发出邀请 —— 官方 API 里没有这个端点。**
五个独立实现全都只有同样这 9 个端点，没有 invite/send（不是漏移植，是上游没包）。
但「邀请」本质只是**把 roomId + inviterId 送达对方**：

| 路径 | 可行性 | 做法 |
|---|---|---|
| **① 分享链接 / 二维码 / 房号** | ✅ 确定 | 官方格式：`https://st.music.163.com/listen-together/share/?songId=…&roomId=…&inviterId=…`<br>**纯本地拼接，不碰接口**。二维码用已有 `QrCodeGen.kt` |
| **② 私信投递** | ✅ 确定 | `send_text`（POST `/api/msg/private/send`，`type:"text"`）发链接 |
| **③ 直打 `invitation/send`** | ❌ **已实测证实【不存在】** | 正反对照钉死：已知路径 `200`、明确不存在的路径 `404`、三个候选路径（`send` / `send/v2` / `create`）**全 `404`**。上游 `404` 即「路径不存在」 |

**为什么「发出邀请」在 HTTP 侧根本不存在（实测 + 机制解释）**

`accept` 的上游路径是 `/api/listen/together/play/invitation/**accept**` —— 服务端**有**邀请概念，
只是「把它发出去」那一步不在这里。房间响应里有 `chatRoomId`（云信聊天室 id），
说明官方 App 的房间内交互走**云信 IM 长连接**。所以邀请是**一条 IM 富文本消息（卡片）**，
由客户端经 IM 通道发出 —— **HTTP API 侧没有对应端点，是因为这条消息根本不走 HTTP**。

旁证：`/api/msg/private/send` 存在且接受 `type=text`（返回 `200`），
但 `type` 试 `listentogether` / `together_invite` / `invite` 全部得到
`{"code":500,"msg":"Invalid parameter"}`（**不是 404** ⇒ 端点存在、只是取值不被接受）。
即便真存在某个结构化 type，也无法靠猜穷举，且每次尝试都是一次真实发信。

**结论**：邀请只能走「链接 / 房号 → 对方调 `accept`」这条确定路径。

唯一不可复刻的是官方 App「朋友动态页点在线好友一键邀请」—— 那依赖客户端 IM 在线态。

### 1.3 指令同步：**可行**（实测，推翻 v2 判断）

v2 依据「端点名是 `report` 不是 `push`」+「HyPlayer 只写不读」推断 HTTP 只能上报。
**实测证明服务端确实持久化并回吐了 `playCommand`**：

> 上报 `PAUSE` / `progress=45000` / `targetSongId=186016` → 随后
> `sync/playlist/get` 读回 `{"commandType":"PAUSE","playStatus":"PAUSE","progress":45000,
> "targetSongId":"186016","formerSongId":"347230","clientSeq":2,"serverSeq":1791075880363,
> "userId":9084388061}`（原文见 §9.3）

所以：

- **服务端不会「推」**（官方 App 走 IM 长连接接收，HTTP 客户端收不到推送）；
- **但它会「存」，且任何 HTTP 客户端都能轮询读到** ⇒ **双向同步成立**。
- HyPlayer「只写不读」是它的实现选择（不完整），不是能力上限。

**唯一的硬限制**：延迟下限 ≈ 轮询间隔，因为拿不到推送。

---

## 2. 实测清单（P0 状态）

| # | 项 | 状态 |
|---|---|---|
| ① | 读侧给什么 | ✅ **已完成** → §9 |
| ② | 分享链接能否被官方 App 接受 | 🟡 **部分验证**：链接 HTTP `200` 可达、`accept` 参数契约成立；**端到端进房仍需第二个账号** |
| ③ | `invitation/send` 是否存在 | ✅ **已完成：确认不存在**（正反对照，三个候选路径全 `404`） |
| ④ | 枚举复核 | ✅ 已完成：**原样回读、大小写不敏感**，建议用大写 |

②③ 都涉及**对其他真实用户的外部动作**，不在未授权时执行。

### 探针方式（已跑通，可复用）

与宿主同名同包的类直调 dll，无需 Kotlin / Gradle：

```java
// cp/player/core/provider/JniProvider.java  —— 包名类名必须与宿主一致
public class JniProvider { public static native String nativeCallApi(String m, String p); }
```

```bash
# 现成探针（本机临时目录，非仓库）
cd /e/tmp/lt-probe
java -cp . Probe listentogether/status
java -cp . Probe listentogether/sync/playlist/get '"roomId":"<rid>"'
```

⚠️ Java 编译器把**注释里的 `\u` + 4 位十六进制**也当 Unicode 转义 ⇒ 会报「非法的 Unicode 逃逸」。
⚠️ Git Bash 的 `/tmp` 与工具解析的 `/tmp` **不是同一个目录**（工具落到 `E:/tmp`），别混用。

---

## 3. 架构方案

### 3.1 分层（新增 `core/src/commonMain/kotlin/cp/player/core/listentogether/`）

```
listentogether/
├── ListenTogetherModels.kt           # Room / Member / RoomPlaybackCommand / RoomPlaylist（纯数据）
├── ListenTogetherService.kt          # 领域接口：StateFlow<RoomState> + 动作
├── ListenTogetherBackend.kt          # 面向音源的抽象：各音源各自实现
├── ListenTogetherEngine.kt           # 同步引擎：轮询 + 心跳 + 回声抑制 + 进度外推（平台无关）
├── NeteaseListenTogetherBackend.kt   # 网易云实现：走 MusicApiService.callApi
└── ListenTogetherInvite.kt           # 分享链接拼装 / 解析（纯函数，可单测）
```

**为什么单开一个包，不和 `integration/` 合并**：`IntegrationBoundaryTest` 钉死
`IntegrationService` 禁止依赖 `MusicApiService / provider.* / cache.*`（身份是「把 CPPlayer
当音源对外提供」）；一起听必须**消费**音源能力，方向相反。

### 3.2 同步引擎：只需一条路线

**轮询 `sync/playlist/get` → 读回 `playCommand` + `playlist` → 对齐本地播放。**

不需要自带信令（v2 的路线 B 作废）。理由：实测服务端持久化并回吐指令，
对端只要轮询就一定能读到。**只有一种情况才需要考虑自带信令**：要求端到端延迟远低于
轮询间隔（如 < 500ms 的「遥控器」体验）—— 那属于后续可选优化，不是必需品。

> 曾经设想的「复用 `LocalServer` + `ExternalPusher` 做信令面」**降级为可选**。
> 注意：`docs/history/SYNC_HANDOFF_PLAN.md`（局域同步 / 无缝转移）仍需要那条通道，
> 两件事不再强绑定，但**如需共用，仍应共用同一套 `/api/v1/sync/*` 与令牌闸门**。

### 3.3 引擎必须做对的四件事

**(a) 轮询节奏**（实测校准）

| 行为 | 间隔 | 说明 |
|---|---|---|
| `heatbeat` | **30s** | 服务端 `timeSpan: 30` 直接给的；比 HyPlayer 的 5s 更省、更不易被风控 |
| `sync/playlist/get` | 2–5s | **这是拿远端指令的唯一通道**，间隔直接决定同步延迟 |
| `status/get` | 10–15s | 只在需要房间/成员变化时拉 |
| `room/check` | 几乎不用 | 只有「判断能否加入」时用一次 |

**(b) 指令新旧判据 —— 用 `serverSeq`，不要用 `clientSeq`**

实测响应里带两个序号：

- `serverSeq`：**服务端**分配（实测是毫秒时间戳，单调递增）⇒ **「有没有新指令」就看它是否变大**；
- `clientSeq`：**客户端**自己给，原样回读 ⇒ 只用于识别「这是我发的」，做**回声抑制**。

判据：`if (remote.serverSeq > lastSeenServerSeq) apply()`；再叠加
`if (remote.userId == myUid && remote.clientSeq <= lastSentClientSeq) ignore()`。
**单靠 clientSeq 不行** —— 别人指令的序号会与本机交错。

**(c) 进度外推**

`progress` 是**数字毫秒**（实测回读 `45000`）。必须按「锚点 + 墙钟差」外推：

```
期望位置 = anchorProgress + (now - anchorWallTime)
偏差 > 2s → seek；否则不动
```

不做外推 → 每轮都 seek（持续打嗝）；不做阈值 → 本来同步也被反复 seek。

**(d) 跟随模式复位**

进房后本机「下一首」= 广播切歌。必须有显式**跟随模式**开关，且**退房 / 断线**走
**同一个清理函数**，并配断言测试。否则会出现「退出了一起听，点下一首还在给别人发指令」。

### 3.4 与 `PlaybackController` 的接线

**不改 `PlaybackController` 接口**（它是前端唯一入口）。引擎持有它的观察
（`state: StateFlow<PlaybackUiState>`）与调用（`playQueue` / `seekTo` / `pause` /
`resume` / `skipNext` / `skipPrevious`），内部维护「是否上报」的判定。

所有「应用远端指令」的调用**必须回到 `MusicBackend.backendScope`**（Main / EDT），
与 `IntegrationService` 写路径同一套线程纪律 —— 否则偶发跳歌且难复现。

### 3.5 UI 接入点

| 位置 | 做法 | 硬约定 |
|---|---|---|
| 播放页 more 菜单 | 「一起听」入口（`PlayerMoreBottomSheet`） | — |
| 房间页 | 新建 `ListenTogetherScreen`，走 **`CpRouteScaffold`** + **`CpBackButton`** | 漏了 `CpRouteScaffold` ⇒ 窄屏整页无顶栏无返回键 |
| 邀请区 | 房间号 + 二维码 +「复制链接」+「私信给好友」 | 用已有 `QrCodeGen.kt` |
| 退出 / 结束房间 | 走 **`CpConfirmState` + `CpConfirmHost`** | 不可逆操作必须二次确认 |
| 宽度 / 边距 | 只取 **`CpSpacing`**（表单页 `formMaxWidth`） | `widthIn` 必须写在 `fillMaxWidth()` **之前** |
| 成员列表（可选） | 桌面宽屏可 `CpTwoPane`；**是否右栏由 `LocalEmbeddedInPane` 声明** | 别拿 `LocalIsExpanded` 猜 |

设置项（`SettingsRegistry`）新增：是否允许他人控制我的播放、心跳/轮询间隔（高级）。

---

## 4. 分期计划

| 阶段 | 内容 | 出口判据 |
|---|---|---|
| ~~P0 探针~~ | ✅ **已完成** | §9 |
| **P1 生命周期 + 邀请闭环** | create / check / status / end + 30s 心跳；建房 → 拼分享链接 → 二维码/复制 → 对方 `accept` | 两台设备进同一房间、成员可见、能退房 |
| **P2 指令同步** | 轮询 `sync/playlist/get` 读 `playCommand` → 应用；本机操作 → `play/command` 上报；`serverSeq` + `clientSeq` 抑制；进度外推 | 两端切歌/暂停/seek 收敛，**无抖动** |
| **P3 队列同步** | `sync/list/command` + 读回 `playlist.displayList` | 一端改队列，另一端一致且不错误重排 |
| ~~P4 邀请增强~~ | ❌ **已实测排除**：`invitation/send` 确认不存在（§9.5），无需再试 | 关闭 |
| **P5 能力抽象** | `ListenTogetherBackend` + manifest 能力声明；其他音源 `Unsupported` | 切到 migu 时入口置灰而非报错 |
| **P6 打磨** | 断线重连、Android 保活、UI 精修 | 锁屏 30 分钟回来仍同步 |

---

## 5. 多音源兼容性

### 5.1 能力声明机制（已存在，不要另造）

`BackendProvider.apiMap`：`= 端点名` → 转发；`= "unsupported"` → 标记不支持；`null` → 用原方法名。
`ProviderManager.callApi` / `MusicApiServiceImpl.callAllProviders` 已实现判定，
返回 `{"code": -1, "msg": "该提供商不支持此功能"}`。**一起听复用这条。**

### 5.2 三种音源类型的表现（用已装模块验证）

本机实际装了 `migu`（咪咕音乐 2.27.7）：

```json
{ "id": "migu", "type": "http", "entryPoint": "http://127.0.0.1:6200/cpplayer", "apiMap": {} }
```

`HttpProvider.callApi` 是 `POST {baseUrl}/{method}` ⇒ 会打
`http://127.0.0.1:6200/cpplayer/listentogether/room/create`，服务端没实现就 404。

| 音源类型 | 表现 | 处理 |
|---|---|---|
| 内置网易云（JNI） | ✅ 已实跑通 | 直接接 |
| `http` 第三方（migu） | 端点不存在 → 失败 | `apiMap` 标 `"unsupported"`，入口置灰 |
| `binary` / `websocket` | 取决于模块实现 | 由 manifest 声明 |

### 5.3 跨音源互通：做不到

**媒体 ID 是 provider 作用域的**（`CPMediaId` = `{providerId}://{resourceType}/{resourceId}`）。
A 用网易云建房、B 在咪咕 → B 无法解析房间里的曲目。

**决策**：房间携带 `providerId`；加入方不一致 → **拒绝加入并提示切音源**，不静默降级。
抽象层的价值是**多音源各自协议共用一套 UI / 状态机**，**不是**互通层。

### 5.4 音频面：不要中继

`/stream` 是**音频中继**（服务端转发字节）；一起听的音频面是「各端自己取流」。
强行用 `/stream` 给房间内其他端推流会撞上带宽、版权、「音质由房主账号决定」等问题。
另注：实测 `roomInfo` 里有 `agoraChannelId`（声网 RTC）与 `chatRoomId`（云信聊天室），
说明**官方 App 的语音/信令走第三方 RTC + IM**，本方案不接这两者。

---

## 6. 风险与坑位

| 风险 | 说明 | 对策 |
|---|---|---|
| 延迟下限 = 轮询间隔 | 无推送通道 | UI 如实体现，别标「实时」 |
| 邀请需多一步 | 无官方一键推送 | 二维码 + 复制链接 + 私信 |
| 回声抖动 | 自报指令绕回被应用 | `serverSeq` 判新 + `clientSeq`/`userId` 判己 + 单测 |
| 进度打嗝 | 每轮 seek | 外推 + 2s 阈值 |
| 跟随模式未复位 | 退房后仍在广播 | 统一清理函数 + 断言测试 |
| **建房会顶掉当前房间** | 一个账号同时只能在一个房间，`end` 只能关不能复活 | 建房前先 `status` 查一次；已在房间则不建 |
| **`status` 恒 NOT_CONNECTED** | IM 连接态，HTTP 客户端进不去 | 不依赖它；但要知道官方 App 可能把你显示为未连接 |
| 房间 30 分钟有效期 | `effectiveDurationMs: 1800000` | 到期前提示续期/重建；**别等用户发现突然掉线** |
| 响应带新 cookie（NMTID） | 每次响应都回一个新 `NMTID` | 确认 `MusicApiServiceImpl` 是否回写；不回写也不会立刻出错，但别装作没这回事 |
| Android 后台被杀 | 轮询协程被 Doze 掐 | 播放中靠已有 `PlaybackMediaSessionService`；不播放时不常驻 |
| `kotlinx.datetime` 运行时坑 | 时间戳换算（`serverSeq`/`roomCreateTime` 都是毫秒） | 一律走 `cp.player.core.util.localDateTimeOf(ms)` |
| 风控 | 建房过频 / 轮询过密 | 固定间隔 + 退避 |

---

## 7. 明确不做

- 不做跨音源互通（§5.3）；
- 不做音频中继 / 转码（§5.4）；
- 不接 Agora RTC / 云信 IM（语音与实时推送不在范围内）；
- 不引入 WebSocket 依赖；
- 不改 `PlaybackController` 接口语义；
- 不把一起听塞进 `IntegrationService`；
- 不在 `IntegrationRoutes` 加一起听端点（对外契约方向相反）；
- 不依赖 `invitation/send`（**已实测证实不存在**，§9.5）—— 邀请只走「链接 / 房号 → 对方 `accept`」。

---

## 8. 交付前验证（按 AGENTS.md 硬约定）

1. **编译**：`:app:compileKotlinDesktop` + `:core:compileKotlinDesktop`；
   ⚠️ `commonMain` 整源集一起编译 —— 报一堆错时看**第一个**报错的文件。
2. **测试**：结论只认 `**/test-results/**/TEST-*.xml` 且 **`skipped="0"`**；别 `| head` 截 Gradle 输出。
3. **版式**：房间页必须**离屏渲染出图**核对。
4. **协议**：P2 起补「回声抑制」单测（给定自报指令序列，断言不产生反向 seek）。
5. **提交**：`git commit --only -F msg.txt -- <显式路径>`，禁 `add -A`。

---

## 9. 实测记录（P0，2026-10-04）

探针：与宿主同名同包的 Java 类直调 `ncm_api_rs.dll`，cookie 从
`~/.cpplayer/cp_player_prefs.properties` 的 `cookie_cp_api` 读（2002 字符，含 `MUSIC_U`）。

### 9.1 登录态与「未在房间」

```json
// user/account  → code 200（uid 9084388061，已登录）
// listentogether/status →
{"code":200,"data":{"anotherDeviceInfo":null,"anotherFollowStatus":false,
 "inRoom":false,"roomInfo":null,"status":null},"message":""}
```

### 9.2 建房 —— `roomInfo` 全貌（**首次拿到的权威结构**）

```json
{"code":200,"data":{"type":"NEW_ROOM","toastText":null,"inviteUserInfo":null,
"avatarPendantShow":true,"hintText":null,"roomInfo":{
  "roomId":"af4e21669699a2a538fdfb8b205f9490_1791075851",
  "creatorId":9084388061,"roomType":"FRIEND","ltType":1,
  "chatRoomId":"7776978779","agoraChannelId":"839427247420551168",
  "roomCreateTime":1791075851589,"effectiveDurationMs":1800000,"waitMs":120000,
  "openHeartRcmd":false,"roomVipAbGroup":{"huiyuan_ListenTogether_TSpop":"c"},
  "roomUsers":[{"userId":9084388061,"nickname":"…","avatarUrl":"…","identityIcon":null,
                "identityName":null,"identityTag":null,"outerId":null,"pendantData":null}],
  "alg":null,"listeningRefer":null,"matchPlayInfo":null,"matchPlayType":null,
  "matchedReason":null,"newUnmaskFlag":null,"roomRTCType":null,"unlockedIdentity":null,
  "unlockChatNeededMs":null,"unlockIdentityNeededMs":null,"unlockTextChatNeededMs":null}}}
```

要点：`roomId` = `{32位hex}_{创建时间戳}`；`effectiveDurationMs` = **30 分钟**；
`waitMs` = 120s 邀请等待；`chatRoomId`（云信）+ `agoraChannelId`（声网）⇒ 实时通道是 IM + RTC。

### 9.3 指令与队列可完整回读（**核心结论**）

```json
// 1) 上报队列
sync/list/command {roomId, commandType:REPLACE, userId, version:1,
                   randomList:"347230,186016", displayList:"347230,186016"}
→ {"code":200,"data":{"result":true}}

// 2) 回读 —— 队列 + 服务端自动推导的 playCommand 都在
sync/playlist/get →
{"data":{"playCommand":{"commandType":"PLAY","playStatus":"PLAY","progress":0,
   "targetSongId":"347230","formerSongId":"347230","clientSeq":0,"serverSeq":0,
   "userId":9084388061,"anotherUid":0},
 "playlist":{"displayList":{"changed":true,"result":["347230","186016"],"rcmdSongIds":[]},
   "replace":true,"listMode":"","playMode":null,"randomList":null,
   "version":[{"userId":9084388061,"version":1}]}}}

// 3) 上报 Play/progress=12000 → 回读：progress 变 12000，serverSeq 变 1791075871536
// 4) 上报 PAUSE/progress=45000/targetSongId=186016/clientSeq=2 → 回读：
{"playCommand":{"commandType":"PAUSE","playStatus":"PAUSE","progress":45000,
  "targetSongId":"186016","formerSongId":"347230","clientSeq":2,
  "serverSeq":1791075880363,"userId":9084388061}}
```

**⇒ 上报什么就存什么、且能原样读回。这是双向同步成立的全部依据。**

### 9.4 心跳与连接态

```json
// heatbeat → {"code":200,"data":{"result":true,"time":1791075891136,"timeSpan":30}}
// status   → …"inRoom":true, roomInfo{…}…, "status":"NOT_CONNECTED"
// end      → {"code":200,"data":{"success":true,"roomType":"FRIEND","shareInfo":null,…}}
// end 后 status → inRoom:false, roomInfo:null
```

- `timeSpan: 30` ⇒ **服务端期望 30 秒心跳**（权威值）。
- `status` 发完心跳仍是 `NOT_CONNECTED` ⇒ 它表示 **IM 长连接态**，HTTP 客户端**永远**满足不了；
  但**不影响读写房间状态**。
- `room/check` 全文仅 `{"copywriting":null,"joinable":true,"status":"AVAILABLE","type":"NORMAL"}`。
- `end` 的响应里有 `shareInfo` 字段（本次为 null）—— **可能是官方邀请链接的载体，值得后续验证**。

### 9.5 第二轮实测：邀请与消息通道（2026-10-04 09:25，已授权）

**新增两个透传口（测未封装端点必须知道）**

| 口 | 用法 | 实测结论 |
|---|---|---|
| `batch` | 参数名以 `/api/` 开头即透传 | ⚠️ **只能调「无参数路径」**。同一条 `status/get`：空值 → `200`；**任何非空值 → `400`**。<br>根因：模块把参数塞成 JSON **字符串**，而上游 `/api/batch` 期望**对象**（模块侧 bug） |
| `api` | `uri` + `data`（JSON 体）+ `crypto` | ✅ **真正的任意路径透传口**，可带参数与加密方式 |

> ⚠️ 用 `batch` 测未封装端点只会拿到 **batch 自己的 `400`**，不是目标端点的响应 ——
> 极易误判成「端点不存在」。第一轮那三条 `400` 其实是**无结论**，不是否定。

**`invitation/send` 确认不存在（正反对照）**

| 路径 | 结果 |
|---|---|
| `/api/listen/together/status/get` | `200` ✅ 已知存在 |
| `/api/listen/together/play/invitation/accept` | `200` ✅ 已知存在 |
| `/api/listen/together/room/check` | `200` ✅ 已知存在 |
| `/api/listen/together/play/invitation/nonexistent_zz` | `404` ← 对照组 |
| `/api/listen/together/bogus/endpoint` | `404` ← 对照组 |
| **`.../play/invitation/send`** | **`404`** |
| **`.../play/invitation/send/v2`** | **`404`** |
| **`.../play/invitation/create`** | **`404`** |

⇒ 上游 `404` = 路径不存在。**邀请发送端点确实不存在**（ShinawaseLoader 那处是盲试，从未成功）。

**`accept` 的两个行为怪癖（直接影响加入流程实现）**

1. **已在房间时无条件返回 `200`** —— 连乱造的 `roomId`（`deadbeef_1`）和错误的 `inviterId`（`1`）
   都返回 `200` + `hintText:"当前正在一起听"`；它不校验参数，直接回你当前房间。
   ⇒ **加入流程必须先查 `status`**：已在房间时提示「需先退出」，别指望 `accept` 报错。
2. **不在房间时一律 `488`** —— 乱造的 roomId 与「格式正确但房间已销毁」的 roomId 返回**完全相同**的
   `{"code":500,"msg":"API error (code=488): Unknown error"}`。
   ⇒ **无法区分「房间不存在」与「邀请不是给你的」**；UI 只能说「邀请无效或已过期」，
   **不要编造更具体的原因**。

**分享链接可达性**：`https://st.music.163.com/listen-together/share/?roomId=…&inviterId=…`
→ **HTTP 200**（无跳转）。格式正确、页面存在。

**消息 `type` 试探**：`/api/msg/private/send` 用 `type=text` 返回 `200`（透传可用）；
换 `listentogether` / `together_invite` / `invite` 全部 `{"code":500,"msg":"Invalid parameter"}`
（**不是 404** ⇒ 端点存在、只是取值不被接受）。结构化 type 无法靠猜穷举。

**仍未验证（单账号限制）**

- `send_text` 发给自己返回 `200`，但**自己跟自己不产生会话**（`msg/private/history` 为空）
  ⇒ **投递效果无法用单账号验证**，需第二个账号真实收一次。
- 因此「对方点链接能否真的进房」**尚未端到端验证**；已验证的只是参数契约
  （`accept` 路径 `200`，且链接携带的正是它需要的两个参数）。
