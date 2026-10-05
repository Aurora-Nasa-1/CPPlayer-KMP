import React from 'react';
import {Composition, Folder} from 'remotion';
import {Promo, PROMO_SCENES, PROMO_TOTAL} from './Film';
import {S01Logo, S02Hook} from './scenes/open';
import {S03Import, S04Switch, S05Failover, S06Cache} from './scenes/source';
import {S07Design, S08Kmp, S09Features, S10End} from './scenes/showcase';
import {withStage} from './stage/preview';

const HD = {width: 1920, height: 1080, fps: 30} as const;

/** 逐幕单独注册，Studio 里可单独调、出图核对只需渲一帧 */
const SCENE_COMPONENTS: Record<string, React.FC> = {
  S01Logo,
  S02Hook,
  S03Import,
  S04Switch,
  S05Failover,
  S06Cache,
  S07Design,
  S08Kmp,
  S09Features,
  S10End,
};

export const RemotionRoot: React.FC = () => {
  return (
    <>
      <Folder name="Film">
        <Composition id="CPPlayerPromo" component={Promo} {...HD} durationInFrames={PROMO_TOTAL} />
      </Folder>

      <Folder name="Scenes">
        {PROMO_SCENES.map(({id, dur}) => (
          <Composition
            key={id}
            id={id}
            component={withStage(SCENE_COMPONENTS[id])}
            {...HD}
            durationInFrames={dur}
          />
        ))}
      </Folder>
    </>
  );
};
