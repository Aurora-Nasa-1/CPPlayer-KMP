import React from 'react';
import {AbsoluteFill, useCurrentFrame} from 'remotion';
import {C} from '../theme';

/** 全屏底：深色底 + 双渐变光斑漂移 + 细网格 + 暗角 */
export const Backdrop: React.FC<{
  readonly tintA?: string;
  readonly tintB?: string;
  readonly grid?: boolean;
}> = ({tintA = 'rgba(74,108,247,0.42)', tintB = 'rgba(139,92,246,0.34)', grid = true}) => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{backgroundColor: C.bgDeep, overflow: 'hidden'}}>
      <div
        style={{
          position: 'absolute',
          width: 1100,
          height: 1100,
          borderRadius: '50%',
          background: `radial-gradient(circle, ${tintA} 0%, transparent 65%)`,
          left: '55%',
          top: '-22%',
          translate: `${Math.sin(frame / 150) * 60}px ${Math.cos(frame / 190) * 40}px`,
          filter: 'blur(70px)',
          opacity: 0.85,
        }}
      />
      <div
        style={{
          position: 'absolute',
          width: 1000,
          height: 1000,
          borderRadius: '50%',
          background: `radial-gradient(circle, ${tintB} 0%, transparent 65%)`,
          left: '-14%',
          top: '42%',
          translate: `${Math.cos(frame / 170) * 50}px ${Math.sin(frame / 130) * 36}px`,
          filter: 'blur(80px)',
          opacity: 0.8,
        }}
      />
      {grid ? (
        <AbsoluteFill
          style={{
            backgroundImage:
              'linear-gradient(rgba(255,255,255,0.05) 1px, transparent 1px),linear-gradient(90deg, rgba(255,255,255,0.05) 1px, transparent 1px)',
            backgroundSize: '76px 76px',
            WebkitMaskImage:
              'radial-gradient(ellipse at 50% 45%, black 18%, transparent 76%)',
            maskImage:
              'radial-gradient(ellipse at 50% 45%, black 18%, transparent 76%)',
          }}
        />
      ) : null}
      <AbsoluteFill
        style={{
          background:
            'radial-gradient(ellipse at center, transparent 52%, rgba(0,0,0,0.55) 100%)',
        }}
      />
    </AbsoluteFill>
  );
};
