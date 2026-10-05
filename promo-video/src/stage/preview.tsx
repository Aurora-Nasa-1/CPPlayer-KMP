import React from 'react';
import {AbsoluteFill, useVideoConfig} from 'remotion';
import {Backdrop, Grain} from './Stage';

/**
 * 场景预览包装。
 *
 * 成片里 Backdrop 是**挂在 TransitionSeries 外面**的（全片连续的一口气，
 * 场景切换不会黑场）。但那样一来，单场景 composition 预览就是白底，
 * 根本看不出真实的对比度与构图 —— 上一轮就是因为只看白底静帧，
 * 把一堆"在白底上勉强能看"的配色交了出去。
 *
 * 所以：**成片用裸场景，Studio/出图用 withStage 包一层**。
 */
export const withStage = <P extends object>(C: React.FC<P>): React.FC<P> => {
  const Wrapped: React.FC<P> = (props) => {
    const {width, height} = useVideoConfig();
    return (
      <AbsoluteFill>
        <Backdrop width={width} height={height} />
        <C {...props} />
        <Grain />
      </AbsoluteFill>
    );
  };
  Wrapped.displayName = `withStage(${C.displayName ?? C.name ?? 'Scene'})`;
  return Wrapped;
};
