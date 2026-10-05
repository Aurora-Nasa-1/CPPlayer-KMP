import React from 'react';
import {wavePath} from '../lib/shape';
import {M3, rgba} from '../theme';

/**
 * M3E 波形进度条（LinearWavyProgressIndicator 的观感）。
 *
 * 实现要点：波形是 x 的单值函数 ⇒ 「已完成」段只要**重新生成一条到 progress*width
 * 为止的路径**即可，不需要 clipPath，圆头端点自然落在断点上，正是 M3E 的样子。
 */
export const WavyProgress: React.FC<{
  readonly progress: number;
  readonly width: number;
  readonly height?: number;
  readonly phase?: number;
  readonly track?: string;
  readonly active?: string;
  readonly thickness?: number;
  readonly period?: number;
  readonly style?: React.CSSProperties;
}> = ({
  progress,
  width,
  height = 16,
  phase = 0,
  track = M3.d.surfaceHighest,
  active = M3.d.primary,
  thickness,
  period = 40,
  style,
}) => {
  const amp = height * 0.32;
  const sw = thickness ?? height * 0.52;
  const p = Math.max(0, Math.min(1, progress));
  const aw = width * p;
  return (
    <svg
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      style={{display: 'block', overflow: 'visible', ...style}}
    >
      <path
        d={wavePath(width, height, {amp, period, phase})}
        stroke={track}
        strokeWidth={sw}
        fill="none"
        strokeLinecap="round"
      />
      {p > 0.001 ? (
        <path
          d={wavePath(aw, height, {amp, period, phase})}
          stroke={active}
          strokeWidth={sw}
          fill="none"
          strokeLinecap="round"
        />
      ) : null}
    </svg>
  );
};

/** 缓存条（按音源隔离的两列，用不同色调区分） */
export const CacheBar: React.FC<{
  readonly value: number;
  readonly width: number;
  readonly color: string;
  readonly height?: number;
  readonly style?: React.CSSProperties;
}> = ({value, width, color, height = 14, style}) => (
  <div
    style={{
      width,
      height,
      borderRadius: 999,
      background: rgba(color, 0.18),
      overflow: 'hidden',
      ...style,
    }}
  >
    <div
      style={{
        width: width * Math.max(0, Math.min(1, value)),
        height: '100%',
        borderRadius: 999,
        background: color,
      }}
    />
  </div>
);
