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
         └── 包含：kmp-sdk-v1 脚本插件（runtime: js / script）
```

### 客户端无感自动迁移（`PluginRepositoryService`）
新客户端启动时会自动检测本地保存的插件源：
- 若存在旧官方源 URL（`.../CPPlayer-Plugins/main/index.json`），自动无缝迁移为新官方源（`.../CPPlayer-Plugins-KMP/main/index.json`）并持久化回写。
- 用户自行添加的第三方订阅源不受任何影响。
- 新安装用户直接使用新仓库默认源。

---

## 2. 新仓库 `CPPlayer-Plugins-KMP` 组织规范

新仓库只托管基于 `kmp-sdk-v1` 的 JS 插件，不再包含历史 JNI DLL / EXE / SO 二进制，保持仓库轻量。

### 推荐目录结构

```text
CPPlayer-Plugins-KMP/
├── .github/
│   └── workflows/
│       └── release-plugins.yml      # 自动打包校验与更新 index.json
├── packages/
│   ├── netease/                     # 插件 1
│   │   ├── manifest.json
│   │   └── index.js
│   └── qqmusic/                     # 插件 2
│       ├── manifest.json
│       └── index.js
├── dist/                            # 编译/打包输出的 zip 工件（或通过 GitHub Releases 托管）
│   ├── netease-1.0.0.zip
│   └── qqmusic-1.0.0.zip
├── index.json                       # 核心商店目录清单
└── README.md
```

---

## 3. `index.json` 官方清单示例（含已验证的网易云 v1.2.1）

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
      "version": "1.2.1",
      "description": "基于 KMP SDK v1 重构的网易云原生音源，支持 161 项业务操作、动态音质与 Native 会话。",
      "author": "Aurora-Nasa-1",
      "homepage": "https://github.com/Aurora-Nasa-1/3rd-CPPlayer-netcloudMusic-Muti",
      "changelog": "v1.2.1: 基于 kmp-sdk-v1 规范全面重构，支持 161 项业务操作，导出 NativeSession 标准符号与免反射 DLL/SO 直接加载。",
      "apiVersion": 1,
      "minHostApiVersion": 3,
      "artifacts": [
        {
          "platform": "windows",
          "arch": "x64",
          "runtime": "jni",
          "url": "https://github.com/Aurora-Nasa-1/3rd-CPPlayer-netcloudMusic-Muti/releases/download/v1.2.1/ncm-api-rs-windows-1.2.1.zip",
          "sha256": "51a23aa19ba4a2946afddf4b73517f2fab18c807b9ca45b90898c40f85830dc5",
          "bytes": 9805879
        },
        {
          "platform": "linux",
          "arch": "x64",
          "runtime": "jni",
          "url": "https://github.com/Aurora-Nasa-1/3rd-CPPlayer-netcloudMusic-Muti/releases/download/v1.2.1/ncm-api-rs-linux-1.2.1.zip",
          "sha256": "7cfa079d6bfa71991c5fb1a32bb7169df582c8f2837efa0611d0bb2401a4cdd5",
          "bytes": 10624800
        },
        {
          "platform": "android",
          "arch": "arm64",
          "runtime": "jni",
          "url": "https://github.com/Aurora-Nasa-1/3rd-CPPlayer-netcloudMusic-Muti/releases/download/v1.2.1/ncm-api-rs-android-1.2.1.zip",
          "sha256": "536e05b7f18842c377db604bf069b5825de3b45497ca0f3014b46deca33b1761",
          "bytes": 17482660
        },
        {
          "platform": "android",
          "arch": "armv7",
          "runtime": "jni",
          "url": "https://github.com/Aurora-Nasa-1/3rd-CPPlayer-netcloudMusic-Muti/releases/download/v1.2.1/ncm-api-rs-android-1.2.1.zip",
          "sha256": "536e05b7f18842c377db604bf069b5825de3b45497ca0f3014b46deca33b1761",
          "bytes": 17482660
        },
        {
          "platform": "android",
          "arch": "x64",
          "runtime": "jni",
          "url": "https://github.com/Aurora-Nasa-1/3rd-CPPlayer-netcloudMusic-Muti/releases/download/v1.2.1/ncm-api-rs-android-1.2.1.zip",
          "sha256": "536e05b7f18842c377db604bf069b5825de3b45497ca0f3014b46deca33b1761",
          "bytes": 17482660
        }
      ]
    }
  ]
}
```

### 关键字段说明
- `runtime`：必须为 `"js"` 或 `"script"`（新客户端硬性校验要求；旧客户端会主动跳过，互不冲突）。
- `platform` 与 `arch`：统一设为 `"any"`，由客户端统一调度跨平台 JS 运行时。
- `sha256` 与 `bytes`：对应 ZIP 压缩包的实际外部校验值。
- `minHostApiVersion`：通常设为 `3`（对应当前 `HOST_PROVIDER_API_VERSION = 3`）。

---

## 4. 插件 ZIP 结构要求

每个插件 ZIP 包的根目录必须包含：
- `manifest.json`：声明 `contract: "kmp-sdk-v1"`、`type: "js"` 以及白名单域名 `sdkOrigins`。
- `index.js`（或自定义相对路径 `entryPoint`）：导出的 JS 业务实现。

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
