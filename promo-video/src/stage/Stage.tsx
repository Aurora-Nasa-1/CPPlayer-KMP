import React from 'react';
import {AbsoluteFill, useCurrentFrame} from 'remotion';
import {M3, rgba} from '../theme';
import {MorphShape} from '../m3/MorphShape';
import {SHAPES} from '../lib/shape';

/**
 * 2.5D 倾斜盒子。
 *
 * 变换顺序是 `rotateX · rotateY · rotateZ · translate3d` —— CSS 的变换函数
 * **从右往左**作用于元素 ⇒ 内容先在自己的平面内平移，再整体倾斜，
 * 也就是「平面被摆斜之后，相机沿平面横移」（平轴 2.5D）。
 *
 * 用普通 div（不是 AbsoluteFill）是为了能放进 flex 布局当"一个会倾斜的盒子"，
 * 例如「标题保持水平，只有上面那个图形在 2.5D 里转」。
 */
export const Tilt: React.FC<{
  readonly perspective?: number;
  readonly rx?: number;
  readonly ry?: number;
  readonly rz?: number;
  readonly x?: number;
  readonly y?: number;
  readonly z?: number;
  readonly scale?: number;
  readonly origin?: string;
  readonly style?: React.CSSProperties;
  readonly children: React.ReactNode;
}> = ({
  perspective = 1500,
  rx = 0,
  ry = 0,
  rz = 0,
  x = 0,
  y = 0,
  z = 0,
  scale = 1,
  origin = '50% 50%',
  style,
  children,
}) => (
  <div style={{perspective, ...style}}>
    <div
      style={{
        transformStyle: 'preserve-3d',
        transformOrigin: origin,
        transform: `rotateX(${rx}deg) rotateY(${ry}deg) rotateZ(${rz}deg) translate3d(${x}px,${y}px,${z}px) scale(${scale})`,
      }}
    >
      {children}
    </div>
  </div>
);

/* ------------------------------------------------------------------ */
/* 贯穿全片的动态背景                                                    */
/* ------------------------------------------------------------------ */

const NOISE =
  "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='220' height='220'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='3' stitchTiles='stitch'/></filter><rect width='220' height='220' filter='url(%23n)'/></svg>";

const Blob: React.FC<{
  readonly x: number;
  readonly y: number;
  readonly size: number;
  readonly color: string;
  readonly opacity: number;
  readonly blur?: number;
}> = ({x, y, size, color, opacity, blur = 150}) => (
  <div
    style={{
      position: 'absolute',
      left: x,
      top: y,
      width: size,
      height: size,
      borderRadius: '50%',
      background: color,
      filter: `blur(${blur}px)`,
      opacity,
      translate: '-50% -50%',
    }}
  />
);

/**
 * 持续演化的 M3E 背景：三组色相光晕缓慢漂移 + 形变剪影 + 细网格 + 暗角。
 * 它挂在 TransitionSeries **外面**，所以场景之间不会黑场，整片是连续的一口气。
 */
export const Backdrop: React.FC<{
  readonly width: number;
  readonly height: number;
  readonly intensity?: number;
}> = ({width, height, intensity = 1}) => {
  const f = useCurrentFrame();
  const t = f / 30;
  // 极慢的多条正弦叠加，保证 80s 内不出现肉眼可辨的重复
  const dx = Math.sin(t * 0.11) * 120 + Math.sin(t * 0.037) * 60;
  const dy = Math.cos(t * 0.083) * 90;
  const dx2 = Math.cos(t * 0.061 + 1.3) * 150;
  const dy2 = Math.sin(t * 0.047 + 0.6) * 110;
  const morphP = (Math.sin(t * 0.075) + 1) / 2;

  return (
    <AbsoluteFill style={{background: M3.d.surfaceLowest, overflow: 'hidden'}}>
      <Blob x={width * 0.22 + dx} y={height * 0.24 + dy} size={width * 0.72} color={M3.d.primaryContainer} opacity={0.55 * intensity} />
      <Blob x={width * 0.82 + dx2} y={height * 0.72 + dy2} size={width * 0.6} color={M3.d.secondaryContainer} opacity={0.42 * intensity} />
      <Blob x={width * 0.6 + dx2 * 0.4} y={height * 0.08 + dy * 0.5} size={width * 0.44} color={M3.d.tertiaryContainer} opacity={0.3 * intensity} />
      <Blob x={width * 0.06 - dx * 0.3} y={height * 0.92 - dy2 * 0.3} size={width * 0.5} color={M3.d.primaryContainer} opacity={0.34 * intensity} />

      {/* 形变剪影：把 M3E 的形变语言直接铺进背景 */}
      <div style={{position: 'absolute', left: '50%', top: '50%', translate: '-50% -50%', opacity: 0.16}}>
        <MorphShape
          shape={morphP < 0.5 ? SHAPES.blob : SHAPES.blob5}
          size={width * 1.5}
          id="bg-blob"
          fill={M3.d.primary}
          rot={t * 0.05}
        />
      </div>

      {/* 细网格：给"工程感"，几乎看不见但撑住了画面 */}
      <div
        style={{
          position: 'absolute',
          inset: 0,
          backgroundImage: `linear-gradient(${rgba(M3.d.onSurface, 0.028)} 1px, transparent 1px), linear-gradient(90deg, ${rgba(
            M3.d.onSurface,
            0.028,
          )} 1px, transparent 1px)`,
          backgroundSize: '72px 72px',
          maskImage: 'radial-gradient(ellipse 78% 68% at 50% 46%, #000 30%, transparent 100%)',
          WebkitMaskImage: 'radial-gradient(ellipse 78% 68% at 50% 46%, #000 30%, transparent 100%)',
        }}
      />

      <AbsoluteFill
        style={{
          background: `radial-gradient(ellipse 92% 82% at 50% 48%, transparent 34%, ${rgba('#000000', 0.62)} 100%)`,
        }}
      />
    </AbsoluteFill>
  );
};

/** 胶片颗粒（每帧随机偏移，避免"糊死的静态噪点"） */
export const Grain: React.FC<{readonly opacity?: number}> = ({opacity = 0.055}) => {
  const f = useCurrentFrame();
  const ox = (f * 37) % 220;
  const oy = (f * 71) % 220;
  return (
    <AbsoluteFill
      style={{
        backgroundImage: `url("${NOISE}")`,
        backgroundPosition: `${ox}px ${oy}px`,
        backgroundSize: '220px 220px',
        opacity,
        mixBlendMode: 'overlay',
        pointerEvents: 'none',
      }}
    />
  );
};
