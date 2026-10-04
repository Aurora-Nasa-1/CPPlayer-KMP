import type React from 'react';

// CPPlayer 品牌与设计令牌（取自 app 图标：蓝→紫渐变）
export const C = {
  bgDeep: '#060A16',
  bg: '#0A1020',
  panel: 'rgba(255,255,255,0.05)',
  panelStrong: 'rgba(255,255,255,0.09)',
  border: 'rgba(255,255,255,0.11)',
  text: '#F2F5FC',
  sub: '#9AA5BD',
  faint: '#5B6579',
  blue: '#4A6CF7',
  purple: '#8B5CF6',
  cyan: '#22D3EE',
  green: '#34D399',
  red: '#F87171',
  amber: '#FBBF24',
};

export const FONT =
  "'Microsoft YaHei UI','Microsoft YaHei','PingFang SC','Noto Sans SC','Source Han Sans SC',system-ui,sans-serif";
export const MONO =
  "'Cascadia Mono','Cascadia Code',Consolas,'Courier New',monospace";

export const BRAND_GRADIENT = 'linear-gradient(135deg,#4A6CF7 0%,#8B5CF6 100%)';
export const BRAND_TEXT_GRADIENT =
  'linear-gradient(120deg,#7DA2FF 0%,#B49BFF 100%)';

export const gradText: React.CSSProperties = {
  backgroundImage: BRAND_TEXT_GRADIENT,
  WebkitBackgroundClip: 'text',
  backgroundClip: 'text',
  color: 'transparent',
};
