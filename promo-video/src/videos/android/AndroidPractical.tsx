import React from 'react';
import {TransitionSeries, linearTiming} from '@remotion/transitions';
import {fade} from '@remotion/transitions/fade';
import {
  AndroidHook,
  AndroidNotif,
  AndroidBack,
  AndroidData,
  AndroidOffline,
  AndroidPlayer,
  AndroidEnd,
} from './scenes';

const TR = 18;

/** CPPlayer · Android 实用派（50s / 1080x1920 竖屏 / 30fps） */
export const AndroidPractical: React.FC = () => (
  <TransitionSeries>
    <TransitionSeries.Sequence name="01 手机主界面" durationInFrames={240} premountFor={30}>
      <AndroidHook />
    </TransitionSeries.Sequence>
    <TransitionSeries.Transition
      presentation={fade()}
      timing={linearTiming({durationInFrames: TR})}
    />
    <TransitionSeries.Sequence name="02 通知栏锁屏控制" durationInFrames={270} premountFor={30}>
      <AndroidNotif />
    </TransitionSeries.Sequence>
    <TransitionSeries.Transition
      presentation={fade()}
      timing={linearTiming({durationInFrames: TR})}
    />
    <TransitionSeries.Sequence name="03 后台不断播" durationInFrames={240} premountFor={30}>
      <AndroidBack />
    </TransitionSeries.Sequence>
    <TransitionSeries.Transition
      presentation={fade()}
      timing={linearTiming({durationInFrames: TR})}
    />
    <TransitionSeries.Sequence name="04 省流量缓存" durationInFrames={252} premountFor={30}>
      <AndroidData />
    </TransitionSeries.Sequence>
    <TransitionSeries.Transition
      presentation={fade()}
      timing={linearTiming({durationInFrames: TR})}
    />
    <TransitionSeries.Sequence name="05 离线下载" durationInFrames={246} premountFor={30}>
      <AndroidOffline />
    </TransitionSeries.Sequence>
    <TransitionSeries.Transition
      presentation={fade()}
      timing={linearTiming({durationInFrames: TR})}
    />
    <TransitionSeries.Sequence name="06 播放页" durationInFrames={240} premountFor={30}>
      <AndroidPlayer />
    </TransitionSeries.Sequence>
    <TransitionSeries.Transition
      presentation={fade()}
      timing={linearTiming({durationInFrames: TR})}
    />
    <TransitionSeries.Sequence name="07 收尾" durationInFrames={120} premountFor={30}>
      <AndroidEnd />
    </TransitionSeries.Sequence>
  </TransitionSeries>
);
