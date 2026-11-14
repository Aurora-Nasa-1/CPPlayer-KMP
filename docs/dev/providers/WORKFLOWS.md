# 分域实现

> 按业务域组合方法：搜索、播放、评论、一起听、MV/DJ、写入、上传、登录。
> 契约版本 `kmp-sdk-v1` · 更新 2026-11-14

逐项签名与能力名见 [SDK_OPERATIONS](SDK_OPERATIONS.md)，字段与默认值见 [SDK_TYPES](SDK_TYPES.md)。

## 1. 实现与声明

| 语言 | 声明方式 |
|---|---|
| Kotlin | 实现 `MusicProvider`；扩展方法继承 `ExtendedOperations`，未实现抛 `ProviderException(Unsupported)` |
| JavaScript | `globalThis.provider` 上提供 `capabilities` 数组与对应异步方法，每个方法收 `(request, context)` |
| TypeScript | 从 `provider-sdk-kmp/js/index.mjs` 导入 `defineProvider` 与导出类型 |

| 规则 | 内容 |
|---|---|
| 能力常量 | 原有在 `Capability`，扩展在 `ExtendedCapability` |
| 只声明不实现 | 视为未实现 |
| 部分实现 | 允许；`tracks.getMany` 可由 `tracks.get` 默认逐项实现 |
| 隐式借用 | 其余能力不得借用另一端点或另一账号 |
| 无参操作 | Kotlin 用 `Bytes(byteArrayOf())`；JS 收到 `{ value: Int8Array(0) }` 或等价空 typed array |
| 完成度 | SDK 声明、运行期实现、统一层入口、页面入口是四个不同条件 |

## 2. 搜索、目录与分页

| 资源 | 搜索 | 详情 / 内容 |
|---|---|---|
| 歌曲 | `search` | `get`、`getMany`、`resolve` |
| 歌单 | `searchPlaylists` | `getPlaylist`、`getPlaylistTracks` |
| 专辑 | `searchAlbums` | `getAlbum` |
| 歌手 | `searchArtists` | `getArtist`、`getArtistTracks`、`getArtistAlbums` |
| MV | `searchMvs` | `getMv`、`resolveMv` |
| 视频 | `searchVideos` | `getVideo`、`resolveVideo` |
| 用户 | `searchUsers` | `getUserProfile` |
| DJ 电台 | `searchDjRadios` | `getDjRadio`、`getDjPrograms` |

| 规则 | 内容 |
|---|---|
| ID | 当前 Provider 的裸字符串；`001`、`A`、`a`、`A,a` 不转换、不拆分 |
| `accountId` | 宿主账号槽位，不是上游用户 ID |
| 命名空间入口 | 应用侧核对 Provider；直接传 request DTO 的扩展方法已绑定当前会话 |
| `limit` | 结果条数上限 |
| `offset` | 从零开始的条目偏移；上游用页码时由 Provider 转换，例：`offset / limit + 1` |
| 单页上限 | 500 项 |
| 消息上限 | 编解码后 4 MiB；更小的上游上限仍然生效 |
| `total` / `hasMore` | 未知返回 null；不用当前页长度或 `items.isNotEmpty()` 伪造 |
| 游标 | 不解析、不 URL 解码、不转数字；首次通常 null，后续原样回传响应 token（含空字符串） |
| 归一化陷阱 | 不要写 `cursor || undefined` |

未知 `hasMore` 与已知 `false` 不同。是否继续翻页由具体业务的上游语义决定。

收藏 ID 超过 500 项时实现 `getLikeListPage(PagedIdRequest): IdPage` 并声明 `accounts.likes.page`（tag 166）：

| 项 | 约定 |
|---|---|
| `id = "self"` | 当前登录账号 |
| 每页上限 | 500 项 |
| `hasMore` | 必须明确给出，不能用空页表示「还有下一页」 |
| 宿主行为 | 按实际返回条数递增 offset；取完全部页才发布完整集合；最多合并 1,000,000 项 |
| 旧能力 | `accounts.likes` / `getLikeList(UserRequest)` 保持有界完整集合语义，不能静默截断为前 500 项 |

目录相关 DTO 不是任意上游参数字典。

| 方法 | 语义 |
|---|---|
| `getNewAlbums` | 既有的有限发现选择 |
| `browseNewAlbums` | 支持 area / offset |
| `getLatestAlbums` | 最新发现选择 |
| `getAlbumChart` | `new` / `hot` 与可选年月 |
| `getAlbum` | 返回完整专辑内容，仍受 500 项与字节上限约束，不可静默截断 |
| `getUserFollows` / `getUserFolloweds` | 既有返回 `ArtistPage`，不是 `SocialUserPage`；不要在现有 tag 下换 DTO |

推荐、FM、相似内容、历史推荐、数字专辑、歌手浏览各自声明能力。年月、区域、首字母等支持值写进音源说明；不支持时明确返回 Unsupported。

## 3. 播放、音质与歌词

`resolve`、`resolveMv`、`resolveVideo` 返回 `MediaResource`。

| 字段 | 约定 |
|---|---|
| `actualQuality` | 按实际资源填写 |
| 媒体头 | 明确列出 |
| 格式 / 码率 / 大小 / 到期时间 | 未知留 null |
| 时长 | 毫秒 |
| 绝对时间 | Unix epoch 毫秒 |
| `ScrobbleRequest.playedSeconds` | 明确的秒数例外 |

| 下载类型 | 调用 |
|---|---|
| 音频 | `resolve` |
| MV | `resolveMv` |
| 视频 | `resolveVideo` |

宿主按实际质量与已知容器格式确定下载记录与后缀；过期直链重新解析；只转发 Provider 返回的媒体头，不从账号会话注入 Cookie。播放缓存跨音质回退须匹配账号、凭据版本与设置摘要；无归属的旧索引不参与账号回退。保存或清除当前账号凭据会立即取消旧会话请求，Provider 必须响应协程取消。

`media.qualities` 对应 `getMediaQualities`，支持 `song`、`audio`、`mv`、`video`。

| 规则 | 内容 |
|---|---|
| `QualityOption.id` | 唯一业务音质 key |
| `available = false` | 可表示当前账号不可用，应用会过滤 |
| 禁止 | 返回歌单或专辑的「音质」；用 manifest 静态映射冒充实时可用性 |
| `qualityOptions` | 负责应用档位与业务 key 映射 |
| `actualQuality` | 负责实际返回档位 |

`getLyrics` 至少返回 LRC、TTML、YRC 中一种主格式；没有匹配返回 NotFound。翻译与罗马音不能单独冒充主歌词。独立 Lyrico 插件与本地边车不属于此 SDK 包协议。

## 4. 评论与楼层

旧 `getComments(CommentPageRequest)` 只接收 offset；新 `getCommentPage(CommentQuery)` 才能接收输入 cursor。需要游标分页时声明 `comments.page`。

| 情形 | 统一层行为 |
|---|---|
| 请求 cursor 为 null 且新能力不存在 | 使用旧能力 |
| cursor 非 null | 不为降级丢弃 token |

`sortType` 的 SDK 语义：1 推荐、2 热度、3 最新。上游若用 99 等值，由 Provider 映射回 SDK 值。响应必须带真实有效排序：例如推荐被降为热度时返回 2，翻页回传它。

资源类型为 `music`、`song`、`mv`、`playlist`、`album`、`dj`、`video`、`event`；应用的命名空间 helper 把歌曲的 `music` 映射到 `song` ID 类型。

```typescript
import { defineProvider } from "../../provider-sdk-kmp/js/index.mjs";

const provider = defineProvider({
  capabilities: ["comments.page"],
  async getCommentPage(request, context) {
    // service is the provider's own upstream adapter.
    const page = await service.comments(request, context);
    return {
      comments: page.comments,
      totalCount: page.totalCount,
      hasMore: page.hasMore,
      cursor: page.nextCursor,
      sortType: page.effectiveSort
    };
  }
});
```

上例说明数据映射，`service` 由音源实现；可编译类型示例见 [`contract.test.mts`](../../../provider-sdk-kmp/js/contract.test.mts)。不要把上游评论 JSON 整体塞进某个字符串字段。

| 项 | 约定 |
|---|---|
| 楼层首页 | `getFloorComments` 用 `time = 0`，下一页原样回传 `nextTime` |
| `topReplies` | 只是内联预览，不代表已获取完整楼层页 |
| 发表成功无返回体 | `CreatedComment.comment` 可为 null，由应用刷新列表 |
| 评论 / 回复 ID | 保留 v1 数字 ID，范围受 JS safe integer 限制；不要声称已全部不透明 |
| 超界身份 | 新增兼容操作，不改旧字段顺序、不静默转 0 |

## 5. 私信、通知与动态

| 方法 | 返回 |
|---|---|
| `getPrivateMessages` | 会话摘要 |
| `getMessageHistory` | 新到旧的消息 |
| `getRecentContacts` | 联系人 |

| 项 | 约定 |
|---|---|
| `sendMessage` | 只发纯文本 |
| `sendResourceMessage` | 发 `song`、`album`、`playlist` 资源及附言；与 `sendMessage` 不是同一种正文编码 |
| 多收件人 | 1..100 个裸用户 ID |
| 已读 | 单独使用 `markMessageAsRead` |

| 通知类型 | 方法 | 分页 |
|---|---|---|
| 评论通知 | `getCommentNotifications` | 游标 |
| 提及 | `getMentionNotifications` | 条目 offset |
| 系统通知 | `getSystemNotifications` | 游标 |

`Notification` 只含文本、时间与已知的发送人 / 资源引用，不回传旧 API 封套。`getUnreadCount` 的各类计数独立，不能合并到私信数。

`getCalendar` 的可选范围用 epoch 毫秒；`getEvents` / `getUserEvents` 用不透明 cursor。动态删除、转发、资源分享各有 typed 方法。`SocialEvent.text` 是正文，不是 JSON。图片用明确 URL 列表表达。

## 6. 一起听

调用顺序通常是：状态检查 → 建房或验邀请 / 接受邀请 → 心跳、快照、命令、队列同步。房间与用户 ID 为字符串。`inRoom = true` 必须有 room。Provider 不创建第二个宿主后端，也不自行启动服务端。

| 输入 / 输出 | 约定 |
|---|---|
| `ListenHeartbeatRequest` | `positionMs` 为毫秒，`playStatus` 为 PLAY / PAUSE |
| `ListenHeartbeat` | 正数 `intervalMs`，不转成上游旧 `timeSpan` 秒数 |
| `ListenCommand` | PLAY / PAUSE / PROGRESS / GOTO；GOTO 必须带 trackId |
| `ListenQueueRequest.action` | REPLACE / ADD / DELETE，按上游语义执行 |
| `ListenQueueRequest.trackIds` | 保序保留重复项；空 REPLACE 明确清空 |
| `ListenSnapshot.playlist` | null 是没有队列快照；空列表是有快照但队列为空 |
| `sequence` / `serverSequence` / `version` | 非负 safe integer，不用时间戳猜值 |

应用按版本与序列号协调状态。Provider 不把缺失值伪造成最新命令，也不吞掉失效会话。页面通过 `SdkListenTogetherBackend` 接入；切换音源或账号会清除旧房间与待发送指令。`MutationResult.success = false` 必须视为失败。

当前应用房间模型仍用 Long 用户 ID：只有能精确往返的正整数 ID 可进入该 UI；带前导零、超范围或其他字符串明确返回 Unsupported。SDK 本身保留原始字符串。

## 7. MV、视频与 DJ

MV 与视频使用独立 ID 类型与能力；相同字符串不说明是同一种资源。`resolution` 是垂直像素数，宿主接受 1..16384，返回资源仍须描述真实质量。

| 项 | 约定 |
|---|---|
| `getVideoGroups` / `getVideoCategories` | 不同操作 |
| `getVideosByGroup.id` | 分组 ID |
| 时间线 | 普通时间线与推荐时间线分别实现 |
| `DjRadio` / `DjProgram` | 不可互换；节目包含可播放的 `Track` |
| `getDjPrograms.id` | 电台 ID |
| `getDjProgram.id` | 节目 ID |
| `browseDjRanking(kind)` | 新 / 热榜选择 |
| `getDjRanking` | 保留原有默认榜单语义 |
| `getRecentDjRadios` | 返回电台，不是节目 |
| 分类 / 分类下电台 / 订阅者 / 用户电台 | 各有 typed 方法 |

## 8. 歌单写入与导入

| 规则 | 内容 |
|---|---|
| 收藏与点赞 | 接收目标布尔状态，不能实现为「切换当前状态」，否则重试会切回去 |
| `updatePlaylist` | 局部更新：null 不改，`tags = []` 清空标签，至少提供一个更新字段 |
| `reorderPlaylists` / `reorderPlaylistTracks` | 1..500 个唯一 ID，严格按输入顺序执行，不排序、不去重 |
| 与一起听的区别 | 队列允许重复项 |
| 封面上传 | 接收字节，返回 `ImageUploadResult` |
| `updatePlaylistCover` | 用已有图片 ID 应用到歌单 |
| 上传语义 | 上游是否在上传时同时应用图片需在音源说明写明；不要把静态 URL 当已上传 imageId |

`createPlaylistImport` 恰好选一种源：非空 `tracks`、非空白 `text` 或非空 `links`。导入名非空白、最多 256 字符。

| 项 | 约定 |
|---|---|
| 返回 | taskId |
| 轮询 | `getPlaylistImportStatus(IdRequest(taskId))`，响应必须是同一 taskId |
| 状态 | pending / running / completed / failed |
| 未知计数 | null |
| 失败说明 | 不得含原始凭证 |

## 9. 图片与云盘上传

图片规则：

| 项 | 值 |
|---|---|
| MIME | `image/jpeg`、`image/png`、`image/webp` |
| 正文 | 1..1048576 字节 |
| `filename` | basename，不是宿主路径 |
| 转码 | 支持 MIME 不等于宿主会替 Provider 转码 |
| 字节类型 | Kotlin `ByteArray`，JS `Int8Array` / `Uint8Array` |
| 有符号字节转 HTTP payload | 保留低 8 位，不能把负数夹成 0 |

云盘上传三阶段：

| 阶段 | 输入 | 输出 / 校验 |
|---|---|---|
| `prepareCloudUpload` | 文件名、正数总字节数、32 位十六进制文件 MD5、正数 bps | 本会话 uploadId、needsUpload、下一个 offset、1..1048576 的 chunkSize |
| `uploadCloudChunk` | offset、content、last | 校验句柄归属、顺序、大小、最后一块边界；整块成功或整块失败；响应 offset = 输入 offset + 正文长度；complete 必须等于 last |
| `completeCloudUpload` | 句柄，可选标题 / 歌手 / 专辑 | 发布文件；去重路径也由 Provider 校验何时可发布，不伪造完成确认 |

| 规则 | 内容 |
|---|---|
| 宿主校验 | 只做边界与确认，无法替 Provider 验证服务端上传状态 |
| 凭证归属 | 凭证、预签名 URL、上游 token 留在 Provider 内，业务只返回不透明句柄 |
| origin | 实际上传 origin 必须在 `sdkOrigins`；动态域名不绕过白名单 |
| 失败处理 | 不自动换句柄继续发块；未经上游确认不重复发布；重试与断点策略由音源定义 |
| 账号切换 | 句柄作废，pending 调用取消 |
| 二进制快照 | 宿主异步调用前快照请求；Provider 不得在并发调用间共享或重写全局缓冲 |
| 临时存储 | JS runtime 总量仅 1 MiB，不能把整首歌存进去 |

## 10. 登录与账号

`authenticate` 用固定动作名，具体上游流程由 Provider 实现。只填当前动作需要的字段，不把空字段猜成账号默认值。以下是工作流约定，不是额外开放的 raw endpoint 通道。

| 动作 | 输入与结果 |
|---|---|
| `qr.key` | 获取 key |
| `qr.create` | 用 key 创建二维码 URL |
| `qr.check` | 用 key 轮询状态，成功时可返回 credential cookie |
| `login.email` | email / password 及所需 challenge；`md5` 表示密码是否已预哈希 |
| `login.phone` | phone 与 password 或 captcha，按音源支持流程；可选 `countryCode` 为不带 `+` 的 1–4 位数字 |
| `captcha.send` | phone，用于申请验证码；可选 `countryCode` |
| `captcha.image` | 获取当前流程的图片验证码，结果可带 imageBase64 |
| `webview.url` | 获取登录页 URL 与流程状态 |
| `webview.check` | 以 sessionId 等流程标识检查登录结果 |
| `verify.url` | 获取验证 URL 与 verifyId 等状态 |
| `verify.check` | 以验证标识检查挑战结果 |
| `login.anonymous` | 建立匿名会话所需的上游身份 |
| `logout` | 执行上游退出 |

| 规则 | 内容 |
|---|---|
| 状态表达 | `AuthActionResult.success/code/status/challenge` 表达真实状态；「尚在扫码」不是最终失败，异常不是登录成功 |
| 错误类别 | `Authentication` |
| 日志 | 原始凭证不入日志 |
| 会话 | 宿主保存新凭证并替换会话；Provider 不跨账号续用旧 context |
| `countryCode` 省略 | 沿用 Provider 原有默认区号（现有网易云 Native flow 为 `86`） |
| Native v1 区号 | 通过既有 auth-only `extraJson` 传递，不改变位置 wire 布局 |
| JS `close` | 引擎由宿主关闭，不存在可依赖的 `provider.close` 回调；可信 Kotlin 实现应在 `close()` 释放资源 |

账号绑定：`getUserBindings` 只返回 maskedAccount。`bindPhone` 的 countryCode 不带 `+`，新手机号验证码是 captcha；`replacePhone` 还需 oldCaptcha。绑定密码为可选明文输入，哈希由 Provider 负责，与 `AuthActionRequest.md5` 布尔字段、云盘文件 MD5 都不同。

签到、资料更新、关注、收藏、云盘导入 / 匹配、最近播放均为独立能力；支持登录不等于支持所有账号写操作。

## 11. Runtime、错误与交付

| 边界 | 宿主约束 |
|---|---|
| HTTP 方法 | GET / POST / PUT / PATCH / DELETE / HEAD |
| origin | 精确白名单 |
| 明文 HTTP | 只允许 localhost / 127.0.0.1 / ::1，其他须 HTTPS |
| 重定向与凭证 | 不自动跟随重定向，不自动补 Cookie / Authorization |
| 请求 / 响应正文 | 各至多 4 MiB |
| HTTP 超时 | 总 10 秒，连接 5 秒 |
| 临时存储 | 会话隔离，总量 1 MiB；key 非空白、≤256 字符；null 删除 |
| Provider 调用 | 默认 15 秒截止，最多 4 并发 |
| QuickJS | 单会话线程；32 MiB 内存、512 KiB 栈、250 ms 执行预算 |

上游可用 JSON、表单或其他编码，通过 runtime HTTP 正常解析；业务边界仍交付 DTO。`context.sha256` 不是通用密码学工具箱：需要 MD5 或其他上游加密时在 Provider 内实现或打包可用实现，不能依赖旧宿主 crypto 全局。

错误类别：Unsupported / NotFound / Authentication / Network / RateLimit / Timeout / InvalidResponse / Runtime。Kotlin 取消原样抛出；宿主不接受旧会话结果；JS 的 `category` 对应这些字符串；未知业务失败不要用空列表掩盖。默认截止与 HTTP 截止分别存在，不能承诺长时间后台任务一直运行。

交付前至少覆盖：

- [ ] 无能力、无数据、无登录
- [ ] 分页末页
- [ ] 重复 ID、带标点 ID
- [ ] 实际音质下降
- [ ] 不同账号隔离
- [ ] 错误与取消
- [ ] 上传：整块确认、错误 offset、句柄失效、去重与发布
- [ ] 一起听：空队列、序列 / 版本、真实上游协调行为

契约夹具证明类型链路，不替代具体音源的上游集成验证。验证按 [GUIDE 第 7 节](GUIDE.md#7-安装与验证) 执行，以保存的 TEST XML 判断失败、错误与跳过；编译成功不等于测试已运行。改 SDK 本身时先改 `schema.mjs` 与 `documentation.mjs`，再生成并跑一致性检查；tag、字段顺序与类型保持兼容，新方法提供 Unsupported 默认。

## 相关

- 契约与边界：[GUIDE](GUIDE.md)
- 操作清单：[SDK_OPERATIONS](SDK_OPERATIONS.md)
- 字段参考：[SDK_TYPES](SDK_TYPES.md)
- JS 差异：[JS](JS.md)
