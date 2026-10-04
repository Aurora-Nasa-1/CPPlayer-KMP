import React from 'react';
import {TransitionSeries, linearTiming} from '@remotion/transitions';
import {fade} from '@remotion/transitions/fade';
import {
  LinuxHook,
  LinuxArch,
  LinuxFailover,
  LinuxCache,
  LinuxStream,
  LinuxDiag,
  LinuxKmp,
  LinuxReal,
  LinuxEnd,
} from './scenes';

const TR = 18;

/** CPPlayer · Linux 极客版（67.4s / 1920x1080 / 30fps） */
export const LinuxGeek: React.FC = () => (
  <TransitionSeries>
    <TransitionSeries.Sequence name="01 终端安装" durationInFrames={300} premountFor={30}>
      <LinuxHook />
    </TransitionSeries.Sequence>
    <TransitionSeries.Transition
      presentation={fade()}
      timing={linearTiming({durationInFrames: TR})}
    />
    <TransitionSeries.Sequence name="02 音源即插件" durationInFrames={300} premountFor={30}>
      <LinuxArch />
    </TransitionSeries.Sequence>
    <TransitionSeries.Transition
      presentation={fade()}
      timing={linearTiming({durationInFrames: TR})}
    />
    <TransitionSeries.Sequence name="03 多 Provider 容灾" durationInFrames={270} premountFor={30}>
      <LinuxFailover />
    </TransitionSeries.Sequence>
    <TransitionSeries.Transition
      presentation={fade()}
      timing={linearTiming({durationInFrames: TR})}
    />
    <TransitionSeries.Sequence name="04 读透缓存" durationInFrames={270} premountFor={30}>
      <LinuxCache />
    </TransitionSeries.Sequence>
    <TransitionSeries.Transition
      presentation={fade()}
      timing={linearTiming({durationInFrames: TR})}
    />
    <TransitionSeries.Sequence name="05 流推送 8080/8420" durationInFrames={300} premountFor={30}>
      <LinuxStream />
    </TransitionSeries.Sequence>
    <TransitionSeries.Transition
      presentation={fade()}
      timing={linearTiming({durationInFrames: TR})}
    />
    <TransitionSeries.Sequence name="06 诊断页" durationInFrames={216} premountFor={30}>
      <LinuxDiag />
    </TransitionSeries.Sequence>
    <TransitionSeries.Transition
      presentation={fade()}
      timing={linearTiming({durationInFrames: TR})}
    />
    <TransitionSeries.Sequence name="07 KMP 分层" durationInFrames={180} premountFor={30}>
      <LinuxKmp />
    </TransitionSeries.Sequence>
    <TransitionSeries.Transition
      presentation={fade()}
      timing={linearTiming({durationInFrames: TR})}
    />
    <TransitionSeries.Sequence name="08 真软件实拍" durationInFrames={240} premountFor={30}>
      <LinuxReal />
    </TransitionSeries.Sequence>
    <TransitionSeries.Transition
      presentation={fade()}
      timing={linearTiming({durationInFrames: TR})}
    />
    <TransitionSeries.Sequence name="09 收尾" durationInFrames={90} premountFor={30}>
      <LinuxEnd />
    </TransitionSeries.Sequence>
  </TransitionSeries>
);
