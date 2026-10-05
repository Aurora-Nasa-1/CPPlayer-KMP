import React from 'react';
import {AbsoluteFill, Audio, Easing, staticFile} from 'remotion';
import {TransitionSeries, linearTiming} from '@remotion/transitions';
import {fade} from '@remotion/transitions/fade';
import {M3} from './theme';
import {Backdrop, Grain} from './stage/Stage';
import {S01Logo, S02Hook} from './scenes/open';
import {S03Import, S04Switch, S05Failover, S06Cache} from './scenes/source';
import {S07Design, S08Kmp, S09Features, S10End} from './scenes/showcase';

/**
 * 全片编排。
 *
 * 关键设计：**Backdrop 与 Grain 挂在 TransitionSeries 外面**。
 * 这样整片有一条连续演化的背景，场景之间是「内容叠化」而不是「整屏黑一下再亮」，
 * 观感上是一口气，而不是十段拼接。
 *
 * 场景内部一律不画背景（见 stage/preview.tsx 的说明）：
 * 成片由这里提供，Studio 预览由 withStage 提供，两处只有一份真值。
 */

export const TRANSITION = 22;

export const PROMO_SCENES: ReadonlyArray<{id: string; C: React.FC; dur: number}> = [
  {id: 'S01Logo', C: S01Logo, dur: 210},
  {id: 'S02Hook', C: S02Hook, dur: 180},
  {id: 'S03Import', C: S03Import, dur: 270},
  {id: 'S04Switch', C: S04Switch, dur: 240},
  {id: 'S05Failover', C: S05Failover, dur: 270},
  {id: 'S06Cache', C: S06Cache, dur: 240},
  {id: 'S07Design', C: S07Design, dur: 420},
  {id: 'S08Kmp', C: S08Kmp, dur: 240},
  {id: 'S09Features', C: S09Features, dur: 240},
  {id: 'S10End', C: S10End, dur: 240},
];

export const PROMO_TOTAL =
  PROMO_SCENES.reduce((a, s) => a + s.dur, 0) - TRANSITION * (PROMO_SCENES.length - 1);

const timing = linearTiming({
  durationInFrames: TRANSITION,
  easing: Easing.bezier(0.4, 0, 0.2, 1),
});

/**
 * 配乐音量自动化。
 *
 * 曲子本身（public/bgm.mp3，"Home Tonight" by DoKashiteru, CC BY 2.5）是 3:31，
 * 片长 78.4s 只用到前 78s，**不需要 loop**。
 * 但它 0–78s 段是一段平稳的 loop，没有天然的高潮 —— 所以用音量曲线给它做出
 * 「进场 → 缓慢抬升 → 收尾」的弧线：抬升幅度只有 0.14，听得出来但不突兀。
 */
const BGM_IN = 40; // 1.3s 淡入
const BGM_OUT = PROMO_TOTAL - 110; // 结尾 3.7s 淡出

const bgmVolume = (frame: number): number => {
  if (frame <= BGM_IN) return (frame / BGM_IN) * 0.8;
  if (frame >= BGM_OUT) {
    const p = Math.min(1, (frame - BGM_OUT) / (PROMO_TOTAL - BGM_OUT));
    return 0.94 * (1 - p);
  }
  return 0.8 + 0.14 * ((frame - BGM_IN) / (BGM_OUT - BGM_IN));
};

export const Promo: React.FC = () => {
  return (
    <AbsoluteFill style={{background: M3.d.surfaceLowest}}>
      <Backdrop width={1920} height={1080} />

      <TransitionSeries>
        {PROMO_SCENES.map(({id, C}, i) => (
          <React.Fragment key={id}>
            <TransitionSeries.Sequence durationInFrames={PROMO_SCENES[i].dur}>
              <C />
            </TransitionSeries.Sequence>
            {i < PROMO_SCENES.length - 1 ? <TransitionSeries.Transition presentation={fade()} timing={timing} /> : null}
          </React.Fragment>
        ))}
      </TransitionSeries>

      <Grain />

      {/* 配乐：署名见 promo-video/ATTRIBUTION.txt（CC BY 2.5 要求保留） */}
      <Audio src={staticFile('bgm.mp3')} volume={bgmVolume} />
    </AbsoluteFill>
  );
};
