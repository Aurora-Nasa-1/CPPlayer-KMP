# CPPlayer 插件商店与多版本分发指南

更新：2026-11-10。记录音源插件 SDK 升级后的**新旧双仓库体系**、客户端自动平滑迁移机制以及新仓库 `CPPlayer-Plugins-KMP` 的组织与维护规范。

---

## 1. 新旧双轨分发架构

为了**同时保证旧版本客户端能继续下载 v1/v3 插件，新版本客户端能稳定下载 `kmp-sdk-v1` 插件**，商店分发源采用物理隔离的双仓库方案：

```text
老版本客户端（已发行，已固化 URL）
    └── 请求：https://raw.githubusercontent.com/Aurora-Nasa-1/CPPlayer-Plugins/main/index.json
         └── 包含：Legacy v1/v3 插件（runtime: jni / http / binary）

新版本客户端（KMP SDK 架构）
    └── 请求：https://raw.githubusercontent.com/Aurora-Nasa-1/CPPlayer-Plugins-KMP/main/index.json
         └── 包含：kmp-sdk-v1 插件（runtime: js / script / jni / native）
```

### 客户端无感自动迁移（`PluginRepositoryService`）
新客户端启动时会自动检测本地保存的插件源：
- 若存在旧官方源 URL（`.../CPPlayer-Plugins/main/index.json`），自动无缝迁移为新官方源（`.../CPPlayer-Plugins-KMP/main/index.json`）并持久化回写。
- 用户自行添加的第三方订阅源不受任何影响。
- 新安装用户直接使用新仓库默认源。

---

## 2. 新仓库 `CPPlayer-Plugins-KMP` 组织规范

新仓库只托管基于 `kmp-sdk-v1` 的新一代插件。目录中的 `version`、`apiVersion`、`minHostApiVersion`、`runtime` 必须与包内 `manifest.json` 逐项一致；缺省字段按 `1` 处理。旧版本仓库 `CPPlayer-Plugins` 保持网易云 1.1.1，不随新版本升级。

### 推荐目录结构

```text
CPPlayer-Plugins-KMP/
├── .github/
│   └── workflows/
│       └── validate.yml             # 校验目录，并在 push 时核对已发布 ZIP
├── scripts/
│   └── validate_catalog.py
├── LICENSE
├── index.json                       # 核心商店目录清单
└── README.md
```

---

## 3. `index.json` 官方清单示例（含已验证的网易云 v1.2.2）

新仓库根目录的 `index.json` 完整内容：

```json
{
  "schemaVersion": 1,
  "name": "CPPlayer Official Plugins (KMP)",
  "plugins": [
    {
      "id": "netease-cloudmusic-rs",
      "kind": "provider",
      "name": "NeteaseCloudMusicApi-RS",
      "version": "1.2.2",
      "description": "基于 KMP SDK v1 重构的网易云原生音源，支持 161 项业务操作、动态音质与 Native 会话。",
      "author": "Aurora-Nasa-1",
      "homepage": "https://github.com/Aurora-Nasa-1/3rd-CPPlayer-netcloudMusic-Muti",
      "changelog": "v1.2.2: 修复 SDK v1 下每日推荐、私人 FM 与心动模式不可用的问题，补全推荐能力声明并严格限制返回数量。",
      "apiVersion": 1,
      "minHostApiVersion": 1,
      "artifacts": [
        {
          "platform": "windows",
          "arch": "x64",
          "runtime": "jni",
          "url": "https://github.com/Aurora-Nasa-1/3rd-CPPlayer-netcloudMusic-Muti/releases/download/v1.2.2/ncm-api-rs-windows-1.2.2.zip",
          "sha256": "9b3c58b9ea15b52a85eb37f3c63043d0f2db2015b4cf4add5a54802a26a9868e",
          "bytes": 9296667
        },
        {
          "platform": "linux",
          "arch": "x64",
          "runtime": "jni",
          "url": "https://github.com/Aurora-Nasa-1/3rd-CPPlayer-netcloudMusic-Muti/releases/download/v1.2.2/ncm-api-rs-linux-1.2.2.zip",
          "sha256": "080595b6cd20654ed3ccd49116421ddf2f7a54ada92b2e568037ca02399ea00c",
          "bytes": 10040572
        },
        {
          "platform": "android",
          "arch": "arm64",
          "runtime": "jni",
          "url": "https://github.com/Aurora-Nasa-1/3rd-CPPlayer-netcloudMusic-Muti/releases/download/v1.2.2/ncm-api-rs-android-1.2.2.zip",
          "sha256": "02663e4700253f956fffa1a8a391c83faff5da29469b0e86126e08ea66215139",
          "bytes": 16771121
        },
        {
          "platform": "android",
          "arch": "armv7",
          "runtime": "jni",
          "url": "https://github.com/Aurora-Nasa-1/3rd-CPPlayer-netcloudMusic-Muti/releases/download/v1.2.2/ncm-api-rs-android-1.2.2.zip",
          "sha256": "02663e4700253f956fffa1a8a391c83faff5da29469b0e86126e08ea66215139",
          "bytes": 16771121
        },
        {
          "platform": "android",
          "arch": "x64",
          "runtime": "jni",
          "url": "https://github.com/Aurora-Nasa-1/3rd-CPPlayer-netcloudMusic-Muti/releases/download/v1.2.2/ncm-api-rs-android-1.2.2.zip",
          "sha256": "02663e4700253f956fffa1a8a391c83faff5da29469b0e86126e08ea66215139",
          "bytes": 16771121
        }
      ]
    }
  ]
}
```

### 关键字段说明
- `runtime`：JS 插件用 `"js"` / `"script"`；原生插件用 `"jni"` / `"native"`，且 `type` 必须与之一致。
- `platform` 与 `arch`：JS 插件可设为 `"any"`；原生插件必须写明确切平台与架构。
- `sha256` 与 `bytes`：对应 ZIP 压缩包的实际外部校验值。
- `minHostApiVersion`：必须与包内 manifest 一致；未声明时按 `1` 填写，不能凭宿主上限补成 `3`。

---

## 4. 插件 ZIP 结构要求

每个插件 ZIP 包的根目录必须包含：
- `manifest.json`：声明 `contract: "kmp-sdk-v1"`、`type` 以及白名单域名 `sdkOrigins`。
- JS 包：`index.js`（或自定义相对路径 `entryPoint`），导出的 JS 业务实现。
- 原生包：`entryPoint` 指向的 DLL / SO，位于根目录或 `lib/<abi>/`。

```text
netease-1.0.0.zip
├── manifest.json
└── index.js
```

---

## 5. 打包与发布脚本工具建议

在插件仓库中，可通过轻量的 Node.js 脚本一键计算哈希与打包：

```javascript
// scripts/pack.mjs
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import archiver from 'archiver';

// 1. 读取包内 manifest.json 校验 contract == 'kmp-sdk-v1'
// 2. 打包生成 dist/<id>-<version>.zip
// 3. 计算 sha256 与文件大小 bytes
// 4. 更新或输出 index.json 条目
```
