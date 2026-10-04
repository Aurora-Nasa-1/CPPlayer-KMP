import React from 'react';
import {C, MONO} from '../theme';

/** macOS 风格终端窗口 */
export const Terminal: React.FC<{
  readonly title?: string;
  readonly style?: React.CSSProperties;
  readonly bodyStyle?: React.CSSProperties;
  readonly children: React.ReactNode;
}> = ({title = 'zsh — cpplayer', style, bodyStyle, children}) => (
  <div
    style={{
      borderRadius: 18,
      border: `1px solid ${C.border}`,
      background: 'rgba(8,12,24,0.9)',
      boxShadow: '0 34px 90px rgba(0,0,0,0.55)',
      overflow: 'hidden',
      ...style,
    }}
  >
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        padding: '16px 22px',
        borderBottom: `1px solid ${C.border}`,
        background: 'rgba(255,255,255,0.045)',
      }}
    >
      <span style={{width: 16, height: 16, borderRadius: 999, background: '#FF5F57'}} />
      <span style={{width: 16, height: 16, borderRadius: 999, background: '#FEBC2E'}} />
      <span style={{width: 16, height: 16, borderRadius: 999, background: '#28C840'}} />
      <span style={{fontFamily: MONO, fontSize: 21, color: C.faint, marginLeft: 12}}>
        {title}
      </span>
    </div>
    <div
      style={{
        padding: '28px 32px',
        fontFamily: MONO,
        fontSize: 30,
        lineHeight: 1.72,
        color: C.text,
        ...bodyStyle,
      }}
    >
      {children}
    </div>
  </div>
);
