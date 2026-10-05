import type React from 'react';

/**
 * CPPlayer 品牌与 Material 3 Expressive 设计令牌。
 *
 * ⚠️ 色值**直接抄自应用源码**
 *   app/src/commonMain/kotlin/cp/player/app/ui/theme/Color.kt
 * （M3E 静态回退色板），不是凭观感调的 —— 宣传片必须与真机同色，
 * 否则"看着像另一个 App"。旧版宣传片用的 #4A6CF7/#8B5CF6 属于通用模板色，
 * 与本项目无关，已废弃。
 *
 * 品牌渐变取自 public/cpplayer-icon.png 的逐点采样：
 *   TL #766DED → #5A5FC0 → BL #416BA5 → BR #28326B
 */

export const M3 = {
  /** 深色（宣传片主用；与 App 深色主题一致） */
  d: {
    surface: '#131318',
    surfaceLowest: '#0E0E13',
    surfaceLow: '#1B1B21',
    surfaceContainer: '#1F1F25',
    surfaceHigh: '#2A2A30',
    surfaceHighest: '#35343B',
    onSurface: '#E5E1E9',
    onSurfaceVariant: '#C9C5D0',
    outline: '#928F99',
    outlineVariant: '#47464F',

    primary: '#C1C1FF',
    onPrimary: '#20216F',
    primaryContainer: '#383B8C',
    onPrimaryContainer: '#E1E0FF',

    secondary: '#80D5D2',
    onSecondary: '#003736',
    secondaryContainer: '#00504E',
    onSecondaryContainer: '#9CF1EE',

    tertiary: '#FFB59D',
    onTertiary: '#5C1905',
    tertiaryContainer: '#7B2E15',
    onTertiaryContainer: '#FFDBCF',

    error: '#F2B8B5',
    onError: '#601410',
    errorContainer: '#8C1D18',
    onErrorContainer: '#F9DEDC',
  },
  /** 浅色（备用；结尾卡/对比镜头偶尔用） */
  l: {
    surface: '#FCF8FC',
    surfaceLowest: '#FFFFFF',
    surfaceLow: '#F7F2FA',
    surfaceContainer: '#F1ECF4',
    surfaceHigh: '#EBE6EF',
    surfaceHighest: '#E5E1EC',
    onSurface: '#1C1B20',
    onSurfaceVariant: '#47464F',
    outline: '#787680',
    outlineVariant: '#C9C5D0',

    primary: '#4F55A5',
    onPrimary: '#FFFFFF',
    primaryContainer: '#E1E0FF',
    onPrimaryContainer: '#15175C',

    secondary: '#006A68',
    onSecondary: '#FFFFFF',
    secondaryContainer: '#9CF1EE',
    onSecondaryContainer: '#00201F',

    tertiary: '#9A4529',
    onTertiary: '#FFFFFF',
    tertiaryContainer: '#FFDBCF',
    onTertiaryContainer: '#3A0B00',
  },
} as const;

/** 旧版场景的别名，保留以免整仓改名时漏掉调用点 */
export const C = M3.d;

export const BRAND = {
  /** 图标实测渐变（135°，左上 → 右下） */
  gradient: 'linear-gradient(135deg,#766DED 0%,#5A5FC0 40%,#416BA5 70%,#28326B 100%)',
  /** 只取上半段，用于文字/描边（深色背景上更亮） */
  textGradient: 'linear-gradient(115deg,#B9B4FF 0%,#8E8BEE 42%,#7FD3E8 100%)',
  /** 光晕用（低透明度铺底） */
  glow: '#766DED',
  glowAlt: '#4FA8C8',
};

export const gradText: React.CSSProperties = {
  backgroundImage: BRAND.textGradient,
  WebkitBackgroundClip: 'text',
  backgroundClip: 'text',
  color: 'transparent',
};

/** 中文优先字体栈。Windows 上 微软雅黑 一定在，Mac 上落到苹方。 */
export const FONT =
  "'Microsoft YaHei UI','Microsoft YaHei','PingFang SC','Noto Sans SC','Source Han Sans SC','Segoe UI',system-ui,sans-serif";

/**
 * 标题用。Noto Sans SC 是可变字重（本机已装 NotoSansSC-VF.ttf，最高 900），
 * 微软雅黑只有 Regular/Bold 两档 —— 大标题要 800/900 的字重就只能靠它。
 */
export const DISPLAY =
  "'Noto Sans SC','Microsoft YaHei UI','Microsoft YaHei','PingFang SC','Segoe UI',system-ui,sans-serif";

/**
 * M3E 圆角阶梯（对齐 Compose 的 ShapeTokens 1.11 具名参数顺序）。
 * extraLarge 在 largeIncreased 之前 —— 这里按语义命名，不按位置。
 */
export const R = {
  xs: 8,
  s: 12,
  m: 16,
  l: 20,
  xl: 28,
  xlIncreased: 32,
  xxl: 48,
} as const;

/** 应用内列表行的分段圆角：外 20 / 内 4（同 legacySegmentShape） */
export const SEG = { outer: 20, inner: 4 } as const;

/** 给同一组列表行的第 i 行（共 n 行）算出四角圆角 —— 直接对应 legacySegmentShape */
export const segRadius = (i: number, n: number): string => {
  const o = SEG.outer;
  const n2 = SEG.inner;
  const top = i === 0 ? o : n2;
  const bottom = i === n - 1 ? o : n2;
  return `${top}px ${top}px ${bottom}px ${bottom}px`;
};

/** 容器色阶：0=最低 … 4=最高（M3 层级体系，深色下逐级提亮） */
export const surfaceLevel = (n: number): string =>
  [M3.d.surfaceLowest, M3.d.surfaceLow, M3.d.surfaceContainer, M3.d.surfaceHigh, M3.d.surfaceHighest][
    Math.max(0, Math.min(4, n))
  ];

export const rgba = (hex: string, a: number): string => {
  const h = hex.replace('#', '');
  const r = parseInt(h.slice(0, 2), 16);
  const g = parseInt(h.slice(2, 4), 16);
  const b = parseInt(h.slice(4, 6), 16);
  return `rgba(${r},${g},${b},${a})`;
};
