import React from 'react';
import {Img, staticFile} from 'remotion';
import {M3, rgba, R} from '../theme';

/**
 * 设备外壳。全部使用**仓库里的真机截图**（public/shots/），
 * 不再自绘假 mock UI —— 上一版最大的问题就是"界面是编的"。
 *
 * 截图实测尺寸：
 *   android-*  1440×3200  (0.450)
 *   desktop-library 1601×1121 (0.700)
 *   desktop-sources 716×346 / diagnostics 716×489 —— 是设置页的面板裁切，
 *   **不能大幅放大**，否则糊。面板类走 ShotPanel，按接近原生尺寸摆放。
 */

/** 手机外壳：屏幕内容为真实截图 */
export const PhoneShell: React.FC<{
  readonly src: string;
  readonly width?: number;
  readonly style?: React.CSSProperties;
  readonly imgStyle?: React.CSSProperties;
  readonly bezel?: number;
  readonly glow?: string;
  readonly children?: React.ReactNode;
}> = ({src, width = 430, style, imgStyle, bezel, glow, children}) => {
  const height = width / 0.45;
  const b = bezel ?? Math.max(7, width * 0.026);
  return (
    <div
      style={{
        width,
        height,
        borderRadius: width * 0.115,
        border: `${b}px solid #1A1A20`,
        background: '#0B0B10',
        boxShadow: [
          '0 60px 120px -40px rgba(0,0,0,0.9)',
          '0 0 0 1px rgba(255,255,255,0.09)',
          glow ? `0 0 120px -20px ${rgba(glow, 0.55)}` : null,
        ]
          .filter(Boolean)
          .join(','),
        overflow: 'hidden',
        position: 'relative',
        flexShrink: 0,
        ...style,
      }}
    >
      <Img
        src={staticFile(src)}
        style={{
          position: 'absolute',
          inset: 0,
          width: '100%',
          height: '100%',
          objectFit: 'cover',
          ...imgStyle,
        }}
      />
      {/* 灵动岛 */}
      <div
        style={{
          position: 'absolute',
          top: width * 0.032,
          left: '50%',
          translate: '-50% 0',
          width: width * 0.29,
          height: width * 0.078,
          borderRadius: 999,
          background: '#050508',
          zIndex: 6,
        }}
      />
      {children}
    </div>
  );
};

/** 桌面窗口：截图本身已含窗口 chrome（标题栏 + 最小化/关闭），所以不再自绘标题栏 */
export const DesktopShell: React.FC<{
  readonly src: string;
  readonly width?: number;
  readonly ratio?: number;
  readonly radius?: number;
  readonly glow?: string;
  readonly style?: React.CSSProperties;
  readonly imgStyle?: React.CSSProperties;
  readonly children?: React.ReactNode;
}> = ({src, width = 1240, ratio = 0.700, radius = R.l, glow, style, imgStyle, children}) => (
  <div
    style={{
      width,
      height: width * ratio,
      borderRadius: radius,
      overflow: 'hidden',
      position: 'relative',
      background: M3.d.surfaceLow,
      boxShadow: [
        '0 70px 150px -50px rgba(0,0,0,0.92)',
        '0 0 0 1px rgba(255,255,255,0.1)',
        glow ? `0 0 140px -30px ${rgba(glow, 0.5)}` : null,
      ]
        .filter(Boolean)
        .join(','),
      flexShrink: 0,
      ...style,
    }}
  >
    <Img
      src={staticFile(src)}
      style={{position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover', ...imgStyle}}
    />
    {children}
  </div>
);

/** 面板截图（设置页的局部裁切），带内描边与渐隐，避免边缘生硬 */
export const ShotPanel: React.FC<{
  readonly src: string;
  readonly width: number;
  readonly ratio: number;
  readonly radius?: number;
  readonly glow?: string;
  readonly style?: React.CSSProperties;
  readonly children?: React.ReactNode;
}> = ({src, width, ratio, radius = R.m, glow, style, children}) => (
  <div
    style={{
      width,
      height: width * ratio,
      borderRadius: radius,
      overflow: 'hidden',
      position: 'relative',
      background: M3.d.surfaceLow,
      boxShadow: [
        '0 44px 90px -40px rgba(0,0,0,0.9)',
        '0 0 0 1px rgba(255,255,255,0.11)',
        glow ? `0 0 110px -30px ${rgba(glow, 0.5)}` : null,
      ]
        .filter(Boolean)
        .join(','),
      flexShrink: 0,
      ...style,
    }}
  >
    <Img
      src={staticFile(src)}
      style={{position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover'}}
    />
    {children}
  </div>
);

/** 2.5D 悬浮卡片：自带顶部高光与底部投影，用于"漂在空中"的截图 */
export const FloatCard: React.FC<{
  readonly width: number;
  readonly height: number;
  readonly radius?: number;
  readonly tone?: string;
  readonly style?: React.CSSProperties;
  readonly children: React.ReactNode;
}> = ({width, height, radius = R.xl, tone = M3.d.primary, style, children}) => (
  <div
    style={{
      width,
      height,
      borderRadius: radius,
      position: 'relative',
      background: `linear-gradient(150deg, ${rgba(tone, 0.16)}, ${rgba(M3.d.surfaceHigh, 0.92)} 42%, ${rgba(
        M3.d.surfaceContainer,
        0.96,
      )})`,
      border: `1px solid ${rgba(tone, 0.3)}`,
      boxShadow: `0 50px 100px -45px rgba(0,0,0,0.95), inset 0 1px 0 ${rgba('#FFFFFF', 0.1)}`,
      boxSizing: 'border-box',
      overflow: 'hidden',
      ...style,
    }}
  >
    {children}
  </div>
);

/** 设备下方的品牌光晕 */
export const DeviceGlow: React.FC<{
  readonly width: number;
  readonly height?: number;
  readonly color?: string;
  readonly opacity?: number;
  readonly style?: React.CSSProperties;
}> = ({width, height = 160, color = M3.d.primary, opacity = 0.5, style}) => (
  <div
    style={{
      position: 'absolute',
      left: '50%',
      translate: '-50% 0',
      width,
      height,
      borderRadius: '50%',
      background: `radial-gradient(ellipse at center, ${rgba(color, opacity)} 0%, transparent 70%)`,
      filter: 'blur(40px)',
      pointerEvents: 'none',
      ...style,
    }}
  />
);
