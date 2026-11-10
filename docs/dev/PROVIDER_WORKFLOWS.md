# Provider 分域实现指南

更新：2026-11-10。面向 `kmp-sdk-v1` Provider 作者。此文说明如何组合业务方法；
逐项签名、能力名见 [操作参考](SDK_OPERATIONS.md)，全部字段、类型、默认值见
[字段参考](SDK_TYPES.md)。两份参考与 IDE 注释由同一份 schema 和文档元数据生成。

## 1. 实现与声明

Kotlin 实现 `MusicProvider`；扩展方法通过 `ExtendedOperations` 继承，未实现时抛
`ProviderException`，类别为 `Unsupported`。原有能力常量位于 `Capability`，
扩展常量位于 `ExtendedCapability`。不要仅声明能力而保留默认空实现。

JavaScript 在 `globalThis.provider` 上提供 `capabilities` 数组与对应异步方法，
每次收到 `(request, context)`。TypeScript 从 `provider-sdk-kmp/js/index.mjs`
导入 `defineProvider` 与导出的类型；`KnownCapability` 提供当前能力名的补全，
未知字符串仍可用于向前兼容。作者侧 helper 检查“声明的已知能力有对应函数”，
宿主仍会独立校验实际输入、输出和会话。

每个 Provider 不必实现全部业务。`tracks.getMany` 可由 `tracks.get` 默认逐项实现，
其余能力不隐式借用另一个端点或另一个账号。
无参数操作的 Kotlin 请求仍是 `Bytes(byteArrayOf())`；JS 收到 `{ value: Int8Array(0) }`
或等价的空 typed array。

SDK 声明、运行期实现、统一层入口、页面入口是不同的完成条件。某操作存在于 SDK，
不保证每个页面已有入口，也不保证某个已安装音源实现了它。

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

SDK 请求里的 ID 是当前 Provider 的裸字符串；`001`、`A`、`a`、`A,a` 不能转换或拆分。
`ProviderContext.accountId` 是宿主账号槽位，不应当作上游用户 ID。
应用面向命名空间 ID 的入口会核对 Provider；直接传 request DTO 的扩展方法已经绑定当前会话。

`limit` 是结果条数上限，`offset` 是从零开始的条目偏移。上游用页码时，由 Provider 转换，
例如固定页长的服务使用 `offset / limit + 1`。不要把 offset 当 pageNo 原样传。
单页最多 500 项，完整编解码消息最多 4 MiB；更小的上游上限仍然生效。
`total` 和 `hasMore` 不知道时返回 null，不用当前页长度或 `items.isNotEmpty()` 伪造。

游标不解析、不 URL 解码、不转数字。首次输入通常为 null；后续回传响应 token，
包括空字符串。不要将 `cursor || undefined` 用作归一化。
未知 `hasMore` 与已知 false 不同；调用者需要结合该业务的上游分页语义决定是否继续。

收藏 ID 集合超过 500 项时，实现 `getLikeListPage(PagedIdRequest): IdPage` 并声明
`accounts.likes.page`（tag 166）。`id = "self"` 指当前登录账号，每页仍最多 500 项；
`hasMore` 必须明确给出，不能用空页表示“还有下一页”。宿主按实际返回条数递增 offset，
成功取完所有页才发布完整集合，最多合并 1,000,000 项。旧的 `accounts.likes` /
`getLikeList(UserRequest)` 保持有界完整集合语义，不能静默截断为前 500 项。

目录相关的默认 DTO 与过滤器不是任意上游参数字典：

- `getNewAlbums` 是既有的有限发现选择；`browseNewAlbums` 支持 area/offset，
  `getLatestAlbums` 是最新发现选择，`getAlbumChart` 有 `new`/`hot` 与可选年月。
- `getAlbum` 返回完整专辑内容，仍受 500 项和字节上限约束，不可静默截断成“完整”。
- `getUserFollows` / `getUserFolloweds` 的既有返回值是 `ArtistPage`，不是 `SocialUserPage`。
  这是 v1 已有形状；不要在当前 tag 下换 DTO。
- 推荐、FM、相似内容、历史推荐、数字专辑、歌手浏览分别声明能力。
  年月、区域、首字母等支持值应在具体音源说明里列出；不支持时明确返回 Unsupported。

## 3. 播放、音质与歌词

`resolve`、`resolveMv`、`resolveVideo` 返回 `MediaResource`。Provider 按实际资源填写
`actualQuality`，明确列出媒体访问头。未知格式、码率、大小、过期时间留 null。
时长和绝对时间分别用毫秒和 Unix epoch 毫秒；`ScrobbleRequest.playedSeconds` 是明确的秒数例外。

音频下载调用 `resolve`；MV 与视频下载分别调用 `resolveMv` / `resolveVideo`。
宿主使用实际质量和已知容器格式确定下载记录与后缀，过期直链重新解析，
只转发 Provider 明确返回的媒体头，不从账号会话自动注入 Cookie。
播放缓存的跨音质回退必须匹配账号、凭据版本与设置摘要，旧的无归属索引不参与账号回退。
保存或清除当前账号凭据会立即取消旧会话请求；Provider 必须响应协程取消。

`media.qualities` 对应 `getMediaQualities`，支持 `song`、`audio`、`mv`、`video`。
每个 `QualityOption.id` 是唯一的业务音质 key；`available = false` 可表示当前账号不可用，
应用会过滤它。不要返回歌单或专辑的“音质”；不要以 manifest 静态映射冒充实时可用性。
manifest `qualityOptions` 负责应用档位与业务 key 映射，`actualQuality` 负责实际返回档位。

`getLyrics` 至少返回 LRC、TTML、YRC 中一种主格式。没有匹配时返回 NotFound。
翻译与罗马音不能单独冒充主歌词。独立 Lyrico 插件与本地边车不属于这个 SDK 包协议。

## 4. 评论与楼层

旧的 `getComments(CommentPageRequest)` 只接收 offset，新增
`getCommentPage(CommentQuery)` 才能接收输入 cursor。新 Provider 需要游标分页时声明
`comments.page`。统一层仅在请求 cursor 为 null 且新能力不存在时使用旧能力；
不会为了降级而丢弃非 null token。

`sortType` 的 SDK 语义为 1 推荐、2 热度、3 最新。上游若使用 99 等不同值，由 Provider
映射回 SDK 值。响应必须带真实有效排序；例如推荐被降为热度时返回 2，翻页回传它。
资源类型为 `music`、`song`、`mv`、`playlist`、`album`、`dj`、`video`、`event`；
应用的命名空间 helper 将歌曲的 `music` 映射到 `song` ID 类型。

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

上例说明数据映射，`service` 由音源实现；可编译类型示例见
[`contract.test.mts`](../../provider-sdk-kmp/js/contract.test.mts)。
不要把上游评论 JSON 整体塞进某个字符串字段。

楼层 `getFloorComments` 首页使用 `time = 0`，下一页原样回传 `nextTime`。
`topReplies` 只是内联预览，不代表已获取完整楼层页。发表成功没有返回评论体时，
`CreatedComment.comment` 可以为 null，由应用刷新列表。

评论与回复 DTO 保留了 v1 的数字 comment/user ID，范围受 JS safe integer 限制；
不要声称所有现有评论 ID 都已经是不透明字符串。需要支持超界或非数字身份时，
应增加兼容的新操作，而非修改旧字段顺序或偷偷转为 0。

## 5. 私信、通知与动态

`getPrivateMessages` 返回会话摘要，`getMessageHistory` 返回新到旧消息，
`getRecentContacts` 返回联系人。`sendMessage` 只发纯文本，`sendResourceMessage`
发送 `song`、`album`、`playlist` 资源及附言；两者不是同一种正文编码。
多收件人请求为 1..100 个裸用户 ID；已读操作单独使用 `markMessageAsRead`。

评论通知、提及、系统通知分别对应 `getCommentNotifications`、
`getMentionNotifications`、`getSystemNotifications`。前者和系统通知使用游标，
提及使用条目 offset。`Notification` 只包含文本、时间与已知的发送人/资源引用，
不回传旧 API 封套。`getUnreadCount` 的各类计数独立，不能合并到私信数。

`getCalendar` 的可选范围使用 epoch 毫秒；`getEvents` / `getUserEvents`
使用不透明 cursor。动态删除、转发和资源分享有独立 typed 方法，
`SocialEvent.text` 是正文，不是 JSON。图片通过明确 URL 列表表达。

## 6. 一起听

调用顺序通常是状态检查、建房或验邀请/接受邀请，再进行心跳、快照、命令和队列同步。
房间与用户 ID 为字符串。`inRoom = true` 必须有 room。
Provider 不创建第二个宿主后端，也不自行启动服务端。

| 输入 / 输出 | 约定 |
|---|---|
| `ListenHeartbeatRequest` | `positionMs` 为毫秒，`playStatus` 为 PLAY / PAUSE |
| `ListenHeartbeat` | 正数 `intervalMs`，不转成上游旧 `timeSpan` 秒数 |
| `ListenCommand` | PLAY / PAUSE / PROGRESS / GOTO；GOTO 必须带 trackId |
| `ListenQueueRequest.action` | REPLACE / ADD / DELETE，按上游语义执行 |
| `ListenQueueRequest.trackIds` | 保序保留重复项；空 REPLACE 明确清空 |
| `ListenSnapshot.playlist` | null 是没有队列快照；空列表是有快照但队列为空 |
| `sequence` / `serverSequence` / `version` | 非负 safe integer，不能用时间戳猜值 |

应用通过版本与序列号协调状态；Provider 不把缺失值伪造成最新命令，也不吞掉失效会话。

应用页面已通过 `SdkListenTogetherBackend` 接入上述 SDK 操作；切换音源或账号会清除
旧房间和待发送指令。`MutationResult.success = false` 必须视为失败。
当前应用房间模型仍用 Long 用户 ID：只有能够精确往返的正整数 ID 可进入该 UI，
带前导零、超范围或其他字符串会明确返回 Unsupported，SDK 本身仍完整保留原始字符串。

## 7. MV、视频与 DJ

MV 与视频使用独立 ID 类型与能力；相同字符串不说明是同一种资源。
`resolution` 是垂直像素数，宿主接受 1..16384，返回资源仍须描述真实质量。
视频分组 `getVideoGroups` 与类别 `getVideoCategories` 是不同操作；
`getVideosByGroup.id` 使用分组 ID。普通时间线与推荐时间线也分别实现。

DJ 电台 `DjRadio` 与节目 `DjProgram` 不可互换。节目包含可播放的 `Track`；
`getDjPrograms.id` 是电台 ID，`getDjProgram.id` 是节目 ID。
新/热榜使用 `browseDjRanking(kind)`；旧 `getDjRanking` 保留原有默认榜单语义。
最近收听 `getRecentDjRadios` 返回电台，不是节目。
电台分类、分类下电台、订阅者、用户电台分别使用明确的 typed 方法。

## 8. 歌单写入与导入

收藏与点赞操作接收目标布尔状态，不能实现为“切换当前状态”，否则重试会把状态切回去。
`updatePlaylist` 是局部更新：null 不改，`tags = []` 清空标签，至少提供一个更新字段。
`reorderPlaylists` / `reorderPlaylistTracks` 要求 1..500 个唯一 ID，
严格按输入顺序执行；不要排序或悄悄去重。与一起听允许重复的队列区分。

封面上传接收字节，返回 `ImageUploadResult`；已有图片 ID 可用 `updatePlaylistCover`
应用到歌单。具体上游是否在上传时同时应用图片，应在音源说明中写明，
不要将一个静态 URL 当作已上传 imageId。

`createPlaylistImport` 恰好选一种源：非空 `tracks`、非空白 `text` 或非空 `links`。
导入名非空白、最多 256 字符。返回 taskId，之后用 `getPlaylistImportStatus(IdRequest(taskId))`
轮询，响应必须是同一 taskId。状态是 pending / running / completed / failed；
未知匹配计数为 null，失败说明不得含原始凭证。

## 9. 图片与云盘上传

图片只接受 `image/jpeg`、`image/png`、`image/webp`，正文 1..1048576 字节。
filename 是 basename，不是宿主路径；支持 MIME 不等于宿主会替 Provider 转码。
Kotlin 使用 `ByteArray`，JS 使用 `Int8Array` 或 `Uint8Array`。
有符号字节转 HTTP payload 时保留低 8 位，不能把负数夹成 0。

云盘上传有三个明确阶段：

1. `prepareCloudUpload` 收到文件名、正数总字节数、32 位十六进制文件 MD5、正数 bps。
   返回本会话的 uploadId、needsUpload、下一个 offset 和 1..1048576 的 chunkSize。
2. 需要传输时，`uploadCloudChunk` 按协商大小传入 offset、content、last。
   Provider 校验句柄归属、顺序、大小和最后一块边界；整块成功或整块失败，
   响应 offset 必须等于输入 offset 加正文长度，complete 必须等于 last。
3. `completeCloudUpload` 使用句柄发布文件，可带标题、歌手、专辑。
   不需上传的去重路径也由 Provider 校验何时可发布，不伪造完成确认。

宿主只做边界与确认校验，无法替 Provider 验证服务端上传状态。
凭证、预签名上传 URL、上游 token 留在 Provider 内，业务响应只返回不透明句柄。
runtime HTTP 的实际上传 origin 必须在 sdkOrigins 中；动态上传域名并不绕过白名单。
不要在失败后自动换句柄继续发块，也不要未经上游确认重复发布；重试与断点策略必须
由具体音源定义。切换账号后句柄作废，pending 调用取消。

宿主发起异步调用前会快照二进制请求；这不意味着 Provider 可以在并发调用间共享、
重写一个全局缓冲区。JS runtime 临时存储总量仅 1 MiB，不能把整首歌曲存进去。

## 10. 登录与账号

`authenticate` 使用固定动作名，但具体上游流程仍由 Provider 实现。只填当前动作需要的字段，
不把空字段猜成账号默认值。以下是工作流约定，不是额外开放的 raw endpoint 通道。

| 动作 | 输入与结果 |
|---|---|
| `qr.key` | 获取 key |
| `qr.create` | 使用 key 创建二维码 URL |
| `qr.check` | 使用 key 轮询状态，成功时可返回 credential cookie |
| `login.email` | email/password 及所需 challenge；md5 表示密码是否已预哈希 |
| `login.phone` | phone 与 password 或 captcha，按音源支持的流程 |
| `captcha.send` | phone，用于申请验证码 |
| `captcha.image` | 获取当前流程的图片验证码，结果可带 imageBase64 |
| `webview.url` | 获取登录页 URL 与流程状态 |
| `webview.check` | 以 sessionId 等流程标识检查登录结果 |
| `verify.url` | 获取验证 URL 与 verifyId 等状态 |
| `verify.check` | 以验证标识检查挑战结果 |
| `login.anonymous` | 建立匿名会话所需的上游身份 |
| `logout` | 执行上游退出 |

`AuthActionResult.success/code/status/challenge` 应表达真实状态；不要把“尚在扫码”
当最终失败，或把异常当登录成功。错误类别使用 `Authentication`，原始凭证不进日志。
宿主保存新凭证并替换会话；Provider 不保留旧 context 跨账号续用。
JS 引擎由宿主关闭，不存在可依赖的 JS `provider.close` 回调；
可信 Kotlin 实现的 `close()` 应释放资源。

用户绑定查询使用 `getUserBindings`，只返回 maskedAccount。
`bindPhone` 的 countryCode 不带 `+`，新手机号验证码是 captcha；
`replacePhone` 还需 oldCaptcha。绑定密码为可选明文输入，哈希由 Provider 负责，
与 `AuthActionRequest.md5` 布尔字段、云盘文件 MD5 都不同。

签到、资料更新、关注、收藏、云盘导入/匹配、最近播放均是独立能力，
不要因为支持登录就宣告支持所有账号写操作。

## 11. Runtime、错误与交付

| 边界 | 当前宿主约束 |
|---|---|
| HTTP | GET / POST / PUT / PATCH / DELETE / HEAD；精确 origin 白名单 |
| 明文 HTTP | 只允许 localhost / 127.0.0.1 / ::1；其他请求须 HTTPS |
| 重定向与凭证 | 不自动跟随重定向，不自动补 Cookie/Authorization |
| 请求 / 响应正文 | 各至多 4 MiB；HTTP 总超时 10 秒，连接超时 5 秒 |
| 临时存储 | 当前会话隔离，总量 1 MiB；key 非空白、最多 256 字符；null 删除 |
| Provider 调用 | 默认 15 秒截止，最多 4 个并发 |
| QuickJS | 单会话线程；32 MiB 内存、512 KiB 栈、250 ms 执行预算 |

上游可以用 JSON、表单或其他编码，通过 runtime HTTP 正常解析；业务边界仍交付 DTO。
`context.sha256` 不是通用密码学工具箱；需要 MD5 或其他上游加密时在 Provider
自身实现或打包可用实现，不能依赖旧宿主 crypto 全局。

错误类别使用 Unsupported / NotFound / Authentication / Network / RateLimit /
Timeout / InvalidResponse / Runtime。Kotlin 取消原样抛出；宿主不接受旧会话结果。
JS Error 的 category 对应这些字符串，未知业务失败不要用空列表掩盖。
默认截止和 HTTP 截止分别存在，不能承诺长时间后台任务会一直运行。

交付音源时至少覆盖：无能力、无数据、无登录、分页末页、重复/带标点 ID、实际音质下降、
不同账号隔离、错误与取消。实现上传还需覆盖整块确认、错误 offset、句柄失效、去重与发布；
实现一起听需验证空队列、序列/版本和真实上游的协调行为。
本仓库契约夹具证明类型链路，不替代具体音源的上游集成验证。

运行 [Provider 指南](PROVIDER_DEV_GUIDE.md#6-安装与验证) 中的检查，
以保存的 TEST XML 判断失败、错误和跳过；完整编译成功不等于所有测试已运行。
修改 SDK 本身时，先改 schema 与 documentation.mjs，再生成并运行一致性检查。
操作 tag、既有字段顺序与类型保持兼容，新方法提供 Unsupported 默认实现。
