// 扫描 public/ui/ 下的界面截图，读出像素尺寸，生成 src/ui.generated.ts。
// 用法：node scripts/ui-manifest.mjs
import fs from 'node:fs';
import path from 'node:path';

const UI_DIR = path.resolve('public/ui');
const EXT = new Set(['.png', '.jpg', '.jpeg']);

// 读 PNG / JPEG 的宽高（不引依赖，只解必要的头部）
function dimensions(file) {
  const buf = fs.readFileSync(file);
  if (buf.length > 24 && buf.toString('ascii', 1, 4) === 'PNG') {
    return {w: buf.readUInt32BE(16), h: buf.readUInt32BE(20)};
  }
  if (buf[0] === 0xff && buf[1] === 0xd8) {
    let i = 2;
    while (i < buf.length - 9) {
      if (buf[i] !== 0xff) {
        i++;
        continue;
      }
      const marker = buf[i + 1];
      const len = buf.readUInt16BE(i + 2);
      // SOF0..SOF15（跳过 DHT/JPG/DAC 这些非 SOF 段）
      if (marker >= 0xc0 && marker <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(marker)) {
        return {h: buf.readUInt16BE(i + 5), w: buf.readUInt16BE(i + 7)};
      }
      i += 2 + len;
    }
  }
  return null;
}

const files = fs.existsSync(UI_DIR)
  ? fs
      .readdirSync(UI_DIR)
      .filter((f) => EXT.has(path.extname(f).toLowerCase()))
      .sort((a, b) => a.localeCompare(b, 'zh-CN', {numeric: true}))
  : [];

const cards = files.map((f) => {
  const dim = dimensions(path.join(UI_DIR, f));
  if (!dim) throw new Error(`读不出尺寸：${f}（请存成标准 PNG / JPEG）`);
  const ratio = dim.w / dim.h;
  return {
    file: `ui/${f}`,
    name: path.basename(f, path.extname(f)).replace(/^\d+[-_]?/, ''),
    w: dim.w,
    h: dim.h,
    kind: ratio > 1.15 ? 'landscape' : 'portrait',
  };
});

const ts = `// 由 scripts/ui-manifest.mjs 生成，请勿手改（换图后重跑脚本）
export type UiCard = {file: string; name: string; w: number; h: number; kind: 'portrait' | 'landscape'};

export const UI_CARDS: UiCard[] = ${JSON.stringify(cards, null, 2)};

export const HAS_UI = UI_CARDS.length > 0;
export const PORTRAITS = UI_CARDS.filter((c) => c.kind === 'portrait');
export const LANDSCAPES = UI_CARDS.filter((c) => c.kind === 'landscape');
`;

fs.writeFileSync(path.resolve('src/ui.generated.ts'), ts);

if (cards.length === 0) {
  console.log('public/ui/ 里还没有截图 —— 会先渲染占位卡。');
} else {
  console.log(`已收录 ${cards.length} 张：`);
  for (const c of cards) console.log(`  ${c.kind.padEnd(9)} ${c.w}x${c.h}  ${c.file}`);
}
