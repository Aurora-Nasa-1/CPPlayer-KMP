import React from 'react';
import {AbsoluteFill, Easing, interpolate, useCurrentFrame} from 'remotion';
import {C, FONT, MONO, SAFE_BOTTOM, SAFE_TOP, SAFE_X} from '../theme';
import {VO} from '../vo.generated';
import {Subtitle} from './Subtitle';

// 统一的页面骨架：安全边距 + 左上角章节标记 + 常驻字幕 + 进出场溶解。
// 进出场用 ease-out / ease-in（不是弹簧）：弹簧前 5 帧几乎全透明，
// 多镜头累加会出现「画面是空的」，观感像掉帧。
export const Stage: React.FC<{
  index: string;
  label: string;
  duration: number;
  vo: number;
  children: React.ReactNode;
  labelColor?: string;
}> = ({index, label, duration, vo, children, labelColor = C.primary}) => {
  const frame = useCurrentFrame();

  const inP = interpolate(frame, [0, 12], [0, 1], {
    easing: Easing.out(Easing.cubic),
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const outP = interpolate(frame, [duration - 11, duration - 1], [1, 0], {
    easing: Easing.in(Easing.cubic),
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const opacity = Math.min(inP, outP);

  return (
    <AbsoluteFill
      style={{
        fontFamily: FONT,
        padding: `${SAFE_TOP}px ${SAFE_X}px ${SAFE_BOTTOM}px`,
        opacity,
        transform: `translateY(${(1 - inP) * 16}px)`,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 18,
          fontFamily: MONO,
          fontSize: 21,
          letterSpacing: 1.4,
          color: C.inkVar,
        }}
      >
        <span
          style={{
            display: 'inline-block',
            width: 40,
            height: 3,
            borderRadius: 2,
            background: labelColor,
          }}
        />
        <span style={{color: labelColor, fontWeight: 600}}>{index}</span>
        <span style={{opacity: 0.55}}>/ 05</span>
        <span style={{opacity: 0.4}}>·</span>
        <span style={{opacity: 0.85}}>{label}</span>
      </div>
      <div style={{flex: 1, position: 'relative'}}>{children}</div>
      <Subtitle lines={VO[vo].lines} seconds={VO[vo].seconds} voStart={VO[vo].voStart} />
    </AbsoluteFill>
  );
};

// 给不使用 Stage 的整版镜头（开场 / 收尾）用的纯淡入淡出壳。
export const SceneFade: React.FC<{
  duration: number;
  fadeIn?: boolean;
  children: React.ReactNode;
}> = ({duration, fadeIn = true, children}) => {
  const frame = useCurrentFrame();
  const inP = fadeIn
    ? interpolate(frame, [0, 12], [0, 1], {
        easing: Easing.out(Easing.cubic),
        extrapolateLeft: 'clamp',
        extrapolateRight: 'clamp',
      })
    : 1;
  const outP = interpolate(frame, [duration - 11, duration - 1], [1, 0], {
    easing: Easing.in(Easing.cubic),
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  return (
    <AbsoluteFill style={{opacity: Math.min(inP, outP), fontFamily: FONT}}>{children}</AbsoluteFill>
  );
};
