import React from 'react';
import {TransitionSeries, linearTiming} from '@remotion/transitions';
import {fade} from '@remotion/transitions/fade';
import {WinHook, WinWindow, WinMedia, WinSource, WinDaily, WinEnd} from './scenes';

const TR = 18;

/** CPPlayer · Windows 实用派（50s / 1920x1080 / 30fps） */
export const WindowsPractical: React.FC = () => (
  <TransitionSeries>
    <TransitionSeries.Sequence name="01 MSI 安装" durationInFrames={288} premountFor={30}>
      <WinHook />
    </TransitionSeries.Sequence>
    <TransitionSeries.Transition
      presentation={fade()}
      timing={linearTiming({durationInFrames: TR})}
    />
    <TransitionSeries.Sequence name="02 原生窗口" durationInFrames={312} premountFor={30}>
      <WinWindow />
    </TransitionSeries.Sequence>
    <TransitionSeries.Transition
      presentation={fade()}
      timing={linearTiming({durationInFrames: TR})}
    />
    <TransitionSeries.Sequence name="03 任务栏媒体控制" durationInFrames={300} premountFor={30}>
      <WinMedia />
    </TransitionSeries.Sequence>
    <TransitionSeries.Transition
      presentation={fade()}
      timing={linearTiming({durationInFrames: TR})}
    />
    <TransitionSeries.Sequence name="04 音源管理" durationInFrames={288} premountFor={30}>
      <WinSource />
    </TransitionSeries.Sequence>
    <TransitionSeries.Transition
      presentation={fade()}
      timing={linearTiming({durationInFrames: TR})}
    />
    <TransitionSeries.Sequence name="05 下载与本地音乐" durationInFrames={258} premountFor={30}>
      <WinDaily />
    </TransitionSeries.Sequence>
    <TransitionSeries.Transition
      presentation={fade()}
      timing={linearTiming({durationInFrames: TR})}
    />
    <TransitionSeries.Sequence name="06 收尾" durationInFrames={144} premountFor={30}>
      <WinEnd />
    </TransitionSeries.Sequence>
  </TransitionSeries>
);
