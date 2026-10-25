# CPPlayer 宣传片工程（讲解版）

74 秒产品讲解片：**TTS 口播驱动** + **真实界面牌铺开**（UI 像扑克一样摊开）。画面全部由代码生成，
截图由你提供，放进 `public/ui/` 就会自动变成牌面。

- 规格：1920×1080 · 30fps · 74.3s · H.264 / AAC · 中文口播 + 中文字幕
- 成片：`out/cpplayer-promo.mp4`
- 分镜脚本：[`STORYBOARD.md`](STORYBOARD.md)

## 流水线

```
audio/vo-script.json ──► scripts/build-vo.mjs ──► public/vo/*.mp3 + src/vo.generated.ts
                                                        │
public/ui/*.jpg|png ──► scripts/ui-manifest.mjs ──► src/ui.generated.ts
                                                        │
                                              src/scenes/* ──► remotion render
```

**场景时长不是手写的**，而是量出来的：每段口播合成长度 → 加开口延后与换气 → 就是该镜头的帧数。
改一句台词重跑 `build-vo.mjs`，整片时间轴自动重排，字幕也跟着切。

## 目录

```
src/
  theme.ts              色板 / 字体 / 安全边距（配色取自应用 M3 浅色板）
  timeline.ts           场景时间轴（来自 vo.generated.ts）
  vo.generated.ts       ← 脚本生成：每段配音时长与起止帧
  ui.generated.ts       ← 脚本生成：截图尺寸与竖/横分类
  cards.ts              牌面数据与扇形排布（无截图时用占位牌）
  Film.tsx              场景排布 + 口播/垫乐挂载
  components/           Stage 页面骨架、UiCard 界面牌、Subtitle 字幕、Brand、Backdrop
  scenes/S1..S5         hook / deck / spot / provider / outro
audio/vo-script.json    口播稿（改这里）
audio/make-bgm.mjs      垫乐生成器（100 BPM 合成，无版权素材）
scripts/build-vo.mjs    配音 + 时间轴
scripts/ui-manifest.mjs 截图清单
public/ui/              界面截图放这里
```

## 命令

```bash
npm install

# 1) 放截图后刷新清单（没放图也能渲染，会用占位牌）
node scripts/ui-manifest.mjs

# 2) 改了口播稿就重跑配音与时间轴（需要 .venv-tts）
node scripts/build-vo.mjs

# 3) 预览 / 出片
npm run dev
npm run render
```

首次配音需要：

```bash
python -m venv .venv-tts && ./.venv-tts/Scripts/python.exe -m pip install edge-tts
```

## 改什么改哪里

| 想改 | 改这里 | 要不要重跑脚本 |
|---|---|---|
| 台词 / 语速 / 音色 | `audio/vo-script.json` | 要（`build-vo.mjs`） |
| 换截图 | `public/ui/` | 要（`ui-manifest.mjs`） |
| 牌面大小、扇形角度、出现顺序 | `src/scenes/S2Deck.tsx` 顶部常量 | 不要 |
| 配色 | `src/theme.ts` 的 `C`（与应用 `Color.kt` 一一对应） | 不要 |
| 垫乐 | `audio/make-bgm.mjs` | 要（生成 WAV 再转 mp3） |

## 约定

- 字幕常驻底部，字号 33，与口播按字数比例同步；画面底部统一留 172px 给它。
- 界面牌统一「白卡 + 内嵌截图 + 左下角名称胶囊」，竖图进前排、横图进后排。
- 动效统一 `spring`，同组元素错帧 3–10 帧；镜头进出场用 ease-out / ease-in（弹簧前几帧近乎全透明，会显得画面是空的）。
- 全片共用一层背景（`Film.tsx` 里的 `<Backdrop />`），换镜头只换内容，氛围不断。
