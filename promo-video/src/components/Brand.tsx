import React from 'react';
import {spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {C, FONT} from '../theme';

// 品牌标记：圆角方块内六根律动音量条（呼应「播放器」本体）。
export const LogoMark: React.FC<{size?: number; animate?: boolean; delay?: number}> = ({
  size = 140,
  animate = true,
  delay = 0,
}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const p = spring({frame: frame - delay, fps, config: {damping: 14, mass: 0.8, stiffness: 110}});

  const bars = [0.44, 0.74, 1, 0.62, 0.86, 0.4];
  const barW = size * 0.082;
  const gap = size * 0.052;

  return (
    <div
      style={{
        width: size,
        height: size,
        borderRadius: size * 0.3,
        background: `linear-gradient(145deg, ${C.primary} 0%, #6E63C4 46%, ${C.secondary} 100%)`,
        boxShadow: `0 ${size * 0.14}px ${size * 0.34}px rgba(79,85,165,0.30)`,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap,
        transform: `scale(${0.72 + 0.28 * p}) rotate(${(1 - p) * -8}deg)`,
        opacity: Math.min(1, p * 1.6),
      }}
    >
      {bars.map((base, i) => {
        const wobble = animate
          ? Math.sin(frame * 0.085 + i * 0.9) * 0.14 + Math.sin(frame * 0.21 + i) * 0.05
          : 0;
        const h = Math.max(0.16, Math.min(1, base + wobble)) * size * 0.44;
        return (
          <div
            key={i}
            style={{
              width: barW,
              height: h,
              borderRadius: barW,
              background: 'rgba(255,255,255,0.96)',
            }}
          />
        );
      })}
    </div>
  );
};

export const Wordmark: React.FC<{size?: number; color?: string; style?: React.CSSProperties}> = ({
  size = 112,
  color = C.ink,
  style,
}) => (
  <div
    style={{
      fontFamily: FONT,
      fontSize: size,
      fontWeight: 800,
      letterSpacing: -size * 0.028,
      color,
      lineHeight: 1,
      ...style,
    }}
  >
    CPPlayer
  </div>
);

// 向外扩散的声波圆环
export const PulseRings: React.FC<{
  size?: number;
  count?: number;
  color?: string;
  period?: number;
}> = ({size = 320, count = 3, color = C.primary, period = 66}) => {
  const frame = useCurrentFrame();
  return (
    <div
      style={{
        position: 'absolute',
        width: size,
        height: size,
        left: '50%',
        top: '50%',
        marginLeft: -size / 2,
        marginTop: -size / 2,
        pointerEvents: 'none',
      }}
    >
      {Array.from({length: count}).map((_, i) => {
        const t = ((frame + (i * period) / count) % period) / period;
        const scale = 0.9 + t * 1.15;
        return (
          <div
            key={i}
            style={{
              position: 'absolute',
              inset: 0,
              borderRadius: '50%',
              border: `2px solid ${color}`,
              opacity: (1 - t) * 0.26,
              transform: `scale(${scale})`,
            }}
          />
        );
      })}
    </div>
  );
};
