import React from 'react';
import {interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {C, FONT, MONO, shadow} from '../theme';

// 入场包装：透明度 + 上浮，弹簧驱动。
export const Reveal: React.FC<{
  delay?: number;
  y?: number;
  x?: number;
  scaleFrom?: number;
  style?: React.CSSProperties;
  children: React.ReactNode;
}> = ({delay = 0, y = 26, x = 0, scaleFrom = 1, style, children}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const p = spring({
    frame: frame - delay,
    fps,
    config: {damping: 200, mass: 0.7, stiffness: 95},
  });
  return (
    <div
      style={{
        opacity: p,
        transform: `translate(${(1 - p) * x}px, ${(1 - p) * y}px) scale(${
          scaleFrom + (1 - scaleFrom) * p
        })`,
        ...style,
      }}
    >
      {children}
    </div>
  );
};

// 逐字上浮：用于大标题，按字符错帧。
export const RevealChars: React.FC<{
  text: string;
  delay?: number;
  stagger?: number;
  style?: React.CSSProperties;
}> = ({text, delay = 0, stagger = 2.4, style}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  return (
    <div style={{display: 'flex', flexWrap: 'wrap', ...style}}>
      {Array.from(text).map((ch, i) => {
        const p = spring({
          frame: frame - delay - i * stagger,
          fps,
          config: {damping: 200, mass: 0.6, stiffness: 120},
        });
        return (
          <span
            key={i}
            style={{
              display: 'inline-block',
              opacity: p,
              transform: `translateY(${(1 - p) * 34}px)`,
              whiteSpace: 'pre',
            }}
          >
            {ch}
          </span>
        );
      })}
    </div>
  );
};

export const Title: React.FC<{
  children: React.ReactNode;
  size?: number;
  color?: string;
  weight?: number;
  style?: React.CSSProperties;
}> = ({children, size = 78, color = C.ink, weight = 700, style}) => (
  <div
    style={{
      fontFamily: FONT,
      fontSize: size,
      lineHeight: 1.22,
      fontWeight: weight,
      letterSpacing: -1.6,
      color,
      ...style,
    }}
  >
    {children}
  </div>
);

export const Sub: React.FC<{
  children: React.ReactNode;
  size?: number;
  color?: string;
  style?: React.CSSProperties;
}> = ({children, size = 34, color = C.inkVar, style}) => (
  <div
    style={{
      fontFamily: FONT,
      fontSize: size,
      lineHeight: 1.55,
      fontWeight: 400,
      color,
      letterSpacing: 0.2,
      ...style,
    }}
  >
    {children}
  </div>
);

export const Mono: React.FC<{
  children: React.ReactNode;
  size?: number;
  color?: string;
  style?: React.CSSProperties;
}> = ({children, size = 22, color = C.inkVar, style}) => (
  <div
    style={{
      fontFamily: MONO,
      fontSize: size,
      lineHeight: 1.5,
      color,
      letterSpacing: 0.3,
      ...style,
    }}
  >
    {children}
  </div>
);

export const Card: React.FC<{
  children: React.ReactNode;
  style?: React.CSSProperties;
  bg?: string;
  radius?: number;
  elevated?: boolean;
  border?: boolean;
}> = ({children, style, bg = C.surface, radius = 30, elevated = false, border = true}) => (
  <div
    style={{
      background: bg,
      borderRadius: radius,
      border: border ? `1px solid ${C.outline}` : 'none',
      boxShadow: elevated ? shadow.float : shadow.card,
      ...style,
    }}
  >
    {children}
  </div>
);

export const Chip: React.FC<{
  children: React.ReactNode;
  color?: string;
  bg?: string;
  dim?: boolean;
  style?: React.CSSProperties;
}> = ({children, color = C.onPrimaryContainer, bg = C.primaryContainer, dim = false, style}) => (
  <div
    style={{
      display: 'inline-flex',
      alignItems: 'center',
      gap: 10,
      padding: '9px 20px',
      borderRadius: 999,
      background: bg,
      color,
      fontFamily: FONT,
      fontSize: 24,
      fontWeight: 600,
      letterSpacing: 0.3,
      opacity: dim ? 0.55 : 1,
      whiteSpace: 'nowrap',
      ...style,
    }}
  >
    {children}
  </div>
);

// 小段单色键盘标记，用于「接口 / 端点」等代码感标签
export const KeyTag: React.FC<{children: React.ReactNode; color?: string}> = ({
  children,
  color = C.primary,
}) => (
  <span
    style={{
      fontFamily: MONO,
      fontSize: 21,
      fontWeight: 700,
      color: C.white,
      background: color,
      borderRadius: 7,
      padding: '3px 10px',
      letterSpacing: 0.4,
    }}
  >
    {children}
  </span>
);

export const useLoopProgress = (period: number) => {
  const frame = useCurrentFrame();
  return (frame % period) / period;
};

export const interpolateClamp = (
  frame: number,
  range: [number, number],
  out: [number, number],
) =>
  interpolate(frame, range, out, {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
