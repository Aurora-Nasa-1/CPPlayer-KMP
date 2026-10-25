import React from 'react';
import {Composition} from 'remotion';
import {Film} from './Film';
import {FPS, HEIGHT, TOTAL, WIDTH} from './theme';

export const RemotionRoot: React.FC = () => (
  <Composition
    id="CpplayerPromo"
    component={Film}
    durationInFrames={TOTAL}
    fps={FPS}
    width={WIDTH}
    height={HEIGHT}
  />
);
