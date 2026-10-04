import React from 'react';
import {Img, staticFile} from 'remotion';

/** CPPlayer 应用图标（public/cpplayer-icon.png），带光晕 */
export const LogoMark: React.FC<{
  readonly size?: number;
  readonly glow?: boolean;
  readonly style?: React.CSSProperties;
}> = ({size = 160, glow = true, style}) => (
  <div style={{position: 'relative', width: size, height: size, flexShrink: 0, ...style}}>
    {glow ? (
      <div
        style={{
          position: 'absolute',
          inset: -size * 0.28,
          borderRadius: '50%',
          background:
            'radial-gradient(circle, rgba(96,120,255,0.5), transparent 65%)',
          filter: 'blur(26px)',
        }}
      />
    ) : null}
    <Img
      src={staticFile('cpplayer-icon.png')}
      style={{
        position: 'relative',
        width: size,
        height: size,
        borderRadius: size * 0.22,
        boxShadow: '0 24px 60px rgba(0,0,0,0.5)',
      }}
    />
  </div>
);
