# Windows Native Image PGO

`default.iprof` 已入库（约 29.5 MB）：只要这个文件存在，生产构建（CI 与本地）
就会自动给 native-image 传 `--pgo=<该文件>`，并保持 `-O3`
（接线在 `app/build.gradle.kts` 的 `graalvm.pgo`，实现由 Nucleus 插件负责）。

## 当前 profile 的来历

| 项 | 值 |
| --- | --- |
| 采集时间 | 2026-11-14 |
| 采集载体 | `desktop-pgo-instrument.yml` 产出的插桩版 app image（`--pgo-instrument`） |
| 采集场景 | 启动 → 曲库浏览/滚动 → 搜索 → 播放 → 动态歌词 → 暂停/seek → 托盘最小化 → 正常退出 |
| 规模 | 43,195 types / 324,729 methods；39,227 条 call-count、6,484 条虚调用、805 条采样 |
| 字节数 | 30,938,646 |
| SHA-256 | `6ae1a5ec05741405a45d283f30f8c6e7f2a9bb4f3c0056c6f55539b03e1f2fac` |

profile 里 `cp.player` 出现 9,061 次、`androidx.compose` 6,801 次、`dev.nucleusframework`
1,677 次 —— 训练确实覆盖到了应用自身代码与 Compose 渲染，不是只跑了个启动。

## 重新采集（业务/场景大改后）

### 云端（推荐）
触发 `.github/workflows/desktop-pgo-instrument.yml`，拿到
`CPPlayer-win-x64-pgo-instrument.zip` 后解压，运行 `CPPlayer.exe` 走代表性场景，
**正常退出**（异常退出不会落盘 profile），同目录即出现 `default.iprof`。

### 本地
```powershell
./gradlew :app:runWithPgoInstrument
```

插桩期有 21.8 万个可达方法，Oracle GraalVM 在 `--pgo-instrument` 下强制以 level 2
编译，所以配置把并发编译线程压到 1（`-H:NumberOfThreads=1`）、堆放到 `-J-Xmx12g`、
并关掉看门狗超时 —— 目的是避免 IR 图膨胀把编译器 OOM 掉，不是为了提速。

采完把新的 `default.iprof` 覆盖到本目录并提交。

## 生效判据（别只看 BUILD SUCCESSFUL）

1. `app/build/compose/tmp/main/graalvm/nativeCompile/native-image-args.txt` 里应有
   `"--pgo=<绝对路径>"`，且**没有** `--pgo-instrument`（插桩版不能发版）。
2. 构建日志里有一行 `PGO: applying recorded profile ...`。
3. 若日志出现 `PGO: recorded profile ... ignored`，说明工具链不是 Oracle GraalVM，
   或 `-Pnucleus.graalvm.pgo=off` 把它关了。

`desktop-release.yml` 已经把 1、2 做成硬断言（Windows job），profile 消失或缺参数
会直接把发行 job 弄红，而不是悄悄退化成「没有 PGO 的发行版」。

## 注意

- **GraalVM 版本必须与采集时一致**（Oracle GraalVM 25.3.4.1，工具链写 `25i3`）。
  版本不符时 native-image 会直接报 profile 不兼容。
- profile 只对**同一次插桩构建的可达方法集合**有意义。业务代码大改后旧 profile
  会退化：找不到对应方法的条目只告警、不报错，等于悄悄少优化 —— 所以大版本发布前
  建议重新采集一轮。
- 无 profile 时自动回落到 Oracle 的 ML Profile Inference；应用 profile 后
  Native Image 会自动停用 ML 推断。
- GraalVM 25.3.4.1 对符合条件的数组算术循环默认开循环向量化。
