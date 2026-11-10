# 架构与模块职责

更新：2026-11-10。此文描述当前生产链路，不把早期兼容迁移方案视为现行行为。

## 1. 依赖与职责

```text
app-android -> app -> core -> provider-sdk-kmp
```

- `provider-sdk-kmp`：独立强类型业务契约、数据类型、默认批量行为和受控 runtime。
  不依赖 core、Compose、宿主数据库或具体音源。
- `core`：账号会话、统一音乐源、本地库、播放/下载/缓存、歌词与对外集成，不含 UI。
- `app`：Compose UI、ScreenModel、业务 Repository 与平台呈现。
- `app-android`：Android Activity、Application、MediaSessionService 等平台入口。

## 2. 唯一远端数据链

```text
Repository / PlaybackController / MediaResolver / Integration
    -> UnifiedMusicSource
        -> 已注册的本地音频 / 下载副本
        -> ProviderSdkSessions.current()
            -> ProviderHost
                -> SDK MusicProvider
                    -> Kotlin / JS / 可信 Native adapter
```

统一层先处理本地数据，纯本地/离线请求不创建远端会话。
远端请求只访问当前账号会话，外部 Provider ID 不匹配时返回 Unsupported，
不会按标题、裸 ID 或失败状态去找另一个音源。

`ProviderManager` 只管理描述符选择和会话失效，不分配端口、不启动服务、
不分发同步 JSON 请求。`ModuleManager` 只安装 SDK 脚本，手动/仓库导入共用可回滚事务。
脚本 Provider 与可信 Kotlin/JNI、桌面 Native worker 都实现相同的 `MusicProvider` 业务契约；
JNI/worker 是适配传输，不是另一套 API。旧 HTTP/Binary/JNI JSON 协议和工厂分支已移除；
旧 JS 实现只存在于测试夹具。
共享工作副本中未跟踪的 FFM/compat 旧文件保留在磁盘，但已排除出生产源集，
验证脚本检查最终 core JAR，避免意外重新打包它们。

## 3. 生命周期与数据语义

- 会话身份包含 Provider、SDK 账号、修订号；凭证版本、设置快照变化会新建会话。
- 旧会话先同步失效并取消调用，排空后只释放一次；切换期间的旧结果不能写回。
- 能力列表每会话只读取一次；失败/取消不缓存。
- ID 区分大小写且不转数值；播放/目录原有入口使用带 Provider 命名空间的 `CPMediaId`。
  新增的请求对象业务方法显式绑定当前账号会话，内部 ID 使用 Provider 原始字符串。
- 批量详情保序、保留重复项、逐项失败；远端失败不丢弃同批次本地结果。
- 毫秒、实际音质、显式 Header 原样传递；未知计数、码率、格式和到期时间不猜值。
- 账号 Cookie 不再自动补入媒体流、预加载、下载和流中继请求。

## 4. 类型化业务入口

app Repository 与播放链路直接消费 `UnifiedMusicSource`，不再经过
`MusicApiService`、`MusicSourceFromApi` 或 JSON 输出投影。
歌单内搜索不再绕过 Repository 读取 raw JSON 缓存或自行传 Cookie。

无活跃 Provider 时不会回退到旧 JSON 缓存，取消也不会被吞成缓存错误。
多 Provider 容灾执行路径已删除。
播放文件缓存仍按 Provider/账号/凭证版本/设置隔离，并以实际音质标识。

当前 SDK 契约含 160 个业务操作；除歌曲与目录读取外，登录、推荐、评论/私信、
一起听、MV/视频、DJ、日历/动态、云盘和目录写操作已有类型化链路。
`ExtendedOperations`、扩展 JS/JNI 分发、Host 方法与统一层方法由同一 schema 生成，
旧编号不变。操作清单见 [SDK 操作表](SDK_OPERATIONS.md)，
本批范围与尚未移植的类别见 [迁移状态](SDK_MIGRATION_STATUS.md)。
具体音源未声明或未实现的能力仍返回 Unsupported；契约测试不代表真实上游或真机联调。
独立 Lyrico 歌词插件、本地边车、AMLL、本地视频不受影响。

## 5. 源集

```text
commonMain -> jvmMain -> {androidMain, desktopMain}
```

| 源集 | 职责 |
|---|---|
| `commonMain` | 业务模型、SDK 接口、会话调度、expect 抽象 |
| `jvmMain` | Android/Desktop 共用的文件/归档、Ktor 服务、QuickJS runtime |
| `androidMain` | Android Context、Media3、平台服务 |
| `desktopMain` | Desktop 媒体引擎、窗口/托盘、受信任 Native worker |

桌面持久化路径统一取 `DesktopDataDir`。不为新音源新增私有宿主目录。

## 6. 验证与后续工作

- 必跑 `:app:compileKotlinDesktop` 与 `:core:compileKotlinDesktop`。
- [定向验证脚本](../../scripts/verify-provider-chain.ps1) 使用独立 cache/build，
  检查 SDK、统一层、导入回滚、缓存隔离、播放流头与 Repository 的 XML，要求零跳过。
- 独立运行完整 `:core:desktopTest`；旧工作副本里的测试可能仍要求已退役协议成功，
  需要与现行契约区分，不能通过恢复隐式兼容来“修绿”。
- 后续扩展能力应先加 SDK schema/接口默认实现、跨语言生成与契约测试，
  再接统一层、Repository 和 UI，不能另开 v3 JSON 入口。

开发入口：[Provider 指南](PROVIDER_DEV_GUIDE.md) · [Manifest](MANIFEST_REFERENCE.md)。
此前的 `PROVIDER_TYPED_RUNTIME.md`/SDK proposal 是过程记录；
其中“仍回退旧 Provider”的迁移描述不再是当前生产行为。
