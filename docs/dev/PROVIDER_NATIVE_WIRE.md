# 原生 Provider 的 Wire 格式（`kmp-sdk-v1`）

更新：2026-11-14。本文补的是 [Manifest 参考](MANIFEST_REFERENCE.md#原生-sdk-包) 里
「类型、字段顺序和操作码以 SDK 生成物为准」这句话缺掉的部分。

那个说法对作者不成立：**作者手上只有文档，没有生成物**。实测（`lab-native` 包在
桌面客户端里跑通）之后，把真实格式写在这里。事实源：`provider-sdk-kmp/src/commonMain/kotlin/cp/player/sdk/Wire.kt`、
`NativeWireCodec.generated.kt`、`provider-sdk-kmp/src/jvmMain/kotlin/cp/player/sdk/jni/NativeSession.kt`。

## 1. 四个导出符号

宿主对每个包 `System.load(<包内库的独立快照>)`，然后反射这 4 个符号。名字必须精确，
`JNIEnv*` 与接收者（`jclass`）由 JNI 隐式传入，下面的签名省略它们。

| 符号 | 参数 | 返回 |
|---|---|---|
| `Java_cp_player_sdk_jni_NativeSession_createNative` | `jstring providerId, jstring accountId, jstring credential, jstring settingsJson` | `jlong` 句柄；**返回 0 视为创建失败** |
| `Java_cp_player_sdk_jni_NativeSession_invokeNative` | `jlong handle, jint operation, jbyteArray request` | `jbyteArray`；可为 `null`，宿主随后会按截断失败处理 |
| `Java_cp_player_sdk_jni_NativeSession_cancelNative` | `jlong handle` | `void`；线程安全、立即返回、**不得释放句柄** |
| `Java_cp_player_sdk_jni_NativeSession_closeNative` | `jlong handle` | `void`；调用已排空，只调用一次 |

`credential` 可能为 `null`。`settingsJson` 是设置快照的 JSON 文本（不是对象）。

## 2. 基本类型（小端、定长）

| 类型 | 编码 |
|---|---|
| `Int` | `i32`，4 字节小端 |
| `Long` | `i64`，8 字节小端 |
| `Boolean` | 1 字节：`0` / `1`；**读到其他值直接判为非法** |
| `String` | `i32` 字节长度 + UTF-8 原始字节（长度是**字节**数，不是字符数） |
| `ByteArray` | `i32` 长度 + 原始字节 |
| `T?`（可选） | 1 字节存在位（同 bool）+ 存在时的值 |
| `List<T>` | `i32` 元素个数 + 依次编码的元素 |

没有对齐填充、没有 varint、没有字段名、没有版本号。**记录是纯位置序列化**：
字段顺序就是 `SDK_TYPES.md` 里表格的行顺序，不能跳过、不能换序、不能追加到末尾以外的位置。

## 3. 响应封套

返回的字节数组整体是：

```text
[1 字节 bool] [载荷]
```

- `0` → 成功，后面紧跟该操作的响应 DTO；
- `1` → 失败，后面紧跟 `Failure`，即 `category: String` + `message: String`。

失败类别用 `Unsupported` / `NotFound` / `Authentication` / `Network` / `RateLimit` /
`Timeout` / `InvalidResponse` / `Runtime`。`category` 必须非空白且 ≤128 字符，
`message` ≤4096 字符，否则宿主会把它改写成 `InvalidResponse("Invalid provider failure")`。

## 4. 操作码

`operation` 就是 [操作参考](SDK_OPERATIONS.md) 第一列的 tag（`2..166` 为业务操作）。
请求按该操作的请求 DTO 编码，响应按响应 DTO 编码。

**`operation = 1` 是 `CAPABILITIES`，原生包必须实现。** 请求是 `Bytes`（`i32 长度 + 字节`，
通常长度为 0），响应是 `Capabilities { supported: List<String> }`。宿主每次调用前都会先取
能力表；不实现这一项，所有业务调用都会以 `Unsupported` 结束。
`6..9`（`HTTP`/`SHA256`/`STORAGEGET`/`STORAGESET`）在 SDK 里有 DTO，但宿主不会向原生包派发，
原生库要么自己联网（JNI 不受沙箱限制），要么不联网。

`Bytes` 请求（无参操作）编码后是 `i32 0`（四个零字节），不是空数组。

## 5. 宿主会在解码后继续校验

这些不是线格式问题，但会让正确编码的结果仍然失败：

- 整条消息 ≤4 MiB；任何 `List` ≤500 项；`WireReader` 结束时必须**刚好读完**，多一个字节就是 `Trailing wire data`。
- `search`：`items.size <= request.limit`；`total` 为 `null` 或落在 `0..2^53-1`。
- 每条 `Track`：`id` 非空白且 ≤1024 字符；`title` ≤4096；`durationMs` 在 `0..2^53-1`；
  `artists` ≤64 且每个 `name` 非空白；`coverUrl` 必须能解析成 `http`/`https` 且有 host；
  `album` ≤4096。
- `Long` 字段一律按「精确安全整数」校验：超过 2^53-1 或非整数会被拒绝。

## 6. 最容易踩的两条

1. **登录态必须从 `credential` 推导，不能只放内存。** 宿主在 `authenticate` 成功后保存
   cookie、**换掉整个会话并重新 `createNative`**；把 `loggedIn` 存在全局变量里的实现在这一步
   会退回未登录，表现是「扫码/登录显示成功，但账号页仍是未登录」。正确做法：
   `credential` 非空即已登录，用户身份也从凭证推。
2. **`getUserPlaylists` 的 `id` 可能是宿主账号键**（实测桌面端传了 `9084388061`），
   而不是上游用户 ID。按「当前登录用户」处理最稳，不要拿它去 `creatorId == id` 过滤。

## 7. 最小可用检查表

- [ ] 4 个符号都在导出表里（`dumpbin -exports` 应有 4 个名字，乱序无所谓）。
- [ ] `createNative` 返回非 0。
- [ ] `operation = 1` 返回至少 `tracks.search`、`tracks.get`、`tracks.resolve`。
- [ ] `search` 返回的 `total`/`hasMore` 能是 `null`（未知），但给了就必须自洽。
- [ ] 失败路径返回 `1` + `Failure`，而不是抛异常或返回空数组。
- [ ] 解码输入的每个字段都用同一套位置规则，别按 JSON 思路跳字段。
