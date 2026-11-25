# 可选扩展：缓存配合、宿主状态感知与多档封面规则

> 本文面向 **SDK Provider 作者**。**本页描述的所有能力均为可选项**：如果你不添加任何额外代码，宿主已经会在底层自动提供 HTTP 条件缓存、并发请求合并、后台降载与封面本地高转低复用。

---

## 1. 为什么你可以“零代码”直接受益

宿主在以下两层已经默认托管了大部分省流量与抗重下工作：
1. **`ProviderHost` 会话级防重与读缓存**：同一账号会话内并发或短时间重复调用 `get`、`getPlaylist`、`getAlbum`、`getLyrics`、`search` 等只读接口会自动合并与缓存；写操作（收藏、增删歌单等）会自动失效缓存；`tracks.resolve` 与登录接口绝不缓存。
2. **`ScopedProviderRuntime.http()` 自动条件缓存**：对插件发起的可缓存公共 `GET` 请求，宿主会自动合并并发请求并在持有 `ETag` / `Last-Modified` 时附带 `If-None-Match` / `If-Modified-Since`（命中 `304` 零正文流量）。
3. **封面本地降采样复用**：同一张封面只要本地已经下载过原图或任意更高分辨率分档，列表、迷你播控或托盘再请求小尺寸时由宿主本地 Skia/Bitmap 直接裁剪生成，**不发任何网络请求**。

如果你希望针对特殊图床或省流模式进一步压榨流量，只需按需选用下方**三个极轻量入口**中的任意一个。

---

## 2. 可选方式一：在 `manifest.json` 声明 `imageResize`（零代码）

如果你的上游图片 CDN 支持通过 URL 参数直接裁剪返回小图（例如网易云的 `?param=256y256` 或其它图床的 `?w=256`），推荐直接在 `manifest.json` 中声明 `imageResize`，**无需改动插件源码**：

```json
{
  "id": "my-provider",
  "name": "My Music Provider",
  "version": "1.0.0",
  "type": "js",
  "entryPoint": "index.js",
  "contract": "kmp-sdk-v1",
  "imageResize": {
    "mode": "query_template",
    "template": "{url}?w={w}&h={h}",
    "stripQueryParams": ["w", "h", "sign", "expires"],
    "supportedWidths": [128, 256, 512, 1024]
  }
}
```

### 字段说明
| 字段 | 类型 | 说明 |
|---|---|---|
| `mode` | `"netease_param"` \| `"query_template"` \| `"none"` | 裁剪模式。省略时：已知网易云域名自动走 `netease_param`，其余第三方地址保持原样 |
| `template` | `string?` | 当 `mode = "query_template"` 时生效，支持 `{url}`、`{w}`、`{h}`、`{size}` 占位符 |
| `stripQueryParams` | `string[]` | 计算本地缓存主键时需剥离的动态查询参数（如防盗链签名 `sign`、`t`、`expires`）。**仅影响本地缓存键匹配，绝不会修改实际发往网络的请求 URL** |
| `supportedWidths` | `int[]` | 上游 CDN 允许的离散宽度白名单；宿主会自动向上对齐到最接近档位 |

---

## 3. 可选方式二：动态 URL 覆盖 `MusicProvider.resolveCoverUrl`

对于无法用简单静态模板表达的图床（例如需要调整路径分段 `300x300` -> `800x800`），Kotlin / 原生适配器可按需覆盖 [`MusicProvider.resolveCoverUrl`](file:///workspace/nimble-bose/provider-sdk-kmp/src/commonMain/kotlin/cp/player/sdk/MusicProvider.kt)：

```kotlin
override fun resolveCoverUrl(rawUrl: String, targetPx: Int): String? {
    // 仅处理自己的图片路径；返回 null 时自动退回宿主默认策略
    if ("example-cdn.test" !in rawUrl) return null
    return rawUrl.replace("/size/original/", "/size/${targetPx}x${targetPx}/")
}
```

---

## 4. 可选方式三：按需读取宿主运行态 `hostState`

宿主当前是否最小化到托盘、是否处于省流模式，都会自动对后台预加载做节流。如果你的插件内部会在单次业务方法里顺带发起多条额外子查询（例如自动批量补齐专辑详情、热评或高清背景图），可以可选读取当前提示来跳过非关键请求：

- **Kotlin 插件**：读取 `context.hostState`（或 `context.runtime.hostState()`）
  ```kotlin
  val hs = context.hostState
  if (!hs.inBackgroundTray && !hs.dataSaver) {
      // 可选：拉取额外的非关键富媒体信息
  }
  ```
- **JS 插件**：调用 `globalThis.__sdkHostState()`（若可用）或读取 `context.hostState`
  ```js
  const hostState = typeof __sdkHostState === "function" ? __sdkHostState() : null;
  if (!hostState || (!hostState.inBackgroundTray && !hostState.dataSaver)) {
      // 正常执行可选补充查询
  }
  ```

`HostRuntimeState` 字段含义：
- `inBackgroundTray: Boolean` — 主窗口是否已最小化到托盘（或后台挂起）
- `dataSaver: Boolean` — 是否处于省流偏好模式
- `preferredImagePx: Int` — 当前宿主建议的最大封面像素档位（托盘后台通常为 `128`，省流通常为 `256`，正常前台为 `512+`）
