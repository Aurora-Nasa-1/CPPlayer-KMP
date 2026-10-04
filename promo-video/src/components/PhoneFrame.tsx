import React from 'react';
import {C, FONT} from '../theme';

/** 手机外壳（圆角边框 + 挖孔），children 为屏幕内容 */
export const PhoneFrame: React.FC<{
  readonly width?: number;
  readonly style?: React.CSSProperties;
  readonly children: React.ReactNode;
}> = ({width = 620, style, children}) => {
  const height = width * 2.05;
  const bezel = Math.max(9, width * 0.022);
  return (
    <div
      style={{
        width,
        height,
        borderRadius: width * 0.155,
        border: `${bezel}px solid #232B40`,
        background: '#0B0F1C',
        boxShadow:
          '0 46px 100px rgba(0,0,0,0.6), inset 0 0 0 2px rgba(255,255,255,0.05)',
        overflow: 'hidden',
        position: 'relative',
        flexShrink: 0,
        ...style,
      }}
    >
      <div
        style={{
          position: 'absolute',
          top: width * 0.045,
          left: '50%',
          translate: '-50% 0px',
          width: width * 0.3,
          height: width * 0.072,
          borderRadius: 999,
          background: '#04060C',
          zIndex: 5,
        }}
      />
      {children}
    </div>
  );
};

/** 竖屏小标题（手机内容内部用） */
export const ScreenLabel: React.FC<{
  readonly children: React.ReactNode;
  readonly size?: number;
  readonly color?: string;
  readonly style?: React.CSSProperties;
}> = ({children, size = 30, color = C.text, style}) => (
  <div style={{fontFamily: FONT, fontSize: size, color, fontWeight: 600, ...style}}>
    {children}
  </div>
);
