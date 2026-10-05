import React from 'react';
import {shapePath, shapePerimeter, type Polar} from '../lib/shape';

/**
 * M3E 形变形状。
 *
 * - `MorphShape` 渲染一个实心/描边形状（SVG）
 * - `MorphClip` 用形状去裁切任意 HTML 子树（CSS clip-path: path()）
 *
 * ⚠️ 所有 `<defs>` 的 id 都由调用方通过 `id` 传入并保持稳定 ——
 * 不能用 useId()（React 生成 `:r0:` 这类含冒号的 id，在 `url(#...)` 里非法），
 * 也不能用模块级自增计数器（Remotion 逐帧渲染，每帧 id 都会变，defs 引用会失效）。
 */

type Grad = {from: string; to: string; angle?: number; via?: string};

const gradVec = (angle = 135): [number, number, number, number] => {
  const a = (angle * Math.PI) / 180;
  const dx = Math.cos(a) / 2;
  const dy = Math.sin(a) / 2;
  return [0.5 - dx, 0.5 - dy, 0.5 + dx, 0.5 + dy];
};

export const MorphShape: React.FC<{
  readonly shape: Polar;
  readonly size: number;
  readonly id: string;
  readonly fill?: string;
  readonly gradient?: Grad;
  readonly stroke?: string;
  readonly strokeWidth?: number;
  /** 0→1：沿周长「画」出来（stroke-dashoffset 动画） */
  readonly draw?: number;
  readonly rot?: number;
  readonly wobble?: number;
  readonly seed?: number;
  readonly opacity?: number;
  readonly glow?: string;
  readonly style?: React.CSSProperties;
}> = ({
  shape,
  size,
  id,
  fill,
  gradient,
  stroke,
  strokeWidth = 0,
  draw = 1,
  rot = 0,
  wobble = 0,
  seed = 0,
  opacity = 1,
  glow,
  style,
}) => {
  const d = shapePath(shape, size, {rot, wobble, seed});
  const gid = `${id}-g`;
  const dash = shapePerimeter(shape, size);
  const [x1, y1, x2, y2] = gradVec(gradient?.angle);
  return (
    <svg
      width={size}
      height={size}
      viewBox={`0 0 ${size} ${size}`}
      style={{
        display: 'block',
        overflow: 'visible',
        filter: glow ? `drop-shadow(0 0 ${size * 0.09}px ${glow})` : undefined,
        opacity,
        ...style,
      }}
    >
      {gradient ? (
        <defs>
          <linearGradient id={gid} x1={x1} y1={y1} x2={x2} y2={y2}>
            <stop offset="0%" stopColor={gradient.from} />
            {gradient.via ? <stop offset="52%" stopColor={gradient.via} /> : null}
            <stop offset="100%" stopColor={gradient.to} />
          </linearGradient>
        </defs>
      ) : null}
      <path
        d={d}
        fill={gradient ? `url(#${gid})` : fill ?? 'none'}
        stroke={stroke}
        strokeWidth={strokeWidth}
        strokeLinejoin="round"
        strokeLinecap="round"
        strokeDasharray={stroke ? dash : undefined}
        strokeDashoffset={stroke ? dash * (1 - draw) : undefined}
      />
    </svg>
  );
};

/** 用形变形状裁切任意 HTML 子树 */
export const MorphClip: React.FC<{
  readonly shape: Polar;
  readonly size: number;
  readonly rot?: number;
  readonly style?: React.CSSProperties;
  readonly children: React.ReactNode;
}> = ({shape, size, rot = 0, style, children}) => {
  const d = shapePath(shape, size, {rot});
  return (
    <div
      style={{
        width: size,
        height: size,
        clipPath: `path('${d}')`,
        WebkitClipPath: `path('${d}')`,
        ...style,
      }}
    >
      {children}
    </div>
  );
};
