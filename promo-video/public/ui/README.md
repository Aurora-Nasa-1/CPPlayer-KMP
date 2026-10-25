# 截图放这里

把 CPPlayer 的界面截图放进本目录即可，文件名随意（建议按顺序命名，数字越小的牌越靠前）：

```
public/ui/01-播放页.jpg
public/ui/02-歌词.png
public/ui/03-首页.png
public/ui/04-歌单.png
public/ui/05-桌面-曲库.png
public/ui/06-桌面-音源管理.png
```

规则：

- 支持 `.png` / `.jpg` / `.jpeg`，按文件名排序。
- **竖图**（高 > 宽）当手机界面，进前排牌；**横图**（宽 > 高）当桌面界面，进后排牌。
- 建议 6–10 张；至少各来一张手机图和桌面图，第 3 段（特写）会自动取用第一张竖图和第一张横图。
- 放好后运行 `node scripts/ui-manifest.mjs`，再 `npm run render`。

没有放图时，片子会用虚线占位卡渲染，方便先看节奏。清理浏览器缓存不必手动处理，Remotion 每次渲染都会重新打包。
