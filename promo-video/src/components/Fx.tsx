import React from 'react';
import {interpolateColors, useCurrentFrame} from 'remotion';
import {C} from '../theme';

// 频谱条：低频在左、高频在右，用叠加正弦模拟音乐律动（无需外部音频分析）。
export const Spectrum: React.FC<{
  width: number;
  height: number;
  bars?: number;
  color?: string;
  color2?: string;
  seed?: number;
  gap?: number;
  minRatio?: number;
  rounded?: boolean;
}> = ({
  width,
  height,
  bars = 56,
  color = C.primary,
  color2 = C.secondary,
  seed = 0,
  gap = 5,
  minRatio = 0.12,
  rounded = true,
}) => {
  const frame = useCurrentFrame();
  const barW = (width - gap * (bars - 1)) / bars;

  return (
    <div style={{display: 'flex', alignItems: 'flex-end', gap, width, height}}>
      {Array.from({length: bars}).map((_, i) => {
        const f = i / bars;
        // 低频占主导，高频衰减；再叠两层不同速度的呼吸
        const envelope = 0.34 + 0.66 * Math.pow(1 - f, 1.5);
        const s1 = Math.sin(frame * 0.16 + i * 0.55 + seed) * 0.5 + 0.5;
        const s2 = Math.sin(frame * 0.07 + i * 0.21 + seed * 2) * 0.5 + 0.5;
        const s3 = Math.sin(frame * 0.31 + i * 1.7 + seed * 3) * 0.5 + 0.5;
        const amp = minRatio + (1 - minRatio) * envelope * (0.45 * s1 + 0.35 * s2 + 0.2 * s3);
        const h = Math.max(height * 0.04, Math.min(1, amp) * height);
        // 整条频谱横向从主色渐变到辅色（按列插值，不做二分色，避免出现「斑马纹」）
        const tint = interpolateColors(f, [0, 1], [color, color2]);
        return (
          <div
            key={i}
            style={{
              width: barW,
              height: h,
              borderRadius: rounded ? Math.min(barW, 6) : 0,
              background: tint,
              opacity: 0.5 + 0.5 * (1 - f * 0.45),
            }}
          />
        );
      })}
    </div>
  );
};

// 流动虚线：用于表达「数据在两端之间流动」
export const FlowLine: React.FC<{
  width: number;
  height?: number;
  color?: string;
  dash?: number;
  speed?: number;
  thickness?: number;
}> = ({width, height = 2, color = C.primary, dash = 10, speed = 1.6, thickness = 2}) => {
  const frame = useCurrentFrame();
  const offset = -(frame * speed) % (dash * 2.6);
  return (
    <svg width={width} height={height} style={{display: 'block', overflow: 'visible'}}>
      <line
        x1={0}
        y1={height / 2}
        x2={width}
        y2={height / 2}
        stroke={color}
        strokeWidth={thickness}
        strokeLinecap="round"
        strokeDasharray={`${dash} ${dash * 1.6}`}
        strokeDashoffset={offset}
        opacity={0.45}
      />
    </svg>
  );
};

// 沿路径滚动的光点（用 SVG animateMotion 会与 Remotion 帧不同步，这里按帧算坐标）
export const FlowDot: React.FC<{
  from: {x: number; y: number};
  to: {x: number; y: number};
  period?: number;
  delay?: number;
  color?: string;
  size?: number;
}> = ({from, to, period = 52, delay = 0, color = C.primary, size = 12}) => {
  const frame = useCurrentFrame();
  const t = (((frame - delay) % period) + period) % period / period;
  const x = from.x + (to.x - from.x) * t;
  const y = from.y + (to.y - from.y) * t;
  const fade = Math.sin(Math.PI * t);
  return (
    <div
      style={{
        position: 'absolute',
        left: x - size / 2,
        top: y - size / 2,
        width: size,
        height: size,
        borderRadius: '50%',
        background: color,
        opacity: fade * 0.9,
        boxShadow: `0 0 ${size}px ${color}`,
      }}
    />
  );
};
