# 架构与模块职责

> 分层、数据链、生命周期、源集。描述当前生产链路，不含已退役的兼容方案。

## 1. 依赖与职责

```text
app-android -> app -> core -> provider-sdk-kmp
```

| 模块 | 职责 | 边界 |
|---|---|---|
| `provider-sdk-kmp` | 强类型业务契约、数据类型、默认批量行为、受控 runtime | 不依赖 core、Compose、宿主数据库或具体音源 |
| `core` | 账号会话、统一音乐源、本地库、播放 / 下载 / 缓存、歌词、对外集成 | 不含 UI |
| `app` | Compose UI、ScreenModel、业务 Repository、平台呈现 | 经 `MusicBackend` 访问后端 |
| `app-android` | Activity、Application、MediaSessionService | Android 平台入口 |

## 2. 唯一远端数据链

```text
Repository / PlaybackController / MediaResolver / Integration
  └─ UnifiedMusicSource
      ├─ 已注册的本地音频 / 下载副本
      └─ ProviderSdkSessions.current()
          └─ ProviderHost
              └─ SDK MusicProvider
                  └─ Kotlin / JS / 可信 Native adapter
```

| 规则 | 内容 |
|---|---|
| 本地优先 | 纯本地 / 离线请求不创建远端会话 |
| 会话绑定 | 远端请求只访问当前账号会话 |
| ID 归属 | 外部 Provider ID 不匹配时返回 `Unsupported`，不按标题、裸 ID 或失败状态去找另一个音源 |
| `ProviderManager` | 只管理描述符选择与会话失效；不分配端口、不启动服务、不分发同步 JSON 请求 |
| `ModuleManager` | 只安装 SDK 脚本；手动 / 仓库导入共用可回滚事务 |
| 适配器 | 脚本 Provider 与可信 Kotlin / JNI、桌面 Native worker 实现同一个 `MusicProvider`；JNI / worker 是传输，不是另一套 API |
| 已移除 | 旧 HTTP / Binary / JNI JSON 协议与工厂分支；旧 JS 实现只存在于测试夹具 |

共享工作副本中未跟踪的 FFM / compat 旧文件保留在磁盘，但已排除出生产源集。验证脚本检查最终 core JAR，避免意外重新打包它们。

## 3. 生命周期与数据语义

| 项 | 规则 |
|---|---|
| 会话身份 | Provider + SDK 账号 + 修订号；凭证版本或设置快照变化即新建会话 |
| 切换 | 旧会话先同步失效并取消调用，排空后只释放一次 |
| 旧结果 | 切换期间的旧结果不能写回 |
| 能力列表 | 每会话只读一次；失败 / 取消不缓存 |
| ID | 区分大小写且不转数值；播放与目录入口用带 Provider 命名空间的 `CPMediaId` |
| 请求对象方法 | 显式绑定当前账号会话，内部用 Provider 原始字符串 |
| 批量详情 | 保序、保留重复项、逐项失败；远端失败不丢弃同批次本地结果 |
| 数值传递 | 毫秒、实际音质、显式 Header 原样传递；未知计数、码率、格式、到期时间不猜值 |
| 账号 Cookie | 不再自动补入媒体流、预加载、下载与流中继请求 |

## 4. 类型化业务入口

| 项 | 现状 |
|---|---|
| 消费方 | app Repository 与播放链路直接消费 `UnifiedMusicSource` |
| 已删除 | `MusicApiService`、`MusicSourceFromApi`、JSON 输出投影 |
| 歌单内搜索 | 不再绕过 Repository 读 raw JSON 缓存或自传 Cookie |
| 无活跃 Provider | 不回退旧 JSON 缓存；取消不会被吞成缓存错误 |
| 多 Provider 容灾 | 执行路径已删除 |
| 播放文件缓存 | 按 Provider / 账号 / 凭证版本 / 设置隔离，并以实际音质标识 |
| 凭据变更 | 保存或清除会同步取消对应活跃账号的 SDK 请求，其他账号不受影响 |
| 缓存索引回退 | 必须匹配账号、凭据版本与设置摘要；无法确认归属的旧缓存只允许精确键命中 |
| 收藏列表 | 绑定同一账号身份；换音源、换账号、退出登录时清除旧状态 |
| `getLikeListPage` | tag 166，按最多 500 项分页，统一层合并完整集合；旧接口保持兼容 |
| 视频下载 | 按 MV / video 类型调用对应解析器，保留实际质量、格式、过期时间与显式媒体头 |
| 一起听 | 页面经 `SdkListenTogetherBackend` 接入；会话切换清理旧房间与待发指令 |
| 房间 UI | 用户 ID 仍为 Long；无法无损表示的不透明 SDK 用户 ID 明确返回 `Unsupported` |

当前 SDK 契约含 161 个业务操作。除歌曲与目录读取外，登录、推荐、评论 / 私信、一起听、MV / 视频、DJ、日历 / 动态、云盘与目录写操作已有类型化链路。

`ExtendedOperations`、扩展 JS / JNI 分发、Host 方法与统一层方法由同一 schema 生成，旧编号不变。操作清单见 [SDK_OPERATIONS](providers/SDK_OPERATIONS.md)，批次范围与未移植类别见[迁移状态](../history/SDK_MIGRATION_STATUS.md)。

具体音源未声明或未实现的能力仍返回 `Unsupported`。契约测试不代表真实上游或真机联调。独立 Lyrico 歌词插件、本地边车、AMLL、本地视频不受影响。

## 5. 源集

```text
commonMain -> jvmMain -> {androidMain, desktopMain}
```

| 源集 | 职责 |
|---|---|
| `commonMain` | 业务模型、SDK 接口、会话调度、expect 抽象 |
| `jvmMain` | Android / Desktop 共用的文件与归档、Ktor 服务、QuickJS runtime |
| `androidMain` | Android Context、Media3、平台服务 |
| `desktopMain` | 桌面媒体引擎、窗口 / 托盘、JNI 包类加载器 |

桌面持久化路径统一取 `DesktopDataDir`。不为新音源新增私有宿主目录。

## 6. 验证与扩展

| 项 | 要求 |
|---|---|
| 编译 | 必跑 `:app:compileKotlinDesktop` 与 `:core:compileKotlinDesktop` |
| 定向验证 | [verify-provider-chain.ps1](../../scripts/verify-provider-chain.ps1) 用独立 cache / build，检查 SDK、统一层、导入回滚、缓存隔离、播放流头与 Repository，要求零跳过 |
| 完整测试 | 单独跑 `:core:desktopTest`；旧工作副本里的测试可能仍要求已退役协议成功，不能通过恢复隐式兼容来「修绿」 |
| 扩展顺序 | 先加 SDK schema / 接口默认实现、跨语言生成与契约测试，再接统一层、Repository、UI；不另开 v3 JSON 入口 |

## 相关

- 构建与验证：[BUILD](BUILD.md)
- Provider 契约：[providers/GUIDE](providers/GUIDE.md)
- 桌面播放链路：[FLICK_ENGINE](FLICK_ENGINE.md)
