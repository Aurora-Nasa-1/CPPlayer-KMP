# A2UI 生成式界面

> A2UI 在 CPPlayer 里的**唯一用途**、所用库、协议版本与动作契约。
> 读者：要改私人 DJ 台、A2UI 设置页或动作映射的贡献者。

## 1. 用在哪里

| 入口 | 位置 | 形态 |
|---|---|---|
| 播放器「更多」面板 | `ui/component/PlayerMoreBottomSheet.kt` | 卡片入口 → 弹层 |
| 桌面播放页顶栏 | `ui/screen/DesktopPlayerScreen.kt` | 工具按钮 |
| 弹层本体 | `ui/component/LivingDjDeskSheet.kt` | `ModalBottomSheet` 内的 A2UI Surface |
| 设置 | 设置 → 通用 → AI 与 A2UI | 表单页 |

A2UI **不是**通用页面生成器，也不挂在搜索页：它只读当前播放曲目与队列，产出
一个可操作的「私人 DJ 台」。歌曲解读也在这同一个 Surface 内生成（`send_prompt`）。

## 2. 依赖与版本

协议解析、数据绑定、组件绘制全部来自社区 KMP 移植 `ai.littlepress.a2ui`，
不再维护自写协议模型或渲染层。依赖在 `app` 的 `commonMain`：

| 坐标 | 用途 |
|---|---|
| `ai.littlepress.a2ui:a2ui-model` | 协议模型、`A2uiMessageProcessor` |
| `ai.littlepress.a2ui:compose-runtime` | `A2uiMessageParser`、组件运行时 |
| `ai.littlepress.a2ui:material3-a2ui` | `A2uiSurface` 与 Material 3 Basic Catalog V1 |

| 版本 | 含义 |
|---|---|
| `1.0.0-alpha02` | **库**版本（`gradle/libs.versions.toml` 的 `a2ui`） |
| `v0.9` | **协议**版本，由 `A2uiProtocolConstants.PROTOCOL_VERSION` 提供 |

两者不是一回事。`v0.8` 是旧协议，当前库只接受 `v0.9`（兼容 `v0.9.1`），
因此宿主里不再保留 `v0.8` 的兼容分支。Catalog id 必须精确等于
`https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json`。

## 3. 宿主薄层

| 文件 | 职责 | 不做什么 |
|---|---|---|
| `ui/a2ui/A2uiRuntime.kt` | JSON 分帧、`processInput`、Surface 清理、动作分发 | 不解析组件、不写渲染 |
| `ui/a2ui/A2uiAgent.kt` | 系统提示词、真实曲目上下文、无 Key 时的本地 Surface | 不编造曲目 |
| `ui/a2ui/A2uiPlatform.desktop.kt` / `.android.kt` | 构建 Processor 与 Catalog（图片走 Coil） | 不碰业务状态 |

`A2uiMessageProcessor.collectMessages()` 负责驱动消息队列；`activeSurfaces`
是唯一渲染来源。`deleteSurface` 是宿主清理消息，不计入「已接收消息数」。

## 4. 动作契约

模型只能发以下事件，参数全部从官方 `A2uiClientEventMessage.context` 读取：

| 事件 | context | 真实动作 |
|---|---|---|
| `play_track` | `mediaId` | `playback.playQueue` |
| `play_tracks` | `mediaIds[]` | `playback.playQueue` |
| `enqueue_track` | `mediaId` | `playback.addToQueue` |
| `enqueue_all` | `mediaIds[]` | 逐首 `addToQueue` |
| `search_similar` | `query` | 打开搜索页（缺省用当前曲目名 + 歌手） |
| `set_eq_preset` | `preset` | 写入 5 段 EQ，无法识别时**不修改**当前声场 |
| `send_prompt` | `prompt` | 重新生成 Surface |

`mediaId` 必须是 `AppModel.toMediaId(...)` 的产物（`provider://type/id`）。
界面只允许出现上下文里给过的真实曲目；没有可播放曲目时只提供搜索入口。

## 5. 无 Key / 在线失败

| 条件 | 行为 |
|---|---|
| 有 API Key，请求成功 | 流式解析模型输出的 A2UI v0.9 JSON |
| 有 Key，请求失败且开启「无 Key 时用真实曲目编排」 | 先 `deleteAllSurfaces()`，再喂入本地编排，避免残留双 Surface |
| 无 Key，且开启该开关 | 用 `musicRepository.search` 的真实结果编排；搜不到则只用当前队列 |
| 无 Key，且关闭该开关 | 直接报错，不生成占位内容 |
| 模型没返回可渲染消息 | 若开关开启则回退本地编排，否则报错 |

「离线」在这里的含义是**真实数据本地编排**，不是假数据模拟。
`AiConfigManager.streamChat` 在无 Key 时直接失败，不产出假文本。

## 6. 设置项

配置由 `core/.../ai/AiConfigManager.kt` 持久化在 `cp_ai_settings` 命名空间：

| 键 | 影响 |
|---|---|
| `ai_enabled` | 关闭后不再创建 Surface |
| `ai_provider` / `ai_base_uri` / `ai_api_key` / `ai_model` | 请求目标 |
| `ai_temperature` | 请求里的 `temperature` |
| `ai_stream_enabled` | SSE 流式解析 |
| `ai_offline_simulation` | 无 Key / 失败时的真实曲目本地编排 |
| `ai_expressive_motion` | `A2uiSurface` 结构切换动画 |
| `ai_haptic_feedback` | 动作确认触觉 |

页面必须走 `SettingsPage` + `SettingsSection` + `Settings*Item`，与其余设置页同宽同距；
文案全部来自 `CpStrings.ai`（`i18n/AiStrings.kt`），不要在调用点写死中文。

## 7. 验证

| 目标 | 用例 |
|---|---|
| 分帧与协议 | `A2uiRuntimeTest.framer_*` / `dj_messages_are_real_v09_*` |
| 官方 Catalog 可处理 | `A2uiRuntimeTest.local_surface_is_processed_*` |
| 离屏可渲染 | `A2uiRuntimeTest.local_surface_renders_*` |
| 动作参数 | `A2uiActionHandlerTest` |
| 设置页版式 | `AiSettingsVisualTest`（手机 390×844、桌面 1100×900 出图） |

测试判据同 [BUILD](BUILD.md)：只认 `TEST-*.xml` 且 `skipped="0"`；版式必须出图核对。
