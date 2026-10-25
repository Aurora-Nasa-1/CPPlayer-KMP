import React from 'react';
import {AbsoluteFill, useCurrentFrame} from 'remotion';
import {C, FONT} from '../theme';

// 背景：极浅的品牌色氛围光 + 细点阵网格。
// 全部用 radial-gradient 绘制（不做 CSS blur），在 headless 里渲染更稳。
export const Backdrop: React.FC<{accent?: string}> = ({accent = C.primary}) => {
  const frame = useCurrentFrame();
  const t = frame / 30;

  const drift = (ax: number, ay: number, sp: number) => ({
    x: Math.sin(t * sp + ax) * 46,
    y: Math.cos(t * sp * 0.8 + ay) * 34,
  });

  const a = drift(0, 1.2, 0.35);
  const b = drift(2.1, 0.4, 0.28);

  return (
    <AbsoluteFill style={{backgroundColor: C.bg, fontFamily: FONT, overflow: 'hidden'}}>
      <AbsoluteFill
        style={{
          backgroundImage: `radial-gradient(760px 620px at ${22 + a.x / 20}% ${18 + a.y / 20}%, ${
            accent === C.secondary ? C.secondaryContainer : C.primaryContainer
          } 0%, rgba(252,248,252,0) 64%)`,
          opacity: 0.85,
          transform: `translate(${a.x}px, ${a.y}px)`,
        }}
      />
      <AbsoluteFill
        style={{
          backgroundImage: `radial-gradient(820px 680px at ${84 + b.x / 20}% ${
            82 + b.y / 20
          }%, ${C.secondaryContainer} 0%, rgba(252,248,252,0) 62%)`,
          opacity: 0.7,
          transform: `translate(${b.x}px, ${b.y}px)`,
        }}
      />
      <AbsoluteFill
        style={{
          backgroundImage: `radial-gradient(600px 520px at 50% 52%, ${C.tertiaryContainer} 0%, rgba(252,248,252,0) 68%)`,
          opacity: 0.45,
        }}
      />
      <AbsoluteFill
        style={{
          backgroundImage: `radial-gradient(circle, rgba(79,85,165,0.13) 1.1px, transparent 1.2px)`,
          backgroundSize: '44px 44px',
          maskImage:
            'radial-gradient(ellipse 78% 72% at 50% 46%, rgba(0,0,0,0.85) 0%, rgba(0,0,0,0.25) 62%, rgba(0,0,0,0) 100%)',
          WebkitMaskImage:
            'radial-gradient(ellipse 78% 72% at 50% 46%, rgba(0,0,0,0.85) 0%, rgba(0,0,0,0.25) 62%, rgba(0,0,0,0) 100%)',
          opacity: 0.55,
        }}
      />
    </AbsoluteFill>
  );
};
