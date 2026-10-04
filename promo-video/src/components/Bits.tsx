import React from 'react';
import {useCurrentFrame} from 'remotion';
import {C, FONT, gradText} from '../theme';
import {enter} from '../lib/anim';

/** 大标题（自带入场动画） */
export const Headline: React.FC<{
  readonly children: React.ReactNode;
  readonly size: number;
  readonly start?: number;
  readonly align?: 'left' | 'center';
  readonly style?: React.CSSProperties;
}> = ({children, size, start = 0, align = 'left', style}) => {
  const frame = useCurrentFrame();
  const p = enter(frame, start);
  return (
    <h1
      style={{
        margin: 0,
        fontFamily: FONT,
        fontWeight: 700,
        fontSize: size,
        lineHeight: 1.18,
        color: C.text,
        textAlign: align,
        letterSpacing: '0.01em',
        opacity: p,
        translate: `0px ${(1 - p) * 34}px`,
        ...style,
      }}
    >
      {children}
    </h1>
  );
};

/** 副标题 */
export const Sub: React.FC<{
  readonly children: React.ReactNode;
  readonly size: number;
  readonly start?: number;
  readonly align?: 'left' | 'center';
  readonly style?: React.CSSProperties;
}> = ({children, size, start = 10, align = 'left', style}) => {
  const frame = useCurrentFrame();
  const p = enter(frame, start);
  return (
    <div
      style={{
        fontFamily: FONT,
        fontSize: size,
        color: C.sub,
        textAlign: align,
        opacity: p,
        translate: `0px ${(1 - p) * 22}px`,
        ...style,
      }}
    >
      {children}
    </div>
  );
};

/** 渐变强调字 */
export const Grad: React.FC<{readonly children: React.ReactNode}> = ({
  children,
}) => <span style={{...gradText}}>{children}</span>;

/** 圆点要点 */
export const Bullet: React.FC<{
  readonly children: React.ReactNode;
  readonly start?: number;
  readonly accent?: string;
  readonly size?: number;
  readonly style?: React.CSSProperties;
}> = ({children, start = 0, accent = C.blue, size = 40, style}) => {
  const frame = useCurrentFrame();
  const p = enter(frame, start);
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 18,
        fontFamily: FONT,
        fontSize: size,
        color: C.text,
        opacity: p,
        translate: `0px ${(1 - p) * 18}px`,
        ...style,
      }}
    >
      <span
        style={{
          width: 14,
          height: 14,
          borderRadius: 999,
          background: accent,
          boxShadow: `0 0 18px ${accent}`,
          flexShrink: 0,
        }}
      />
      <span>{children}</span>
    </div>
  );
};

/** 胶囊徽章 */
export const Chip: React.FC<{
  readonly children: React.ReactNode;
  readonly accent?: string;
  readonly size?: number;
  readonly style?: React.CSSProperties;
}> = ({children, accent = 'rgba(122,146,255,0.16)', size = 34, style}) => (
  <div
    style={{
      display: 'inline-flex',
      alignItems: 'center',
      gap: 12,
      padding: '14px 30px',
      borderRadius: 999,
      border: `1px solid ${C.border}`,
      background: accent,
      fontFamily: FONT,
      fontSize: size,
      color: C.text,
      whiteSpace: 'nowrap',
      ...style,
    }}
  >
    {children}
  </div>
);

/** 半透明面板容器 */
export const Panel: React.FC<{
  readonly children: React.ReactNode;
  readonly style?: React.CSSProperties;
}> = ({children, style}) => (
  <div
    style={{
      borderRadius: 20,
      border: `1px solid ${C.border}`,
      background: C.panel,
      boxShadow: '0 24px 60px rgba(0,0,0,0.35)',
      ...style,
    }}
  >
    {children}
  </div>
);
