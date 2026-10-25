import React from 'react';
import {AbsoluteFill, Audio, interpolate, Sequence, staticFile} from 'remotion';
import {C, TOTAL} from './theme';
import {Backdrop} from './components/Backdrop';
import {VO} from './vo.generated';
import {S1Hook} from './scenes/S1Hook';
import {S2Deck} from './scenes/S2Deck';
import {S3Spot} from './scenes/S3Spot';
import {S4Provider} from './scenes/S4Provider';
import {S5Outro} from './scenes/S5Outro';

const REGISTRY = [S1Hook, S2Deck, S3Spot, S4Provider, S5Outro];

export const Film: React.FC = () => (
  <AbsoluteFill style={{backgroundColor: C.bg}}>
    {/* 全片共用一层背景：换镜头时氛围不断，只有内容在换 */}
    <Backdrop />

    {/* 讲解音轨：每段口播落在各自场景内部，帧位置天然与画面同步 */}
    {VO.map((v) => (
      <Sequence
        key={`vo-${v.id}`}
        from={v.start + v.voStart}
        durationInFrames={Math.ceil(v.seconds * 30) + 4}
        name={`vo-${v.id}`}
      >
        <Audio src={staticFile(v.file)} volume={1} />
      </Sequence>
    ))}

    {/* 垫乐压到很低的电平，只铺一层底噪，不与讲解抢频段 */}
    <Audio
      src={staticFile('bgm.mp3')}
      volume={(f) =>
        interpolate(f, [0, 30, TOTAL - 70, TOTAL], [0, 0.1, 0.1, 0], {
          extrapolateLeft: 'clamp',
          extrapolateRight: 'clamp',
        })
      }
    />

    {VO.map((v, i) => {
      const Scene = REGISTRY[i];
      return (
        <Sequence key={v.id} from={v.start} durationInFrames={v.dur} name={v.id}>
          <Scene />
        </Sequence>
      );
    })}
  </AbsoluteFill>
);
