// CPPlayer 宣传片设计系统
// 色值直接取自应用自身的 Material 3 浅色板（app/.../ui/theme/Color.kt），
// 保证视频观感与应用内界面是同一套语言。
import {FPS as F, TOTAL as T} from './vo.generated';

export const FPS = F;
export const TOTAL = T;
export const WIDTH = 1920;
export const HEIGHT = 1080;

export const C = {
  bg: '#FCF8FC',
  surface: '#FFFFFF',
  surfaceLow: '#F7F2FA',
  surfaceC: '#F1ECF4',
  surfaceHigh: '#EBE6EF',

  primary: '#4F55A5',
  primaryContainer: '#E1E0FF',
  onPrimaryContainer: '#15175C',

  secondary: '#006A68',
  secondaryContainer: '#9CF1EE',
  onSecondaryContainer: '#00201F',

  tertiary: '#9A4529',
  tertiaryContainer: '#FFDBCF',
  onTertiaryContainer: '#3A0B00',

  ink: '#1C1B20',
  inkVar: '#47464F',
  outline: '#C9C5D0',
  outlineStrong: '#787680',
  white: '#FFFFFF',
} as const;

export const FONT =
  '"Microsoft YaHei", "PingFang SC", "Noto Sans SC", "Segoe UI", system-ui, sans-serif';
export const MONO =
  '"Cascadia Mono", "JetBrains Mono", "Consolas", "SF Mono", monospace';

export const SAFE_X = 120;
export const SAFE_TOP = 88;
// 底部要给常驻字幕条留位
export const SAFE_BOTTOM = 172;

export const shadow = {
  card: '0 2px 4px rgba(28,27,32,0.05), 0 14px 34px rgba(28,27,32,0.10)',
  float: '0 6px 12px rgba(28,27,32,0.07), 0 30px 70px rgba(28,27,32,0.16)',
  chip: '0 1px 2px rgba(28,27,32,0.05)',
} as const;

// 手机 / 桌面两种牌面的高宽比
export const CARD_RATIO = {portrait: 2.06, landscape: 0.667} as const;
