import {UI_CARDS, type UiCard} from './ui.generated';

export type CardLike = UiCard & {placeholder?: boolean};

// 没放截图时用占位牌，保证版式与节奏可以先看
const PLACEHOLDERS: CardLike[] = [
  {file: '', name: '播放页', w: 1080, h: 2340, kind: 'portrait', placeholder: true},
  {file: '', name: '歌词', w: 1080, h: 2340, kind: 'portrait', placeholder: true},
  {file: '', name: '首页', w: 1080, h: 2340, kind: 'portrait', placeholder: true},
  {file: '', name: '歌单', w: 1080, h: 2340, kind: 'portrait', placeholder: true},
  {file: '', name: '桌面 · 曲库', w: 2560, h: 1440, kind: 'landscape', placeholder: true},
  {file: '', name: '桌面 · 音源管理', w: 2560, h: 1440, kind: 'landscape', placeholder: true},
  {file: '', name: '桌面 · 播放', w: 2560, h: 1440, kind: 'landscape', placeholder: true},
];

export const CARDS: CardLike[] = UI_CARDS.length > 0 ? UI_CARDS : PLACEHOLDERS;
export const PORTRAITS = CARDS.filter((c) => c.kind === 'portrait');
export const LANDSCAPES = CARDS.filter((c) => c.kind === 'landscape');

export const lerp = (a: number, b: number, p: number) => a + (b - a) * p;

// 扇形排布：以中心为圆心，越靠两边越下沉、越外旋
export const fan = (
  n: number,
  centerX: number,
  centerY: number,
  spacing: number,
  tilt: number,
  lift = 7,
) => {
  const mid = (n - 1) / 2;
  return Array.from({length: n}, (_, i) => {
    const d = i - mid;
    return {
      x: centerX + d * spacing,
      y: centerY + Math.abs(d) * lift,
      rot: d * tilt,
    };
  });
};
