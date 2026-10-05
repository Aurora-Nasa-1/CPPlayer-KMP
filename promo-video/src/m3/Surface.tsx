import React from 'react';
import {M3, FONT, DISPLAY, rgba, R} from '../theme';
import {MorphShape} from './MorphShape';
import type {Polar} from '../lib/shape';

/** M3E 色调图标容器（圆角方 + 色调底 + 图标） */
export const Tonal: React.FC<{
  readonly size?: number;
  readonly tone?: 'primary' | 'secondary' | 'tertiary' | 'surface' | 'error';
  readonly radius?: number;
  readonly shape?: Polar;
  readonly id?: string;
  readonly style?: React.CSSProperties;
  readonly children: React.ReactNode;
}> = ({size = 56, tone = 'primary', radius = R.m, shape, id = 'tonal', style, children}) => {
  const map = {
    primary: [M3.d.primaryContainer, M3.d.onPrimaryContainer],
    secondary: [M3.d.secondaryContainer, M3.d.onSecondaryContainer],
    tertiary: [M3.d.tertiaryContainer, M3.d.onTertiaryContainer],
    surface: [M3.d.surfaceHighest, M3.d.onSurface],
    error: [M3.d.errorContainer, M3.d.onErrorContainer],
  } as const;
  const [bg, fg] = map[tone];
  const inner = (
    <div
      style={{
        width: size,
        height: size,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        color: fg,
        ...(shape ? {} : {background: bg, borderRadius: radius}),
        ...style,
      }}
    >
      {children}
    </div>
  );
  if (shape) {
    return (
      <div style={{position: 'relative', width: size, height: size, flexShrink: 0}}>
        <MorphShape shape={shape} size={size} id={`${id}-tonal-bg`} fill={bg} />
        <div style={{position: 'absolute', inset: 0}}>{inner}</div>
      </div>
    );
  }
  return <div style={{flexShrink: 0}}>{inner}</div>;
};

/** 药丸标签 */
export const Chip: React.FC<{
  readonly variant?: 'filled' | 'tonal' | 'outlined' | 'text';
  readonly tone?: 'primary' | 'secondary' | 'tertiary' | 'neutral';
  readonly size?: number;
  readonly icon?: React.ReactNode;
  readonly style?: React.CSSProperties;
  readonly children: React.ReactNode;
}> = ({variant = 'tonal', tone = 'neutral', size = 24, icon, style, children}) => {
  const tones = {
    primary: [M3.d.primary, M3.d.onPrimary, M3.d.primaryContainer, M3.d.onPrimaryContainer],
    secondary: [M3.d.secondary, M3.d.onSecondary, M3.d.secondaryContainer, M3.d.onSecondaryContainer],
    tertiary: [M3.d.tertiary, M3.d.onTertiary, M3.d.tertiaryContainer, M3.d.onTertiaryContainer],
    neutral: [M3.d.onSurface, M3.d.surface, M3.d.surfaceHighest, M3.d.onSurface],
  } as const;
  const [fillBg, fillFg, tonalBg, tonalFg] = tones[tone];
  const bg = variant === 'filled' ? fillBg : variant === 'tonal' ? tonalBg : 'transparent';
  const fg = variant === 'filled' ? fillFg : variant === 'tonal' ? tonalFg : M3.d.onSurfaceVariant;
  return (
    <div
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: size * 0.34,
        height: size * 1.85,
        padding: `0 ${size * 0.85}px`,
        borderRadius: 999,
        background: bg,
        border: variant === 'outlined' ? `1.5px solid ${M3.d.outlineVariant}` : 'none',
        color: fg,
        fontFamily: FONT,
        fontSize: size,
        fontWeight: 600,
        whiteSpace: 'nowrap',
        boxSizing: 'border-box',
        ...style,
      }}
    >
      {icon}
      {children}
    </div>
  );
};

/** M3E 按钮（filled / tonal / outlined） */
export const Button: React.FC<{
  readonly variant?: 'filled' | 'tonal' | 'outlined';
  readonly tone?: 'primary' | 'secondary' | 'tertiary';
  readonly size?: number;
  readonly icon?: React.ReactNode;
  readonly style?: React.CSSProperties;
  readonly children: React.ReactNode;
}> = ({variant = 'filled', tone = 'primary', size = 26, icon, style, children}) => {
  const tones = {
    primary: [M3.d.primary, M3.d.onPrimary, M3.d.primaryContainer, M3.d.onPrimaryContainer],
    secondary: [M3.d.secondary, M3.d.onSecondary, M3.d.secondaryContainer, M3.d.onSecondaryContainer],
    tertiary: [M3.d.tertiary, M3.d.onTertiary, M3.d.tertiaryContainer, M3.d.onTertiaryContainer],
  } as const;
  const [fillBg, fillFg, tonalBg, tonalFg] = tones[tone];
  return (
    <div
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: size * 0.42,
        height: size * 2.35,
        padding: `0 ${size * 1.25}px`,
        borderRadius: 999,
        background: variant === 'filled' ? fillBg : variant === 'tonal' ? tonalBg : 'transparent',
        color: variant === 'filled' ? fillFg : variant === 'tonal' ? tonalFg : M3.d.primary,
        border: variant === 'outlined' ? `1.5px solid ${M3.d.outline}` : 'none',
        fontFamily: FONT,
        fontSize: size,
        fontWeight: 700,
        boxSizing: 'border-box',
        ...style,
      }}
    >
      {icon}
      {children}
    </div>
  );
};

/** 状态点（健康指示） */
export const StatusDot: React.FC<{
  readonly tone?: 'ok' | 'warn' | 'bad';
  readonly size?: number;
  readonly style?: React.CSSProperties;
}> = ({tone = 'ok', size = 14, style}) => {
  const color = tone === 'ok' ? M3.d.secondary : tone === 'warn' ? M3.d.tertiary : M3.d.error;
  return (
    <span
      style={{
        width: size,
        height: size,
        borderRadius: 999,
        background: color,
        boxShadow: `0 0 ${size * 1.2}px ${rgba(color, 0.9)}`,
        display: 'inline-block',
        flexShrink: 0,
        ...style,
      }}
    />
  );
};

/** 大标题（M3E Display）：字重高、字距紧 */
export const Head: React.FC<{
  readonly size?: number;
  readonly weight?: number;
  readonly color?: string;
  readonly style?: React.CSSProperties;
  readonly children: React.ReactNode;
}> = ({size = 84, weight = 800, color = M3.d.onSurface, style, children}) => (
  <div
    style={{
      fontFamily: DISPLAY,
      fontSize: size,
      fontWeight: weight,
      color,
      lineHeight: 1.1,
      letterSpacing: -size * 0.02,
      ...style,
    }}
  >
    {children}
  </div>
);

/** 副标题 */
export const Sub: React.FC<{
  readonly size?: number;
  readonly color?: string;
  readonly weight?: number;
  readonly style?: React.CSSProperties;
  readonly children: React.ReactNode;
}> = ({size = 32, color = M3.d.onSurfaceVariant, weight = 500, style, children}) => (
  <div
    style={{
      fontFamily: FONT,
      fontSize: size,
      fontWeight: weight,
      color,
      lineHeight: 1.45,
      letterSpacing: 0.3,
      ...style,
    }}
  >
    {children}
  </div>
);
